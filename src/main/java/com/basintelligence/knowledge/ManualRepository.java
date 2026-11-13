package com.basintelligence.knowledge;

import static com.basintelligence.common.Text.instant;
import static com.basintelligence.common.Text.utc;

import java.time.Instant;
import java.util.List;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.stereotype.Repository;

import com.basintelligence.common.Text;
import com.basintelligence.machine.MachineType;

@Repository
public class ManualRepository {

    public record ManualDocument(long id, String title, String filename, String contentType, MachineType machineType,
            String sha256, String uploadedBy, Instant uploadedAt, int chunkCount) {
    }

    public record StoredChunk(long id, long documentId, String documentTitle, MachineType machineType, int chunkIndex,
            String section, String content, Integer pageStart, Integer pageEnd) {
    }

    private final JdbcClient jdbc;

    public ManualRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public boolean existsBySha(String sha256) {
        return jdbc.sql("SELECT COUNT(*) FROM documents WHERE sha256 = :sha").param("sha", sha256)
                .query(Long.class).single() > 0;
    }

    public long insertDocument(String title, String filename, String contentType, MachineType type, String sha,
            String by, Instant at) {
        var keys = new GeneratedKeyHolder();
        jdbc.sql("""
                INSERT INTO documents (title, filename, content_type, machine_type, sha256, uploaded_by, uploaded_at)
                VALUES (:title, :filename, :contentType, :type, :sha, :by, :at)""")
                .param("title", title)
                .param("filename", filename)
                .param("contentType", contentType)
                .param("type", type == null ? null : type.name())
                .param("sha", sha)
                .param("by", by)
                .param("at", utc(at))
                .update(keys, "id");
        return keys.getKey().longValue();
    }

    public void insertChunk(long documentId, TextChunker.Chunk chunk) {
        jdbc.sql("""
                INSERT INTO document_chunks (document_id, chunk_index, section, content, page_start, page_end)
                VALUES (:documentId, :index, :section, :content, :pageStart, :pageEnd)""")
                .param("pageStart", chunk.pageStart())
                .param("pageEnd", chunk.pageEnd())
                .param("documentId", documentId)
                .param("index", chunk.index())
                .param("section", Text.truncate(chunk.section(), 255))
                .param("content", chunk.content())
                .update();
    }

    public List<ManualDocument> findAllDocuments() {
        return jdbc.sql("""
                SELECT d.*, (SELECT COUNT(*) FROM document_chunks c WHERE c.document_id = d.id) AS chunk_count
                FROM documents d ORDER BY d.title""")
                .query((rs, n) -> new ManualDocument(rs.getLong("id"), rs.getString("title"), rs.getString("filename"),
                        rs.getString("content_type"),
                        rs.getString("machine_type") == null ? null : MachineType.valueOf(rs.getString("machine_type")),
                        rs.getString("sha256"), rs.getString("uploaded_by"), instant(rs, "uploaded_at"),
                        rs.getInt("chunk_count")))
                .list();
    }

    public List<StoredChunk> findAllChunks() {
        return jdbc.sql("""
                SELECT c.id, c.document_id, d.title, d.machine_type, c.chunk_index, c.section, c.content, c.page_start, c.page_end
                FROM document_chunks c JOIN documents d ON d.id = c.document_id
                ORDER BY c.document_id, c.chunk_index""")
                .query((rs, n) -> new StoredChunk(rs.getLong("id"), rs.getLong("document_id"), rs.getString("title"),
                        rs.getString("machine_type") == null ? null : MachineType.valueOf(rs.getString("machine_type")),
                        rs.getInt("chunk_index"), rs.getString("section"), rs.getString("content"),
                        rs.getObject("page_start", Integer.class), rs.getObject("page_end", Integer.class)))
                .list();
    }

    public java.util.Optional<ManualDocument> findDocument(long id) {
        return findAllDocuments().stream().filter(d -> d.id() == id).findFirst();
    }

    public List<StoredChunk> findChunks(long documentId) {
        return findAllChunks().stream().filter(c -> c.documentId() == documentId).toList();
    }

    /** An investigation that cited a section of a document as evidence. */
    public record Citation(long investigationId, String refLabel, long chunkId, String machineCode, Instant citedAt,
            String requestedBy) {
    }

    public List<Citation> findCitations(long documentId) {
        return jdbc.sql("""
                SELECT e.investigation_id, e.ref_label, e.document_chunk_id, d.code, i.created_at, i.requested_by
                FROM investigation_evidence e
                JOIN document_chunks c ON c.id = e.document_chunk_id
                JOIN investigations i ON i.id = e.investigation_id
                JOIN devices d ON d.id = i.device_id
                WHERE c.document_id = :doc ORDER BY i.created_at DESC, e.id""").param("doc", documentId)
                .query((rs, n) -> new Citation(rs.getLong(1), rs.getString(2), rs.getLong(3), rs.getString(4),
                        instant(rs, "created_at"), rs.getString(6)))
                .list();
    }

    public int deleteDocument(long id) {
        return jdbc.sql("DELETE FROM documents WHERE id = :id").param("id", id).update();
    }
}
