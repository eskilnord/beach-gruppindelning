package se.klubb.groupplanner.importer.match;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import se.klubb.groupplanner.domain.Person;
import se.klubb.groupplanner.importer.ExtractedRow;
import se.klubb.groupplanner.importer.parse.ParsedCell;

/**
 * Unit tests for §8.7 person matching: external id exact (M3 review finding 1), email exact,
 * phone exact (incl. +46/0 canonicalization, finding 2), exact name, similar name.
 */
class PersonMatcherTest {

    private static Person person(String id, String firstName, String lastName, String email, String phone) {
        return person(id, firstName, lastName, email, phone, null);
    }

    private static Person person(
            String id, String firstName, String lastName, String email, String phone, String externalId) {
        Instant now = Instant.now();
        return new Person(id, firstName, lastName, null, email, phone, externalId, true, false, null, now, now);
    }

    private static ExtractedRow row(String firstName, String lastName, String email, String phone) {
        return row(firstName, lastName, email, phone, null);
    }

    private static ExtractedRow row(String firstName, String lastName, String email, String phone, String externalId) {
        return new ExtractedRow(1, firstName, lastName, null, email, phone, externalId,
                (ParsedCell) null, null, null, null, null, null, null, false, Map.of(), Map.of());
    }

    @Test
    void exactExternalIdMatchIsTheStrongestBasis() {
        // Same member id but a changed name AND changed email (married name + new address) - the
        // external id is the source system's own identity and must still match, at top confidence.
        Person existing = person("p0", "Karin", "Hansson", "karin.hansson@example.se", null, "m-1017");
        ExtractedRow imported = row("Karin", "Bergström", "karin.bergstrom@example.se", null, "m-1017");

        List<PersonMatchProposal> matches = PersonMatcher.matchExisting(imported, List.of(existing));

        assertThat(matches).hasSize(1);
        assertThat(matches.get(0).existingPersonId()).isEqualTo("p0");
        assertThat(matches.get(0).matchBasis()).isEqualTo(PersonMatchProposal.MatchBasis.EXTERNAL_ID_EXACT);
        assertThat(matches.get(0).confidence()).isEqualTo(1.0);
    }

    @Test
    void differentExternalIdsDoNotMatchOnIdAlone() {
        Person existing = person("p0", "Karin", "Hansson", null, null, "m-1017");
        ExtractedRow imported = row("Ulla", "Nilsson", null, null, "m-2044");

        assertThat(PersonMatcher.matchExisting(imported, List.of(existing))).isEmpty();
    }

    @Test
    void exactEmailMatchWinsWithFullConfidence() {
        Person existing = person("p1", "Nils", "Åström", "nils.astrom@example.se", null);
        ExtractedRow imported = row("Nisse", "A", "NILS.ASTROM@example.se", null); // Different name, same email.

        List<PersonMatchProposal> matches = PersonMatcher.matchExisting(imported, List.of(existing));

        assertThat(matches).hasSize(1);
        assertThat(matches.get(0).existingPersonId()).isEqualTo("p1");
        assertThat(matches.get(0).matchBasis()).isEqualTo(PersonMatchProposal.MatchBasis.EMAIL_EXACT);
        assertThat(matches.get(0).confidence()).isEqualTo(1.0);
    }

    @Test
    void exactPhoneMatchWhenNoEmailMatch() {
        Person existing = person("p2", "Karin", "Söderberg", "karin@example.se", "070-1234567");
        ExtractedRow imported = row("Karin", "Söderberg", null, "0701234567");

        List<PersonMatchProposal> matches = PersonMatcher.matchExisting(imported, List.of(existing));

        assertThat(matches).hasSize(1);
        assertThat(matches.get(0).matchBasis()).isEqualTo(PersonMatchProposal.MatchBasis.PHONE_EXACT);
    }

    @Test
    void swedishInternationalPrefixCanonicalizesOntoDomesticForm() {
        // "+46 70 123 45 67" and "070-123 45 67" are the same Swedish subscriber number written
        // in international vs domestic form (M3 review finding 2).
        Person existing = person("p2b", "Eva", "Lind", null, "+46 70 123 45 67");
        ExtractedRow imported = row("E.", "L.", null, "070-123 45 67"); // Name differs - phone must carry the match.

        List<PersonMatchProposal> matches = PersonMatcher.matchExisting(imported, List.of(existing));

        assertThat(matches).hasSize(1);
        assertThat(matches.get(0).matchBasis()).isEqualTo(PersonMatchProposal.MatchBasis.PHONE_EXACT);

        // And the 0046... written form canonicalizes the same way.
        ExtractedRow imported0046 = row("E.", "L.", null, "0046 70 123 45 67");
        assertThat(PersonMatcher.matchExisting(imported0046, List.of(existing))).hasSize(1);
    }

    @Test
    void exactNameMatchWhenNoContactInfoOverlaps() {
        Person existing = person("p3", "Björn", "Lönnqvist", null, null);
        ExtractedRow imported = row("björn", "lönnqvist", null, null);

        List<PersonMatchProposal> matches = PersonMatcher.matchExisting(imported, List.of(existing));

        assertThat(matches).hasSize(1);
        assertThat(matches.get(0).matchBasis()).isEqualTo(PersonMatchProposal.MatchBasis.NAME_EXACT);
        assertThat(matches.get(0).confidence()).isEqualTo(0.9);
    }

    @Test
    void similarNameAboveThresholdIsProposed() {
        Person existing = person("p4", "Kristina", "Hansson", null, null);
        ExtractedRow imported = row("Kristina", "Hanson", null, null); // One letter off.

        List<PersonMatchProposal> matches = PersonMatcher.matchExisting(imported, List.of(existing));

        assertThat(matches).hasSize(1);
        assertThat(matches.get(0).matchBasis()).isEqualTo(PersonMatchProposal.MatchBasis.NAME_SIMILAR);
        assertThat(matches.get(0).confidence()).isGreaterThanOrEqualTo(PersonMatcher.NAME_SIMILARITY_THRESHOLD);
    }

    @Test
    void unrelatedPersonIsNotProposed() {
        Person existing = person("p5", "Oskar", "Lundgren", "oskar@example.se", "0701112233");
        ExtractedRow imported = row("Ingrid", "Jakobsson", "ingrid@example.se", "0704445566");

        List<PersonMatchProposal> matches = PersonMatcher.matchExisting(imported, List.of(existing));

        assertThat(matches).isEmpty();
    }

    @Test
    void proposalsAreSortedByDescendingConfidence() {
        Person exact = person("exact", "Anders", "Larsson", "anders@example.se", null);
        Person similar = person("similar", "Anders", "Larson", null, null);

        ExtractedRow imported = row("Anders", "Larsson", "anders@example.se", null);

        List<PersonMatchProposal> matches = PersonMatcher.matchExisting(imported, List.of(similar, exact));

        assertThat(matches.get(0).existingPersonId()).isEqualTo("exact");
    }
}
