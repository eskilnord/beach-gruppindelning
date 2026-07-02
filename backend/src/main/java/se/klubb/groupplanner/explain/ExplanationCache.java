package se.klubb.groupplanner.explain;

import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.stereotype.Component;
import se.klubb.groupplanner.explain.ExplanationDtos.PersonExplanationResponse;

/**
 * In-memory LRU cache for per-player explanations, keyed {@code (runId, planRevision,
 * participantProfileId)} — docs/design/04-solver.md §11.4's "lazy-computation-with-cache" decision
 * and this milestone's task item 4 ("Cache per (runId, planRevision, participantId) in-memory LRU;
 * invalidate on revision bump").
 *
 * <p><b>Invalidation strategy:</b> the cache key embeds the plan revision the explanation was computed
 * AT, not just the runId. {@code ExplanationService} always looks up with the plan's CURRENT revision,
 * so the moment {@code ActivityPlanRepository#bumpRevision} fires, every future lookup for that plan
 * misses (a different, never-before-seen key) — old entries are never explicitly deleted, they simply
 * become unreachable and eventually fall out via the bounded LRU eviction below. This makes
 * "invalidate on revision bump" a structural property of the key shape rather than a sweep/scan, and
 * is directly testable: compute at revision N (cached), bump to N+1, compute again -&gt; cache miss.
 *
 * <p>Bounded ({@link #MAX_ENTRIES}) so a long desktop session (many plans, many re-solves) can't grow
 * this unboundedly — a plain {@link LinkedHashMap} in access-order mode with {@code
 * removeEldestEntry} overridden is the standard bounded-LRU idiom; synchronized for the (rare, single-
 * desktop-user) concurrent-request case.
 */
@Component
public class ExplanationCache {

    private static final int MAX_ENTRIES = 500;

    public record Key(String runId, int planRevision, String participantProfileId) {
    }

    private final Map<Key, PersonExplanationResponse> cache = new LinkedHashMap<>(16, 0.75f, true) {
        @Override
        protected boolean removeEldestEntry(Map.Entry<Key, PersonExplanationResponse> eldest) {
            return size() > MAX_ENTRIES;
        }
    };

    public synchronized PersonExplanationResponse get(Key key) {
        return cache.get(key);
    }

    public synchronized void put(Key key, PersonExplanationResponse value) {
        cache.put(key, value);
    }

    public synchronized int size() {
        return cache.size();
    }

    /** Test-only escape hatch — production code relies purely on key-shape invalidation. */
    public synchronized void clear() {
        cache.clear();
    }
}
