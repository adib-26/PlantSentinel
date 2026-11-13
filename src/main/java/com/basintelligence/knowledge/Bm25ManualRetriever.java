package com.basintelligence.knowledge;

import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import com.basintelligence.machine.MachineType;

/** In-memory BM25 over all chunks. Used with H2 (the offline demo), where PostgreSQL full-text search is unavailable. */
public class Bm25ManualRetriever implements ManualRetriever {

    private final ManualRepository repository;
    private final AtomicReference<Bm25Index> index = new AtomicReference<>(Bm25Index.empty());

    public Bm25ManualRetriever(ManualRepository repository) {
        this.repository = repository;
    }

    @Override
    public void refresh() {
        List<Bm25Index.Document> docs = repository.findAllChunks().stream()
                .map(c -> new Bm25Index.Document(c.id(),
                        // Title and section are indexed with the body so headings like "Bearing failure" count.
                        c.documentTitle() + "\n" + (c.section() == null ? "" : c.section() + "\n") + c.content(),
                        Map.of("title", c.documentTitle(),
                                "section", c.section() == null ? "" : c.section(),
                                "content", c.content(),
                                "machineType", c.machineType() == null ? "" : c.machineType().name(),
                                "pageStart", c.pageStart() == null ? "" : c.pageStart().toString(),
                                "pageEnd", c.pageEnd() == null ? "" : c.pageEnd().toString())))
                .toList();
        index.set(new Bm25Index(docs));
    }

    @Override
    public List<ManualHit> search(String query, MachineType machineType, int k) {
        return index.get().search(query, k, doc -> {
            String type = doc.attributes().get("machineType");
            return machineType == null || type.isEmpty() || type.equals(machineType.name());
        }).stream().map(hit -> {
            Map<String, String> a = hit.document().attributes();
            return new ManualHit(hit.document().chunkId(), a.get("title"),
                    a.get("section").isEmpty() ? null : a.get("section"), a.get("content"),
                    a.get("machineType").isEmpty() ? null : MachineType.valueOf(a.get("machineType")), hit.score(),
                    a.get("pageStart").isEmpty() ? null : Integer.valueOf(a.get("pageStart")),
                    a.get("pageEnd").isEmpty() ? null : Integer.valueOf(a.get("pageEnd")));
        }).toList();
    }

    @Override
    public String name() {
        return "BM25 (in-memory)";
    }

    @Override
    public int indexedChunks() {
        return index.get().size();
    }
}
