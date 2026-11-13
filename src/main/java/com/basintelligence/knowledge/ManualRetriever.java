package com.basintelligence.knowledge;

import java.util.List;

import com.basintelligence.machine.MachineType;

/** Retrieval contract used by investigations and the manual search page. */
public interface ManualRetriever {

    /**
     * Top-k passages. When a machine type is given, results come from manuals for that type plus general
     * manuals that have no machine type.
     */
    List<ManualHit> search(String query, MachineType machineType, int k);

    /** Called after documents change. */
    void refresh();

    /** Human-readable name shown in the UI and evidence sources. */
    String name();

    int indexedChunks();
}
