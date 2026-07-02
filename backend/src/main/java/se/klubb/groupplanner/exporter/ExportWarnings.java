package se.klubb.groupplanner.exporter;

import java.util.ArrayList;
import java.util.List;
import se.klubb.groupplanner.domain.ParticipantProfile;
import se.klubb.groupplanner.domain.TrainingGroup;

/**
 * "Eventuella varningar" (spec §20.2's last column) — computed purely from already-persisted
 * domain data (no solver run required, since {@code run} is an OPTIONAL export query parameter),
 * shared by both the normal export path ({@link ExportDataAssembler}) and the anonymized export
 * path ({@link AnonymizedExportService}) — every warning string here is a structural statement
 * ("under minsta gruppstorlek", "saknar tränare"), never a name or free-text comment, so reuse
 * across the anonymized path introduces no leak risk.
 */
final class ExportWarnings {

    private ExportWarnings() {
    }

    static List<String> forParticipant(ParticipantProfile p) {
        List<String> warnings = new ArrayList<>();
        if (p.manualReviewFlag()) {
            warnings.add("Behöver manuell bedömning");
        }
        return warnings;
    }

    /** Group-level warnings, applied identically to every player row in the group (matches the real
     * file's convention of surfacing capacity/coach problems next to the affected players). */
    static List<String> forGroup(TrainingGroup g, int placedCount, int actualCoachCount) {
        List<String> warnings = new ArrayList<>();
        if (g.minSize() != null && placedCount < g.minSize()) {
            warnings.add("Under minsta gruppstorlek");
        }
        if (g.maxSize() != null && placedCount > g.maxSize()) {
            warnings.add("Över maxstorlek");
        }
        if (g.requiredCoachCount() > actualCoachCount) {
            warnings.add("Saknar tränare");
        }
        return warnings;
    }

    static List<String> combine(List<String> a, List<String> b) {
        List<String> combined = new ArrayList<>(a.size() + b.size());
        combined.addAll(a);
        combined.addAll(b);
        return combined;
    }
}
