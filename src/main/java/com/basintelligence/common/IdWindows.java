package com.basintelligence.common;

/**
 * Splits a large DELETE into statements over consecutive id ranges, so retention never holds many row locks or
 * writes one large burst of WAL. Called outside a transaction, each statement commits on its own. Id ranges are used
 * instead of {@code LIMIT} in a sub-select because they run the same way on PostgreSQL and H2 and use the primary key.
 */
public final class IdWindows {

    /** Ids per statement. */
    public static final long SIZE = 5_000;

    @FunctionalInterface
    public interface Delete {
        /** Deletes the qualifying rows with {@code from <= id <= to} and returns how many. */
        int rows(long from, long to);
    }

    private IdWindows() {
    }

    /** Runs {@code delete} over {@code [first, last]} in windows of {@link #SIZE} ids. Null bounds: nothing to do. */
    public static int delete(Long first, Long last, Delete delete) {
        if (first == null || last == null) {
            return 0;
        }
        int total = 0;
        for (long from = first; from <= last; from += SIZE) {
            total += delete.rows(from, Math.min(last, from + SIZE - 1));
        }
        return total;
    }
}
