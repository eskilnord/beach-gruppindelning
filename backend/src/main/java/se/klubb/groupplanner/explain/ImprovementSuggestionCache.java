package se.klubb.groupplanner.explain;

import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.stereotype.Component;
import se.klubb.groupplanner.explain.ExplanationDtos.ImprovementSuggestionsResponse;

/**
 * In-memory LRU cache for {@code GET .../suggestions} responses, keyed {@code (runId,
 * planRevision)} — mirrors {@link ExplanationCache}'s own "invalidate on revision bump" pattern
 * (WI-D, docs/design/04-solver.md §11.4's cache decision, generalized here to a whole-plan
 * suggestion list rather than one player's explanation). {@link ExplanationCache} itself is NOT
 * reused directly: its key additionally carries a {@code participantProfileId} and its value type is
 * pinned to {@link ExplanationDtos.PersonExplanationResponse} — a per-plan suggestion list has
 * neither, so a parallel, equally small cache class is the lower-risk choice over widening an
 * already-tested class's shape.
 *
 * <p>Invalidation strategy is identical to {@link ExplanationCache}: the key embeds the plan
 * revision the suggestions were computed AT, so a revision bump makes every future lookup for that
 * plan a structural cache miss (never an explicit sweep/invalidate call) — see {@link
 * ImprovementSuggestionService#suggestions} for the read side.
 */
@Component
public class ImprovementSuggestionCache {

    private static final int MAX_ENTRIES = 200;

    public record Key(String runId, int planRevision) {
    }

    private final Map<Key, ImprovementSuggestionsResponse> cache = new LinkedHashMap<>(16, 0.75f, true) {
        @Override
        protected boolean removeEldestEntry(Map.Entry<Key, ImprovementSuggestionsResponse> eldest) {
            return size() > MAX_ENTRIES;
        }
    };

    public synchronized ImprovementSuggestionsResponse get(Key key) {
        return cache.get(key);
    }

    public synchronized void put(Key key, ImprovementSuggestionsResponse value) {
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
