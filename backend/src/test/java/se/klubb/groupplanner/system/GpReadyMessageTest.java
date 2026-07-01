package se.klubb.groupplanner.system;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/**
 * Unit tests for the exact GP_READY line format the desktop shell parses
 * (docs/design/01-architecture.md §4, step 3): compact JSON, no spaces.
 */
class GpReadyMessageTest {

    @Test
    void jsonIsCompactWithNoSpaces() {
        String json = GpReadyMessage.json(54321, 4242, 0);

        assertThat(json).doesNotContain(" ");
        assertThat(json).isEqualTo("{\"port\":54321,\"pid\":4242,\"schemaVersion\":0}");
    }

    @Test
    void buildPrependsExactPrefix() {
        String line = GpReadyMessage.build(1234, 999, 0);

        assertThat(line).isEqualTo("GP_READY {\"port\":1234,\"pid\":999,\"schemaVersion\":0}");
        assertThat(line).startsWith("GP_READY {");
        assertThat(line).doesNotContain("GP_READY  ");
    }

    @Test
    void handlesLargePidValues() {
        long largePid = 4_000_000_000L;

        String json = GpReadyMessage.json(0, largePid, 0);

        assertThat(json).isEqualTo("{\"port\":0,\"pid\":4000000000,\"schemaVersion\":0}");
    }
}
