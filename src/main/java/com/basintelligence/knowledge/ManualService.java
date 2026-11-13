package com.basintelligence.knowledge;

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.util.HexFormat;
import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.basintelligence.common.AuditService;
import com.basintelligence.common.DatabaseInfo;
import com.basintelligence.common.InvalidStateException;
import com.basintelligence.common.Text;
import com.basintelligence.machine.MachineType;

/**
 * Manual ingestion (Tika, chunking, database) and retrieval. On PostgreSQL (Neon) retrieval is SQL full-text
 * search; on H2 (offline demo) an in-memory BM25 index stands in.
 */
@Service
public class ManualService {

    private static final Logger log = LoggerFactory.getLogger(ManualService.class);
    private static final int CHUNK_CHARS = 900;

    private final ManualRepository repository;
    private final DocumentTextExtractor extractor;
    private final AuditService audit;
    private final Clock clock;
    private final ManualRetriever retriever;
    private final TextChunker chunker = new TextChunker(CHUNK_CHARS);

    private final io.micrometer.core.instrument.MeterRegistry meters;

    public ManualService(ManualRepository repository, DocumentTextExtractor extractor, AuditService audit, Clock clock,
            DatabaseInfo database, JdbcClient jdbc, io.micrometer.core.instrument.MeterRegistry meters) {
        this.meters = meters;
        this.repository = repository;
        this.extractor = extractor;
        this.audit = audit;
        this.clock = clock;
        this.retriever = database.isPostgres() ? new PostgresFullTextRetriever(jdbc) : new Bm25ManualRetriever(repository);
        log.info("Manual retrieval: {}", retriever.name());
    }

    @Transactional
    public ManualRepository.ManualDocument ingest(String title, String filename, byte[] bytes, MachineType machineType,
            String actor) {
        String safeFilename = ManualFilePolicy.checkUpload(filename, bytes);
        String sha = sha256(bytes);
        if (repository.existsBySha(sha)) {
            throw new InvalidStateException("This document has already been uploaded");
        }
        DocumentTextExtractor.Extracted extracted = extractor.extract(bytes, safeFilename);
        ManualFilePolicy.checkDetectedType(extracted.contentType());
        List<TextChunker.Chunk> chunks = chunker.chunk(extracted.text());
        String cleanTitle = title == null || title.isBlank() ? safeFilename : title.strip();
        long id = repository.insertDocument(Text.truncate(cleanTitle, 255), safeFilename,
                Text.truncate(extracted.contentType(), 120), machineType, sha, actor, clock.instant());
        chunks.forEach(c -> repository.insertChunk(id, c));
        audit.record(actor, "MANUAL_UPLOADED", "document:" + id, cleanTitle + " (" + chunks.size() + " chunks)");
        log.info("Ingested manual '{}' into {} chunks", cleanTitle, chunks.size());
        retriever.refresh();
        libraryVersion.incrementAndGet();
        return repository.findAllDocuments().stream().filter(d -> d.id() == id).findFirst().orElseThrow();
    }

    public boolean alreadyIngested(byte[] bytes) {
        return repository.existsBySha(sha256(bytes));
    }

    @Transactional
    public void delete(long id, String actor) {
        repository.deleteDocument(id);
        audit.record(actor, "MANUAL_DELETED", "document:" + id, null);
        retriever.refresh();
        libraryVersion.incrementAndGet();
    }

    public void rebuildIndex() {
        retriever.refresh();
        libraryVersion.incrementAndGet();
    }

    public List<ManualHit> search(String query, MachineType machineType, int k) {
        long started = System.nanoTime();
        List<ManualHit> hits = retriever.search(query, machineType, k);
        String name = retriever.name();
        meters.timer(com.basintelligence.common.BasMetrics.RAG_SEARCH_DURATION, "retriever", name)
                .record(System.nanoTime() - started, java.util.concurrent.TimeUnit.NANOSECONDS);
        meters.counter(com.basintelligence.common.BasMetrics.RAG_SEARCHES, "retriever", name, "outcome",
                hits.isEmpty() ? "empty" : "hit").increment();
        if (!hits.isEmpty()) {
            meters.summary(com.basintelligence.common.BasMetrics.RAG_TOP_SCORE, "retriever", name).record(hits.getFirst().score());
        }
        return hits;
    }

    /** Search without recording usage metrics, for the retrieval benchmark. */
    List<ManualHit> searchUnmetered(String query, MachineType machineType, int k) {
        return retriever.search(query, machineType, k);
    }

    /** Increases whenever the library or its index changes; lets cached evaluations notice. */
    public long libraryVersion() {
        return libraryVersion.get();
    }

    private final java.util.concurrent.atomic.AtomicLong libraryVersion = new java.util.concurrent.atomic.AtomicLong();

    public ManualRepository.ManualDocument document(long id) {
        return repository.findDocument(id)
                .orElseThrow(() -> new com.basintelligence.common.NotFoundException("Document not found: " + id));
    }

    public List<ManualRepository.StoredChunk> sections(long documentId) {
        return repository.findChunks(documentId);
    }

    public List<ManualRepository.Citation> citations(long documentId) {
        return repository.findCitations(documentId);
    }

    /** chunk id → document id, for linking search results to the document page. */
    public java.util.Map<Long, Long> chunkDocumentIds() {
        java.util.Map<Long, Long> map = new java.util.HashMap<>();
        repository.findAllChunks().forEach(c -> map.put(c.id(), c.documentId()));
        return map;
    }

    /** document id → number of investigation evidence items that cite it. */
    public java.util.Map<Long, Integer> citationCounts() {
        java.util.Map<Long, Integer> map = new java.util.HashMap<>();
        for (var d : repository.findAllDocuments()) {
            map.put(d.id(), repository.findCitations(d.id()).size());
        }
        return map;
    }

    public List<ManualRepository.ManualDocument> documents() {
        return repository.findAllDocuments();
    }

    public int indexedChunks() {
        return retriever.indexedChunks();
    }

    public String retrieverName() {
        return retriever.name();
    }

    static String sha256(byte[] bytes) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
