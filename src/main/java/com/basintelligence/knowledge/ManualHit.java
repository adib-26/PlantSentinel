package com.basintelligence.knowledge;

import com.basintelligence.machine.MachineType;

/** One retrieved manual passage. {@code score} is BM25 (H2) or ts_rank_cd (PostgreSQL). */
public record ManualHit(long chunkId, String documentTitle, String section, String content, MachineType machineType,
        double score, Integer pageStart, Integer pageEnd) {

    public ManualHit(long chunkId, String documentTitle, String section, String content, MachineType machineType,
            double score) {
        this(chunkId, documentTitle, section, content, machineType, score, null, null);
    }

    /** "Document › Section, p. 4" (or "pp. 4–5"); section and pages are included when known. */
    public String citation() {
        return citation(documentTitle, section, pageStart, pageEnd);
    }

    public static String citation(String title, String section, Integer pageStart, Integer pageEnd) {
        String c = section == null ? title : title + " › " + section;
        if (pageStart != null) {
            c += pageEnd == null || pageEnd.equals(pageStart) ? ", p. " + pageStart : ", pp. " + pageStart + "–" + pageEnd;
        }
        return c;
    }
}
