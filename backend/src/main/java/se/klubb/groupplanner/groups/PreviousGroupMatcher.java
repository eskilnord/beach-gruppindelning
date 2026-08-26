package se.klubb.groupplanner.groups;

import java.util.List;
import java.util.Optional;

/**
 * Matches a {@link PreviousGroupRef} against a plan's generated groups. Pure — deliberately takes
 * plain {@code (name, groupOrder)} pairs rather than any repo/domain {@code TrainingGroup} type, so
 * this module stays independent of persistence and can be wired in from wherever such a pair is
 * available (e.g. {@code "<category> N"} / {@code groupOrder = N} groups from {@code
 * solver/assemble/GroupGenerator}).
 */
public final class PreviousGroupMatcher {

    private PreviousGroupMatcher() {
    }

    /**
     * @param name the group's display name, e.g. {@code "Torsdag Herr 3"}.
     * @param groupOrder the group's {@code groupOrder}.
     */
    public record GroupNameAndOrder(String name, int groupOrder) {
    }

    public record MatchResult(GroupNameAndOrder group, MatchKind kind) {
    }

    public enum MatchKind {
        /** The ref's canonical name matched a plan group's (equally canonicalized) name. */
        NAME,
        /** No name match; the ref's numeric groupOrder matched a plan group's groupOrder. */
        ORDER
    }

    /**
     * Match order: (1) exact {@code canonicalName} equality against the same canonicalization
     * applied to each candidate's {@code name} — skipped entirely when either side's canonical form
     * is blank, so e.g. a ref parsed from {@code "---"} (canonicalName {@code ""}) never NAME-matches
     * a group whose name also canonicalizes to empty (e.g. {@code "-"}); (2) else {@code
     * ref.groupOrder() != null} and equal to a candidate's {@code groupOrder}; (3) else empty.
     */
    public static Optional<MatchResult> match(PreviousGroupRef ref, List<GroupNameAndOrder> planGroups) {
        if (ref == null || planGroups == null) {
            return Optional.empty();
        }

        boolean refCanonicalIsBlank = ref.canonicalName() == null || ref.canonicalName().isBlank();
        if (!refCanonicalIsBlank) {
            for (GroupNameAndOrder group : planGroups) {
                if (group != null && group.name() != null) {
                    String candidateCanonical = PreviousGroupNormalizer.canonicalizeText(group.name());
                    if (!candidateCanonical.isBlank() && candidateCanonical.equals(ref.canonicalName())) {
                        return Optional.of(new MatchResult(group, MatchKind.NAME));
                    }
                }
            }
        }

        if (ref.groupOrder() != null) {
            for (GroupNameAndOrder group : planGroups) {
                if (group != null && ref.groupOrder().equals(group.groupOrder())) {
                    return Optional.of(new MatchResult(group, MatchKind.ORDER));
                }
            }
        }

        return Optional.empty();
    }
}
