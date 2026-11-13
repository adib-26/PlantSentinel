package com.basintelligence.web.api;

import java.util.List;

import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import com.basintelligence.factory.Factory;
import com.basintelligence.factory.FactoryService;
import com.basintelligence.machine.MachineRepository;
import com.basintelligence.machine.MachineService;
import com.basintelligence.telemetry.EdgeGatewayService;
import com.basintelligence.users.UserAdminService;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/** Administration API: users, factories, edge gateways and technician assignments. ADMIN only. */
@RestController
@RequestMapping("/api/admin")
@Validated
@PreAuthorize("hasRole('ADMIN')")
@Tag(name = "Administration")
public class AdminApiController {

    public record NewFactory(
            @NotBlank @Pattern(regexp = "[A-Z0-9][A-Z0-9-]{1,39}", message = "use 2-40 upper-case letters, digits or '-'") String code,
            @NotBlank @Size(max = 120) String name,
            @Size(max = 200) String location) {
    }

    public record NewGateway(
            @NotBlank @Pattern(regexp = "[A-Za-z0-9._:-]{3,80}", message = "use 3-80 letters, digits or . _ : -") String code,
            @NotBlank @Size(max = 40) String factoryCode,
            @Size(max = 200) String description) {
    }

    public record Assignment(@NotBlank @Size(max = 80) String username) {
    }

    private final UserAdminService users;
    private final FactoryService factories;
    private final EdgeGatewayService gateways;
    private final MachineService machines;

    public AdminApiController(UserAdminService users, FactoryService factories, EdgeGatewayService gateways,
            MachineService machines) {
        this.users = users;
        this.factories = factories;
        this.gateways = gateways;
        this.machines = machines;
    }

    @GetMapping("/users")
    public List<UserAdminService.UserSummary> users() {
        return users.list();
    }

    @PostMapping("/users")
    @ResponseStatus(HttpStatus.CREATED)
    @Operation(summary = "Create a user (ADMIN, ENGINEER, TECHNICIAN or VIEWER) in a factory")
    public UserAdminService.UserSummary createUser(@Valid @RequestBody UserAdminService.NewUser request, Authentication auth) {
        return users.create(request, auth.getName());
    }

    @PatchMapping("/users/{username}")
    @Operation(summary = "Change a user's role, factory or enabled flag; ends the user's sessions")
    public UserAdminService.UserSummary updateUser(@PathVariable String username,
            @Valid @RequestBody UserAdminService.UpdateUser request, Authentication auth) {
        return users.update(username, request, auth.getName());
    }

    @PostMapping("/users/{username}/password")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @Operation(summary = "Set a new password; ends the user's sessions")
    public void resetPassword(@PathVariable String username, @Valid @RequestBody UserAdminService.PasswordReset request,
            Authentication auth) {
        users.resetPassword(username, request.password(), auth.getName());
    }

    @GetMapping("/factories")
    public List<Factory> factories() {
        return factories.list();
    }

    @PostMapping("/factories")
    @ResponseStatus(HttpStatus.CREATED)
    public Factory createFactory(@Valid @RequestBody NewFactory request, Authentication auth) {
        return factories.create(request.code(), request.name(), request.location(), auth.getName());
    }

    @GetMapping("/gateways")
    public List<EdgeGatewayService.EdgeGateway> gateways() {
        return gateways.list();
    }

    @PostMapping("/gateways")
    @ResponseStatus(HttpStatus.CREATED)
    @Operation(summary = "Register an edge gateway; the response contains its API key, shown only once")
    public EdgeGatewayService.IssuedKey createGateway(@Valid @RequestBody NewGateway request, Authentication auth) {
        return gateways.create(request.code(), request.factoryCode(), request.description(), auth.getName());
    }

    @PostMapping("/gateways/{code}/rotate")
    @Operation(summary = "Issue a new key for a gateway; the old key stops working immediately")
    public EdgeGatewayService.IssuedKey rotateGateway(@PathVariable String code, Authentication auth) {
        return gateways.rotate(code, auth.getName());
    }

    @PostMapping("/gateways/{code}/revoke")
    public EdgeGatewayService.EdgeGateway revokeGateway(@PathVariable String code, Authentication auth) {
        return gateways.revoke(code, auth.getName());
    }

    @GetMapping("/assignments")
    public List<MachineRepository.Assignment> assignments() {
        return machines.assignments();
    }

    @PostMapping("/machines/{code}/assignments")
    @ResponseStatus(HttpStatus.CREATED)
    @Operation(summary = "Give a technician access to a machine")
    public void assign(@PathVariable String code, @Valid @RequestBody Assignment request, Authentication auth) {
        machines.assignTechnician(code, request.username().strip(), auth.getName());
    }

    @DeleteMapping("/machines/{code}/assignments/{username}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void unassign(@PathVariable String code, @PathVariable String username, Authentication auth) {
        machines.unassignTechnician(code, username, auth.getName());
    }
}
