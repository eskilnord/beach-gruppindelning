package se.klubb.groupplanner.groups;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import se.klubb.groupplanner.groups.PreviousGroupMatcher.GroupNameAndOrder;
import se.klubb.groupplanner.groups.PreviousGroupMatcher.MatchKind;
import se.klubb.groupplanner.groups.PreviousGroupMatcher.MatchResult;

class PreviousGroupMatcherTest {

    private static PreviousGroupRef ref(String canonicalName, Integer groupOrder) {
        return new PreviousGroupRef("display", canonicalName, null, groupOrder, null, null);
    }

    @Test
    void canonicalNameHitBeatsAnUnrelatedOrderHitElsewhereInTheList() {
        // group 1 shares the ref's groupOrder (would be an ORDER hit on its own), but group 2
        // matches by canonicalName - the name check must win regardless of list position.
        PreviousGroupRef r = ref("torsdag herr 3", 1);
        List<GroupNameAndOrder> planGroups = List.of(
                new GroupNameAndOrder("Torsdag Herr 1", 1),
                new GroupNameAndOrder("Torsdag Herr 3", 5));

        Optional<MatchResult> result = PreviousGroupMatcher.match(r, planGroups);

        assertThat(result).isPresent();
        assertThat(result.get().kind()).isEqualTo(MatchKind.NAME);
        assertThat(result.get().group()).isEqualTo(new GroupNameAndOrder("Torsdag Herr 3", 5));
    }

    @Test
    void nameComparisonUsesTheSameCanonicalizationAsTheRef() {
        PreviousGroupRef r = ref("torsdag herr 3", null);
        List<GroupNameAndOrder> planGroups = List.of(new GroupNameAndOrder("Torsdag  Herr, 3", 3));

        Optional<MatchResult> result = PreviousGroupMatcher.match(r, planGroups);

        assertThat(result).isPresent();
        assertThat(result.get().kind()).isEqualTo(MatchKind.NAME);
    }

    @Test
    void orderOnlyHitWhenNoNameMatches() {
        PreviousGroupRef r = ref("okänd kategori", 2);
        List<GroupNameAndOrder> planGroups = List.of(
                new GroupNameAndOrder("Torsdag Herr 1", 1),
                new GroupNameAndOrder("Torsdag Herr 2", 2));

        Optional<MatchResult> result = PreviousGroupMatcher.match(r, planGroups);

        assertThat(result).isPresent();
        assertThat(result.get().kind()).isEqualTo(MatchKind.ORDER);
        assertThat(result.get().group()).isEqualTo(new GroupNameAndOrder("Torsdag Herr 2", 2));
    }

    @Test
    void missReturnsEmptyWhenNeitherNameNorOrderMatch() {
        PreviousGroupRef r = ref("okänd kategori", 99);
        List<GroupNameAndOrder> planGroups = List.of(new GroupNameAndOrder("Torsdag Herr 1", 1));

        assertThat(PreviousGroupMatcher.match(r, planGroups)).isEmpty();
    }

    @Test
    void nullGroupOrderNeverMatchesByOrder() {
        PreviousGroupRef r = ref("okänd kategori", null);
        List<GroupNameAndOrder> planGroups = List.of(new GroupNameAndOrder("Torsdag Herr 1", 1));

        assertThat(PreviousGroupMatcher.match(r, planGroups)).isEmpty();
    }

    @Test
    void nullRefOrNullPlanGroupsProducesEmpty() {
        assertThat(PreviousGroupMatcher.match(null, List.of())).isEmpty();
        assertThat(PreviousGroupMatcher.match(ref("x", 1), null)).isEmpty();
    }

    @Test
    void blankCanonicalNameNeverProducesANameMatch() {
        // "---" canonicalizes to "" (all punctuation), same as a plan group named "-" - the NAME
        // comparison must be skipped for blank canonical forms rather than treating "" == "" as a hit.
        PreviousGroupRef r = PreviousGroupNormalizer.parse("---");
        assertThat(r.canonicalName()).isEmpty();
        List<GroupNameAndOrder> planGroups = List.of(new GroupNameAndOrder("-", 7));

        assertThat(PreviousGroupMatcher.match(r, planGroups)).isEmpty();
    }

    @Test
    void blankCanonicalNameFallsThroughToOrderMatchInsteadOfSkippingEntirely() {
        // Isolates the blank-canonical NAME-skip from the "---" case above: with a non-null
        // groupOrder, a blank-canonical ref must still be able to ORDER-match - the fix only skips
        // the NAME comparison, it doesn't blanket-reject the whole ref.
        PreviousGroupRef r = ref("", 7);
        List<GroupNameAndOrder> planGroups = List.of(new GroupNameAndOrder("-", 7));

        Optional<MatchResult> result = PreviousGroupMatcher.match(r, planGroups);

        assertThat(result).isPresent();
        assertThat(result.get().kind()).isEqualTo(MatchKind.ORDER);
    }
}
