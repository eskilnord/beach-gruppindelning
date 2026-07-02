package se.klubb.groupplanner.importer;

import java.util.List;
import se.klubb.groupplanner.importer.match.PersonMatchProposal;

/** One data row's validation outcome (spec §8.6), plus any potential-duplicate proposals (§8.7). */
public record RowValidationResult(
        int rowIndex,
        RowStatus status,
        List<String> reasons,
        List<PersonMatchProposal> matchProposals) {
}
