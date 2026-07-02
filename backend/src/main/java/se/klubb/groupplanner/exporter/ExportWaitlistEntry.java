package se.klubb.groupplanner.exporter;

/** One "Kölista" row (spec §14/§20.2's implicit waitlist coverage, docs/plan.md Context: the real
 * file's Kölista section lists waitlisted players + priority). {@code priority} is the seeded
 * global {@code priority} custom field's value, or {@code null} if unset. */
public record ExportWaitlistEntry(String displayName, Double rankingPoints, Double estimatedLevel, Integer priority) {
}
