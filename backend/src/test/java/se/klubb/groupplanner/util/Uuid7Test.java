package se.klubb.groupplanner.util;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;

/**
 * Verifies {@link Uuid7} produces valid, time-ordered UUIDv7 values (RFC 9562 §5.7) — see
 * docs/plan.md M1 row ("Uuid7 ordering test").
 */
class Uuid7Test {

    private static final Pattern UUID_PATTERN =
            Pattern.compile("^[0-9a-f]{8}-[0-9a-f]{4}-7[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$");

    @Test
    void generatesWellFormedVersion7Uuid() {
        String id = Uuid7.generate();

        assertThat(id).matches(UUID_PATTERN);
        UUID parsed = UUID.fromString(id);
        assertThat(parsed.version()).isEqualTo(7);
        assertThat(parsed.variant()).isEqualTo(2); // RFC 4122/9562 variant "10"
    }

    @Test
    void idsGeneratedInATightLoopAreMonotonicallyIncreasing() {
        List<String> ids = new ArrayList<>();
        for (int i = 0; i < 10_000; i++) {
            ids.add(Uuid7.generate());
        }

        for (int i = 1; i < ids.size(); i++) {
            String previous = ids.get(i - 1);
            String current = ids.get(i);
            assertThat(current)
                    .as("id #%d (%s) should sort after id #%d (%s)", i, current, i - 1, previous)
                    .isGreaterThan(previous);
        }
    }

    @Test
    void uuidCompareToOrderingMatchesStringOrdering() {
        UUID a = Uuid7.generateUuid();
        UUID b = Uuid7.generateUuid();

        assertThat(a.compareTo(b)).isLessThanOrEqualTo(0);
        assertThat(a.toString()).isLessThanOrEqualTo(b.toString());
    }

    @Test
    void allIdsAreUnique() {
        List<String> ids = new ArrayList<>();
        for (int i = 0; i < 5_000; i++) {
            ids.add(Uuid7.generate());
        }

        assertThat(ids).doesNotHaveDuplicates();
    }
}
