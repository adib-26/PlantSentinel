package com.basintelligence.factory;

import static com.basintelligence.common.Text.instant;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.basintelligence.common.AuditService;
import com.basintelligence.common.InvalidStateException;
import com.basintelligence.common.NotFoundException;

/** Factories own devices. A default factory is created on first start so single-site use needs no setup. */
@Service
public class FactoryService {

    public static final String DEFAULT_CODE = "PLANT-01";

    private static final RowMapper<Factory> MAPPER = (rs, n) -> new Factory(rs.getObject("id", UUID.class),
            rs.getString("code"), rs.getString("name"), rs.getString("location"), instant(rs, "created_at"));

    private final JdbcClient jdbc;
    private final AuditService audit;

    public FactoryService(JdbcClient jdbc, AuditService audit) {
        this.jdbc = jdbc;
        this.audit = audit;
    }

    @Transactional
    public Factory ensureDefault() {
        return find(DEFAULT_CODE).orElseGet(() -> {
            jdbc.sql("INSERT INTO factories (id, code, name, location) VALUES (:id, :code, :name, :location)")
                    .param("id", UUID.randomUUID())
                    .param("code", DEFAULT_CODE)
                    .param("name", "Demo Manufacturing Plant")
                    .param("location", "Shah Alam, Selangor")
                    .update();
            return find(DEFAULT_CODE).orElseThrow();
        });
    }

    public Factory defaultFactory() {
        return find(DEFAULT_CODE).orElseGet(this::ensureDefault);
    }

    public Factory getByCode(String code) {
        return find(code).orElseThrow(() -> new NotFoundException("Factory not found: " + code));
    }

    public Factory get(UUID id) {
        return jdbc.sql("SELECT * FROM factories WHERE id = :id").param("id", id).query(MAPPER).optional()
                .orElseThrow(() -> new NotFoundException("Factory not found: " + id));
    }

    /** Creates a factory (administrators). Codes follow the machine-code style, e.g. PLANT-02. */
    @Transactional
    public Factory create(String code, String name, String location, String actor) {
        if (find(code).isPresent()) {
            throw new InvalidStateException("Factory code already exists: " + code);
        }
        jdbc.sql("INSERT INTO factories (id, code, name, location) VALUES (:id, :code, :name, :location)")
                .param("id", UUID.randomUUID())
                .param("code", code)
                .param("name", name.strip())
                .param("location", location == null || location.isBlank() ? null : location.strip())
                .update();
        audit.record(actor, "FACTORY_CREATED", "factory:" + code, name.strip());
        return getByCode(code);
    }

    public List<Factory> list() {
        return jdbc.sql("SELECT * FROM factories ORDER BY code").query(MAPPER).list();
    }

    private Optional<Factory> find(String code) {
        return jdbc.sql("SELECT * FROM factories WHERE code = :code").param("code", code).query(MAPPER).optional();
    }
}
