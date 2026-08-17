package se.klubb.groupplanner.suggest;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.Test;
import se.klubb.groupplanner.suggest.RosterNameResolver.Resolution;
import se.klubb.groupplanner.suggest.RosterNameResolver.RosterEntry;

class RosterNameResolverTest {

    private static RosterEntry entry(String id, String first, String last) {
        return new RosterEntry(id, null, first, last);
    }

    @Test
    void uniqueFullNameResolvesHigh() {
        List<RosterEntry> roster = List.of(entry("1", "Anna", "Svensson"), entry("2", "Bertil", "Karlsson"));
        Resolution r = RosterNameResolver.resolve("Anna Svensson.", roster);
        assertThat(r).isNotNull();
        assertThat(r.confidence()).isEqualTo(CommentSuggestion.Confidence.HIGH);
        assertThat(r.candidates()).hasSize(1);
        assertThat(r.candidates().get(0).id()).isEqualTo("1");
    }

    @Test
    void uniqueFirstNameResolvesHigh() {
        List<RosterEntry> roster = List.of(entry("1", "Anna", "Svensson"), entry("2", "Bertil", "Karlsson"));
        Resolution r = RosterNameResolver.resolve("Anna vill gärna.", roster);
        assertThat(r).isNotNull();
        assertThat(r.confidence()).isEqualTo(CommentSuggestion.Confidence.HIGH);
        assertThat(r.candidates().get(0).id()).isEqualTo("1");
    }

    @Test
    void twoAnnasProduceUncertainWithTwoCandidates() {
        List<RosterEntry> roster = List.of(
                entry("1", "Anna", "Svensson"), entry("2", "Anna", "Karlsson"), entry("3", "Bertil", "Nilsson"));
        Resolution r = RosterNameResolver.resolve("Anna vill gärna.", roster);
        assertThat(r).isNotNull();
        assertThat(r.confidence()).isEqualTo(CommentSuggestion.Confidence.UNCERTAIN);
        assertThat(r.candidates()).hasSize(2);
        assertThat(r.candidates()).extracting(CommentSuggestion.TargetCandidate::id).containsExactlyInAnyOrder("1", "2");
    }

    @Test
    void fuzzyMisspellingStillResolves() {
        List<RosterEntry> roster = List.of(entry("1", "Wilma", "Bäckman"), entry("2", "Erik", "Karlsson"));
        // Full two-token misspelling ("Beckman" for "Bäckman") - the full-name path's 0.85 threshold
        // absorbs the single-character diacritic difference.
        Resolution full = RosterNameResolver.resolve("Wilma Beckman vill spela.", roster);
        assertThat(full).isNotNull();
        assertThat(full.candidates().get(0).id()).isEqualTo("1");
    }

    @Test
    void moreThanThreeFirstNameMatchesAreDropped() {
        List<RosterEntry> roster = List.of(
                entry("1", "Anna", "A"), entry("2", "Anna", "B"), entry("3", "Anna", "C"), entry("4", "Anna", "D"));
        Resolution r = RosterNameResolver.resolve("Anna vill gärna.", roster);
        assertThat(r).isNull();
    }

    @Test
    void zeroMatchesAreDropped() {
        List<RosterEntry> roster = List.of(entry("1", "Bertil", "Nilsson"));
        Resolution r = RosterNameResolver.resolve("Anna vill gärna.", roster);
        assertThat(r).isNull();
    }

    @Test
    void noCapitalizedCandidateInWindowIsDropped() {
        List<RosterEntry> roster = List.of(entry("1", "Anna", "Svensson"));
        Resolution r = RosterNameResolver.resolve("henne gärna med", roster);
        assertThat(r).isNull();
    }

    @Test
    void selfExclusionIsCallerResponsibilityRosterOmittingSelfNeverMatchesSelf() {
        // The resolver itself has no notion of "self" - RosterNameResolverTest documents the
        // contract that CommentSuggestionService filters the roster before calling in here.
        List<RosterEntry> roster = List.of(entry("2", "Bertil", "Karlsson"));
        Resolution r = RosterNameResolver.resolve("Anna Svensson.", roster);
        assertThat(r).isNull();
    }

    @Test
    void coachScopingIsAlsoCallerResponsibilityViaWhichRosterListIsPassed() {
        List<RosterEntry> coachRoster = List.of(entry("c1", "Vera", "Nilsson"));
        Resolution r = RosterNameResolver.resolve("Vera Nilsson som tränare.", coachRoster);
        assertThat(r).isNotNull();
        assertThat(r.candidates().get(0).id()).isEqualTo("c1");
    }
}
