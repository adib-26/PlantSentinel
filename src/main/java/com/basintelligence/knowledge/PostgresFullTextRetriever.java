package com.basintelligence.knowledge;

import java.util.List;
import java.util.stream.Collectors;

import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.simple.JdbcClient;

import com.basintelligence.machine.MachineType;

/**
 * PostgreSQL full-text retrieval: a weighted {@code tsvector} per chunk (section A, title B, body D) with a GIN
 * index, ranked by {@code ts_rank_cd}. Query terms are OR-ed so long symptom descriptions still match, and the
 * ranking rewards passages that cover more of them. The query is built only from [a-z0-9] tokens and passed as
 * a bind parameter.
 */
public class PostgresFullTextRetriever implements ManualRetriever {

    private static final RowMapper<ManualHit> MAPPER = (rs, n) -> new ManualHit(rs.getLong("id"), rs.getString("title"),
            rs.getString("section"), rs.getString("content"),
            rs.getString("machine_type") == null ? null : MachineType.valueOf(rs.getString("machine_type")),
            rs.getDouble("score"), rs.getObject("page_start", Integer.class), rs.getObject("page_end", Integer.class));

    private static final String SELECT = """
            SELECT c.id, d.title, c.section, c.content, c.page_start, c.page_end, d.machine_type, ts_rank_cd(c.search_vector, q.query, 1) AS score
            FROM document_chunks c
            JOIN documents d ON d.id = c.document_id
            CROSS JOIN (SELECT to_tsquery('english', :tsquery) AS query) q
            WHERE c.search_vector @@ q.query
            """;

    private final JdbcClient jdbc;

    public PostgresFullTextRetriever(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public List<ManualHit> search(String query, MachineType machineType, int k) {
        String tsquery = toTsQuery(query);
        if (tsquery.isEmpty()) {
            return List.of();
        }
        var spec = machineType == null
                ? jdbc.sql(SELECT + " ORDER BY score DESC, c.id LIMIT :k")
                : jdbc.sql(SELECT + " AND (d.machine_type IS NULL OR d.machine_type = :type) ORDER BY score DESC, c.id LIMIT :k")
                        .param("type", machineType.name());
        return spec.param("tsquery", tsquery).param("k", k).query(MAPPER).list();
    }

    @Override
    public void refresh() {
        jdbc.sql("""
                UPDATE document_chunks c
                SET search_vector = setweight(to_tsvector('english', coalesce(c.section, '')), 'A')
                                 || setweight(to_tsvector('english', d.title), 'B')
                                 || to_tsvector('english', c.content)
                FROM documents d
                WHERE d.id = c.document_id AND c.search_vector IS NULL""").update();
    }

    @Override
    public String name() {
        return "PostgreSQL full-text search";
    }

    @Override
    public int indexedChunks() {
        return jdbc.sql("SELECT COUNT(*) FROM document_chunks WHERE search_vector IS NOT NULL").query(Integer.class).single();
    }

    /** "Why is the bearing vibrating?" becomes "bearing | vibrating"; PostgreSQL then stems and drops stopwords. */
    static String toTsQuery(String text) {
        return Bm25Index.terms(text).stream().distinct().collect(Collectors.joining(" | "));
    }
}
