package se.klubb.groupplanner.resources;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import se.klubb.groupplanner.api.error.BadRequestException;
import se.klubb.groupplanner.domain.Court;
import se.klubb.groupplanner.domain.TimeSlot;
import se.klubb.groupplanner.domain.TrainingBlock;
import se.klubb.groupplanner.repo.CourtRepository;
import se.klubb.groupplanner.repo.TrainingBlockRepository;
import se.klubb.groupplanner.util.Uuid7;

/**
 * The core of spec §12.2: {@code "Torsdag 18.00–19.30: 4 banor"} → creates {@code TrainingBlock}s
 * for {@code "Bana 1".."Bana 4"}. Called from {@code PUT
 * /api/plans/{planId}/time-slots/{slotId}/courts}.
 *
 * <h2>Court identity</h2>
 * Courts are club-wide (spec §12.2/§7.7-7.8), not scoped to a time slot: courts named exactly
 * {@code "Bana " + n} are looked up (or created, under the default venue — {@link
 * DefaultVenueService}) for {@code n = 1..count} and reused across every time slot that needs an
 * n-th court — e.g. three slots declared 4/4/3 courts share the same physical "Bana 1".."Bana 4"
 * pool and produce 4+4+3 = 11 blocks total. Blocks for courts that don't match the {@code "Bana N"}
 * naming pattern (which this service never creates itself) are left completely untouched, so a
 * manually-created custom-named court sharing a slot survives regeneration.
 *
 * <h2>Idempotency and identity</h2>
 * {@code UNIQUE(time_slot_id, court_id)} means the same (slot, court) pair always maps to the same
 * {@code training_block} row: calling this with an unchanged {@code count} makes no writes at all
 * (every court/block already exists and is already active). Growing the count only adds new rows for
 * the new courts; shrinking it never deletes anything — blocks for courts beyond the new count are
 * deactivated (spec §12.3: "MVP kan hantera detta genom att användaren kan inaktivera en
 * TrainingBlock"), so group/coach assignment history tied to a block id is never invalidated by a
 * later court-count change.
 *
 * <p><b>Manual exceptions survive regeneration</b> (M5 review fix): a block deactivated manually via
 * {@code PATCH /api/training-blocks/{id}} carries {@code deactivation_source = MANUAL} and is never
 * auto-reactivated by declaring {@code count = N}, even when its court index is within {@code 1..N}
 * — the §12.3 exception ("Bana 4 är inte tillgänglig 21.00–22.30") is the user's explicit override.
 * Only blocks this service itself deactivated ({@code deactivation_source = SHRINK}, or a legacy
 * {@code NULL}) are reactivated when the count grows back. {@code PATCH {active:true}} clears the
 * source, returning the block to normal generation management.
 */
@Service
public class TrainingBlockGenerationService {

    private static final Pattern BANA_PATTERN = Pattern.compile("^Bana (\\d+)$");

    private final CourtRepository courtRepository;
    private final TrainingBlockRepository trainingBlockRepository;
    private final DefaultVenueService defaultVenueService;

    public TrainingBlockGenerationService(
            CourtRepository courtRepository,
            TrainingBlockRepository trainingBlockRepository,
            DefaultVenueService defaultVenueService) {
        this.courtRepository = courtRepository;
        this.trainingBlockRepository = trainingBlockRepository;
        this.defaultVenueService = defaultVenueService;
    }

    static String courtName(int index) {
        return "Bana " + index;
    }

    /** Parses the trailing court index from a "Bana N" name, or {@code null} if it doesn't match. */
    static Integer parseCourtIndex(String courtName) {
        Matcher matcher = BANA_PATTERN.matcher(courtName);
        return matcher.matches() ? Integer.parseInt(matcher.group(1)) : null;
    }

    @Transactional
    public List<TrainingBlockView> generateForSlot(TimeSlot slot, int count) {
        if (count < 0) {
            throw new BadRequestException("count must be >= 0");
        }
        if (count > 60) {
            throw new BadRequestException("count is unreasonably large (max 60)");
        }
        String slotId = slot.id();
        String planId = slot.activityPlanId();

        Map<Integer, Court> courtsByIndex = new LinkedHashMap<>();
        if (count > 0) {
            String venueId = defaultVenueService.resolveOrCreateDefaultVenueId();
            for (int i = 1; i <= count; i++) {
                String name = courtName(i);
                Court court = courtRepository.findByVenueIdAndName(venueId, name)
                        .orElseGet(() -> courtRepository.insert(new Court(Uuid7.generate(), venueId, name, true, null)));
                courtsByIndex.put(i, court);
            }
        }

        Map<String, Court> courtCache = new HashMap<>();
        for (Court court : courtsByIndex.values()) {
            courtCache.put(court.id(), court);
        }

        // Deactivate any existing active "Bana N" block beyond the declared count, marking it
        // SHRINK so a later count increase may reactivate it. (An already-inactive MANUAL block
        // beyond the count keeps its MANUAL marker - the user's explicit exception outranks ours.)
        for (TrainingBlock block : trainingBlockRepository.findByTimeSlotId(slotId)) {
            Court court = courtCache.computeIfAbsent(block.courtId(), this::requireCourt);
            Integer index = parseCourtIndex(court.name());
            if (index != null && index > count && block.active()) {
                trainingBlockRepository.deactivate(block.id(), TrainingBlock.DEACTIVATION_SHRINK);
            }
        }

        // Create/reactivate blocks for courts 1..count. MANUAL deactivations (spec §12.3) are the
        // user's explicit exceptions and are never overridden here (M5 review fix); only blocks we
        // deactivated ourselves (SHRINK, or a legacy NULL source) come back automatically.
        for (Court court : courtsByIndex.values()) {
            TrainingBlock block = trainingBlockRepository.findByTimeSlotIdAndCourtId(slotId, court.id()).orElse(null);
            if (block == null) {
                trainingBlockRepository.insert(
                        new TrainingBlock(Uuid7.generate(), slotId, court.id(), planId, true, false, null));
            } else if (!block.active() && !TrainingBlock.DEACTIVATION_MANUAL.equals(block.deactivationSource())) {
                trainingBlockRepository.reactivate(block.id());
            }
        }

        return currentBlocksForSlot(slotId);
    }

    public List<TrainingBlockView> currentBlocksForSlot(String timeSlotId) {
        List<TrainingBlockView> views = new ArrayList<>();
        for (TrainingBlock block : trainingBlockRepository.findByTimeSlotId(timeSlotId)) {
            views.add(TrainingBlockView.of(block, requireCourt(block.courtId())));
        }
        views.sort(Comparator.comparingInt((TrainingBlockView v) -> {
            Integer index = parseCourtIndex(v.courtName());
            return index != null ? index : Integer.MAX_VALUE;
        }).thenComparing(TrainingBlockView::courtName));
        return views;
    }

    private Court requireCourt(String courtId) {
        return courtRepository.findById(courtId)
                .orElseThrow(() -> new IllegalStateException("Court referenced by a training_block not found: " + courtId));
    }
}
