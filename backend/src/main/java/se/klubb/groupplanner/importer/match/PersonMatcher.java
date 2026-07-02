package se.klubb.groupplanner.importer.match;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import se.klubb.groupplanner.domain.Person;
import se.klubb.groupplanner.importer.ExtractedRow;

/**
 * Proposes matches between an imported row and existing {@link Person} records (spec §8.7): exact
 * external id (member id - the strongest basis, added per M3 review finding 1), exact email, exact
 * phone, exact normalized name, then similar name via Jaro-Winkler
 * (docs/design/02-product-data-ui.md §2: "Jaro-Winkler >= 0.92 on 'förnamn efternamn'").
 *
 * <p>Only the single best-scoring basis per existing person is reported (an exact external-id
 * match implies the name comparison is redundant); results are sorted by descending confidence.
 */
public final class PersonMatcher {

    public static final double NAME_SIMILARITY_THRESHOLD = 0.92;
    private static final int MAX_PROPOSALS = 5;

    private PersonMatcher() {
    }

    public static List<PersonMatchProposal> matchExisting(ExtractedRow row, List<Person> existingPersons) {
        String rowExternalId = normalizeExternalId(row.externalId());
        String rowEmail = normalize(row.email());
        String rowPhone = normalizePhone(row.phone());
        String rowName = normalizeName(row.firstName(), row.lastName(), row.displayName());

        List<PersonMatchProposal> proposals = new ArrayList<>();
        for (Person person : existingPersons) {
            PersonMatchProposal proposal = bestMatch(person, rowExternalId, rowEmail, rowPhone, rowName);
            if (proposal != null) {
                proposals.add(proposal);
            }
        }
        proposals.sort(Comparator.comparingDouble(PersonMatchProposal::confidence).reversed());
        return proposals.size() > MAX_PROPOSALS ? proposals.subList(0, MAX_PROPOSALS) : proposals;
    }

    private static PersonMatchProposal bestMatch(
            Person person, String rowExternalId, String rowEmail, String rowPhone, String rowName) {
        if (rowExternalId != null && rowExternalId.equals(normalizeExternalId(person.externalId()))) {
            return new PersonMatchProposal(person.id(), PersonMatchProposal.MatchBasis.EXTERNAL_ID_EXACT, 1.0);
        }
        if (rowEmail != null && rowEmail.equals(normalize(person.email()))) {
            return new PersonMatchProposal(person.id(), PersonMatchProposal.MatchBasis.EMAIL_EXACT, 1.0);
        }
        if (rowPhone != null && rowPhone.equals(normalizePhone(person.phone()))) {
            return new PersonMatchProposal(person.id(), PersonMatchProposal.MatchBasis.PHONE_EXACT, 0.95);
        }
        String personName = normalizeName(person.firstName(), person.lastName(), person.displayName());
        if (rowName != null && rowName.equals(personName)) {
            return new PersonMatchProposal(person.id(), PersonMatchProposal.MatchBasis.NAME_EXACT, 0.9);
        }
        if (rowName != null && personName != null) {
            double similarity = JaroWinkler.similarity(rowName, personName);
            if (similarity >= NAME_SIMILARITY_THRESHOLD) {
                return new PersonMatchProposal(person.id(), PersonMatchProposal.MatchBasis.NAME_SIMILAR, similarity);
            }
        }
        return null;
    }

    private static String normalize(String value) {
        return value == null || value.isBlank() ? null : value.strip().toLowerCase(Locale.ROOT);
    }

    /** External ids are opaque source-system keys: trimmed but otherwise compared verbatim. */
    static String normalizeExternalId(String value) {
        return value == null || value.isBlank() ? null : value.strip();
    }

    /**
     * Canonicalizes a phone number to digits only, mapping the Swedish international prefix
     * ({@code +46...}/{@code 0046...}) onto the domestic {@code 0...} form so
     * {@code "+46 70 123 45 67"} and {@code "070-123 45 67"} compare equal (M3 review finding 2).
     * The length guard keeps short non-subscriber strings (e.g. a bare "46") untouched.
     */
    static String normalizePhone(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        String digitsOnly = value.replaceAll("[^0-9]", "");
        if (digitsOnly.isEmpty()) {
            return null;
        }
        if (digitsOnly.startsWith("0046") && digitsOnly.length() >= 12) {
            return "0" + digitsOnly.substring(4);
        }
        if (digitsOnly.startsWith("46") && digitsOnly.length() >= 10) {
            return "0" + digitsOnly.substring(2);
        }
        return digitsOnly;
    }

    public static String normalizeName(String firstName, String lastName, String displayName) {
        boolean hasFirstOrLast = ExtractedRow.isNonBlank(firstName) || ExtractedRow.isNonBlank(lastName);
        String combined = hasFirstOrLast
                ? (nullToEmpty(firstName) + " " + nullToEmpty(lastName)).strip()
                : displayName;
        return combined == null || combined.isBlank() ? null : combined.toLowerCase(Locale.ROOT).replaceAll("\\s+", " ").strip();
    }

    private static String nullToEmpty(String s) {
        return s == null ? "" : s;
    }
}
