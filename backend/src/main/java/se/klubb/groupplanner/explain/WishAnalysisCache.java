package se.klubb.groupplanner.explain;

import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.stereotype.Component;
import se.klubb.groupplanner.explain.ExplanationDtos.WishAnalysisResponse;

/**
 * M-E3 in-memory LRU cache for the lazy {@code GET .../wish-analysis} drawer, mirroring {@link
 * ExplanationCache} exactly (same "key embeds the plan revision" invalidation strategy — see that
 * class's javadoc for the full reasoning) but keyed one level finer: {@code (planId, runId,
 * planRevision, participantProfileId, wishId)}, since two different wishes of the SAME player have
 * independent break-even/ordering computations. Ensures a repeated request for the same wish never
 * re-probes (see {@code WishAnalysisEndpointTest}'s cache-hit probe-count assertion).
 *
 * <p>{@code planId} (FIX 3, M-E3 review, MINOR) is part of the key even though {@code runId} is
 * already globally unique in practice — defense in depth against a hypothetical {@code runId} collision
 * across two different plans ever silently serving one plan's wish analysis under another's; {@link
 * ExplanationService#wishAnalysis} independently validates {@code planId}/{@code runId} BEFORE the
 * cache pre-check on every call, so an unknown/mismatched pair 404s honestly regardless of cache state.
 */
@Component
public class WishAnalysisCache {

    private static final int MAX_ENTRIES = 500;

    public record Key(String planId, String runId, int planRevision, String participantProfileId, String wishId) {
    }

    private final Map<Key, WishAnalysisResponse> cache = new LinkedHashMap<>(16, 0.75f, true) {
        @Override
        protected boolean removeEldestEntry(Map.Entry<Key, WishAnalysisResponse> eldest) {
            return size() > MAX_ENTRIES;
        }
    };

    public synchronized WishAnalysisResponse get(Key key) {
        return cache.get(key);
    }

    public synchronized void put(Key key, WishAnalysisResponse value) {
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
