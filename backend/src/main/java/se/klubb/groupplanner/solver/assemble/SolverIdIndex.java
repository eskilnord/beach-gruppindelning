package se.klubb.groupplanner.solver.assemble;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Deterministic {@code long <-> String} id mapping for one entity kind within one assembled {@code
 * GroupPlanSolution} (docs/design/04-solver.md §1.2 assumed numeric DB ids; the actual schema uses
 * TEXT UUIDv7 primary keys — CLAUDE.md "SQLite rules" / ADR-004 — so every solver-domain {@code
 * long}/{@code Long} id in this codebase is instead a position in a list the caller has already
 * sorted by a stable key, per {@code SolverInputAssembler}).
 *
 * <p>This preserves the design's own determinism invariant ("stable IDs come from the DB, never
 * from row order or iteration order", §9.3): the assignment is a pure function of the DB's stable
 * UUIDv7 values (lexicographic sort, which for UUIDv7 also happens to be creation-time order), not
 * of retrieval/iteration order, so the same DB content always produces the same {@code long} ids,
 * reproducibly across machines and JVM runs.
 */
final class SolverIdIndex {

    private final Map<String, Long> toLong = new HashMap<>();
    private final Map<Long, String> toUuid = new HashMap<>();

    private SolverIdIndex() {
    }

    /** Builds an index from a list of DB ids the caller has ALREADY sorted deterministically. */
    static SolverIdIndex of(List<String> sortedIds) {
        SolverIdIndex index = new SolverIdIndex();
        long next = 1L;
        for (String id : sortedIds) {
            if (index.toLong.containsKey(id)) {
                continue; // defensive: tolerate accidental duplicates in the input list.
            }
            index.toLong.put(id, next);
            index.toUuid.put(next, id);
            next++;
        }
        return index;
    }

    long id(String uuid) {
        Long value = toLong.get(uuid);
        if (value == null) {
            throw new IllegalStateException("No solver id assigned for db id: " + uuid);
        }
        return value;
    }

    String uuid(long id) {
        String value = toUuid.get(id);
        if (value == null) {
            throw new IllegalStateException("No db id known for solver id: " + id);
        }
        return value;
    }

    boolean contains(String uuid) {
        return toLong.containsKey(uuid);
    }

    int size() {
        return toLong.size();
    }
}
