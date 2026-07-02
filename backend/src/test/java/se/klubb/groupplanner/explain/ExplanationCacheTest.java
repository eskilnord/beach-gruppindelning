package se.klubb.groupplanner.explain;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import se.klubb.groupplanner.explain.ExplanationDtos.PersonExplanationResponse;

/**
 * Pure unit test for {@link ExplanationCache}'s LRU-invalidation-by-key-shape design (task item 6:
 * "LRU cache invalidation"). No Spring context needed — this is a plain bounded map.
 */
class ExplanationCacheTest {

    private static PersonExplanationResponse dummy(String runId, int revision) {
        return new PersonExplanationResponse(
                runId, revision, revision, false, "p1", "Test Person", null, java.util.List.of(), java.util.List.of(),
                java.util.List.of(), java.util.List.of(), java.util.List.of(), null);
    }

    @Test
    void hitsForTheSameKeyMissesAfterARevisionBump() {
        ExplanationCache cache = new ExplanationCache();
        ExplanationCache.Key keyAtRevision0 = new ExplanationCache.Key("run1", 0, "p1");
        cache.put(keyAtRevision0, dummy("run1", 0));

        assertThat(cache.get(keyAtRevision0)).isNotNull();

        // A revision bump changes the key a caller looks up with (see ExplanationService
        // .loadContext: always uses the plan's CURRENT revision) - the old entry becomes
        // unreachable, which is exactly "invalidate on revision bump" as a structural property.
        ExplanationCache.Key keyAtRevision1 = new ExplanationCache.Key("run1", 1, "p1");
        assertThat(cache.get(keyAtRevision1)).isNull();

        cache.put(keyAtRevision1, dummy("run1", 1));
        assertThat(cache.get(keyAtRevision1)).isNotNull();
        // The stale entry is still technically present (not swept), but never looked up again.
        assertThat(cache.get(keyAtRevision0)).isNotNull();
    }

    @Test
    void isBoundedByAnLruEvictionPolicy() {
        ExplanationCache cache = new ExplanationCache();
        for (int i = 0; i < 600; i++) {
            cache.put(new ExplanationCache.Key("run1", 0, "p" + i), dummy("run1", 0));
        }
        assertThat(cache.size()).isLessThanOrEqualTo(500);
        // The most recently inserted entry must still be present (LRU keeps the newest).
        assertThat(cache.get(new ExplanationCache.Key("run1", 0, "p599"))).isNotNull();
        // An early entry, inserted well before the bound was reached, must have been evicted.
        assertThat(cache.get(new ExplanationCache.Key("run1", 0, "p0"))).isNull();
    }
}
