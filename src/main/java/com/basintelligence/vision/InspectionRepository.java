package com.basintelligence.vision;

import static com.basintelligence.common.Text.instant;
import static com.basintelligence.common.Text.utc;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.stereotype.Repository;

@Repository
public class InspectionRepository {

    private static final RowMapper<Inspection> MAPPER = (rs, n) -> new Inspection(
            rs.getLong("id"),
            rs.getObject("device_id", java.util.UUID.class),
            instant(rs, "inspected_at"),
            rs.getString("component"),
            rs.getString("original_filename"),
            rs.getString("image_path"),
            rs.getString("annotated_path"),
            rs.getDouble("defect_probability"),
            Inspection.Verdict.valueOf(rs.getString("verdict")),
            rs.getInt("defect_regions"),
            rs.getString("model_version"),
            rs.getLong("inference_ms"),
            rs.getString("inspected_by"));

    private final JdbcClient jdbc;

    public InspectionRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public long insert(Inspection i) {
        var keys = new GeneratedKeyHolder();
        jdbc.sql("""
                INSERT INTO inspections (device_id, inspected_at, component, original_filename, image_path, annotated_path,
                                        defect_probability, verdict, defect_regions, model_version, inference_ms, inspected_by)
                VALUES (:deviceId, :at, :component, :filename, :image, :annotated,
                        :probability, :verdict, :regions, :model, :ms, :by)""")
                .param("deviceId", i.machineId())
                .param("at", utc(i.inspectedAt()))
                .param("component", i.component())
                .param("filename", i.originalFilename())
                .param("image", i.imagePath())
                .param("annotated", i.annotatedPath())
                .param("probability", i.defectProbability())
                .param("verdict", i.verdict().name())
                .param("regions", i.defectRegions())
                .param("model", i.modelVersion())
                .param("ms", i.inferenceMs())
                .param("by", i.inspectedBy())
                .update(keys, "id");
        return keys.getKey().longValue();
    }

    public Optional<Inspection> findById(long id) {
        return jdbc.sql("SELECT * FROM inspections WHERE id = :id").param("id", id).query(MAPPER).optional();
    }

    /** Newest first. */
    public List<Inspection> findByMachineSince(java.util.UUID machineId, Instant since, int limit) {
        return jdbc.sql("""
                SELECT * FROM inspections WHERE device_id = :machineId AND inspected_at >= :since
                ORDER BY inspected_at DESC LIMIT :limit""")
                .param("machineId", machineId)
                .param("since", utc(since))
                .param("limit", limit)
                .query(MAPPER).list();
    }
}
