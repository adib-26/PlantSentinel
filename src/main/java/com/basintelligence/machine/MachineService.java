package com.basintelligence.machine;

import java.time.Clock;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Collectors;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.basintelligence.common.AuditService;
import com.basintelligence.common.InvalidStateException;
import com.basintelligence.common.NotFoundException;
import com.basintelligence.factory.Factory;
import com.basintelligence.factory.FactoryService;
import com.basintelligence.users.AccessScope;
import com.basintelligence.users.AccessScopeService;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/**
 * Machine registry. Every read goes through the caller's {@link AccessScope}: a machine outside the scope is reported
 * as not found, so its existence is not revealed. Background jobs (no request) see every machine.
 */
@Service
public class MachineService {

    /** Registration request, validated at the web boundary. {@code factoryCode} defaults to the caller's factory. */
    public record RegisterMachine(
            @NotBlank @Pattern(regexp = "[A-Z0-9][A-Z0-9-]{1,39}", message = "use 2-40 upper-case letters, digits or '-'") String code,
            @NotBlank @Size(max = 120) String name,
            @NotNull MachineType type,
            @Size(max = 120) String location,
            @Size(max = 40) String factoryCode) {

        public RegisterMachine(String code, String name, MachineType type, String location) {
            this(code, name, type, location, null);
        }
    }

    private final MachineRepository machines;
    private final FactoryService factories;
    private final AuditService audit;
    private final AccessScopeService access;
    private final JdbcClient jdbc;
    private final Clock clock;

    public MachineService(MachineRepository machines, FactoryService factories, AuditService audit,
            AccessScopeService access, JdbcClient jdbc, Clock clock) {
        this.machines = machines;
        this.factories = factories;
        this.audit = audit;
        this.access = access;
        this.jdbc = jdbc;
        this.clock = clock;
    }

    @Transactional
    public Machine register(RegisterMachine request, String actor) {
        AccessScope scope = access.current();
        Factory factory = registrationFactory(request.factoryCode(), scope);
        if (machines.existsByCode(request.code())) {
            throw new InvalidStateException("Machine code already registered: " + request.code());
        }
        UUID id = machines.insert(factory.id(), request.code(), request.name().strip(), request.type(),
                request.location() == null || request.location().isBlank() ? null : request.location().strip(),
                clock.instant());
        audit.record(actor, "MACHINE_REGISTERED", "device:" + request.code(),
                request.type() + " in " + factory.code());
        return machines.findById(id).orElseThrow();
    }

    /** Factories the caller may register machines in. */
    public List<Factory> registrableFactories() {
        AccessScope scope = access.current();
        if (scope.unrestricted()) {
            return factories.list();
        }
        return scope.kind() == AccessScope.Kind.FACTORY ? List.of(factories.get(scope.factoryId())) : List.of();
    }

    private Factory registrationFactory(String factoryCode, AccessScope scope) {
        boolean blank = factoryCode == null || factoryCode.isBlank();
        if (scope.unrestricted()) {
            return blank ? factories.defaultFactory() : factories.getByCode(factoryCode.strip());
        }
        if (scope.kind() != AccessScope.Kind.FACTORY) {
            throw new InvalidStateException("Your account is not allowed to register machines");
        }
        Factory own = factories.get(scope.factoryId());
        if (!blank && !own.code().equals(factoryCode.strip())) {
            throw new IllegalArgumentException("You can only register machines in your own factory (" + own.code() + ")");
        }
        return own;
    }

    /** Machines visible to the caller, ordered by code. */
    public List<Machine> list() {
        AccessScope scope = access.current();
        return switch (scope.kind()) {
            case ALL -> machines.findAll();
            case FACTORY -> machines.findByFactory(scope.factoryId());
            case ASSIGNED -> machines.findAssignedTo(scope.username(), scope.factoryId());
            case NONE -> List.of();
        };
    }

    /** Filter for lists of records that belong to machines (investigations, work orders, reports). */
    public VisibleMachines visible() {
        AccessScope scope = access.current();
        if (scope.unrestricted()) {
            return VisibleMachines.EVERYTHING;
        }
        return VisibleMachines.of(list().stream().map(Machine::id).collect(Collectors.toSet()));
    }

    public Machine get(UUID id) {
        return machines.findById(id).filter(this::permitted)
                .orElseThrow(() -> new NotFoundException("Machine not found: " + id));
    }

    public Machine getByCode(String code) {
        return machines.findByCode(code).filter(this::permitted)
                .orElseThrow(() -> new NotFoundException("Machine not found: " + code));
    }

    /** Throws {@link NotFoundException} unless the caller may see the machine. */
    public void checkAccess(UUID machineId) {
        get(machineId);
    }

    private boolean permitted(Machine machine) {
        AccessScope scope = access.current();
        return switch (scope.kind()) {
            case ALL -> true;
            case FACTORY -> machine.factoryId().equals(scope.factoryId());
            case ASSIGNED -> machine.factoryId().equals(scope.factoryId())
                    && machines.isAssignedTo(machine.id(), scope.username());
            case NONE -> false;
        };
    }

    // ---- technician assignments (administrators) ----

    public List<MachineRepository.Assignment> assignments() {
        return machines.findAssignments();
    }

    /** Gives a technician access to a machine. The technician must belong to the machine's factory. */
    @Transactional
    public void assignTechnician(String machineCode, String username, String actor) {
        Machine machine = getByCode(machineCode);
        var user = jdbc.sql("SELECT roles, factory_id FROM users WHERE username = :u AND enabled = TRUE")
                .param("u", username)
                .query((rs, n) -> new Object[] {rs.getString(1), rs.getObject(2, UUID.class)}).optional();
        if (user.isEmpty() || !roles((String) user.get()[0]).contains("TECHNICIAN")) {
            throw new IllegalArgumentException("Unknown technician: " + username);
        }
        if (!machine.factoryId().equals(user.get()[1])) {
            throw new IllegalArgumentException(username + " does not belong to the factory of " + machine.code());
        }
        if (!machines.insertAssignment(machine.id(), username, actor, clock.instant())) {
            throw new InvalidStateException(username + " is already assigned to " + machine.code());
        }
        audit.record(actor, "MACHINE_ASSIGNED", "device:" + machine.code(), "technician " + username);
    }

    @Transactional
    public void unassignTechnician(String machineCode, String username, String actor) {
        Machine machine = getByCode(machineCode);
        if (!machines.deleteAssignment(machine.id(), username)) {
            throw new NotFoundException(username + " is not assigned to " + machine.code());
        }
        audit.record(actor, "MACHINE_UNASSIGNED", "device:" + machine.code(), "technician " + username);
    }

    private static List<String> roles(String csv) {
        return Optional.ofNullable(csv).map(s -> Arrays.stream(s.split(",")).map(String::strip).toList()).orElse(List.of());
    }
}
