package se.klubb.groupplanner.suggest;

import com.fasterxml.jackson.databind.JsonNode;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import org.springframework.stereotype.Service;
import se.klubb.groupplanner.domain.CoachProfile;
import se.klubb.groupplanner.domain.CustomFieldValue;
import se.klubb.groupplanner.domain.ParticipantProfile;
import se.klubb.groupplanner.domain.Person;
import se.klubb.groupplanner.domain.TimeSlot;
import se.klubb.groupplanner.fields.FieldValueService;
import se.klubb.groupplanner.fields.FieldValueView;
import se.klubb.groupplanner.repo.CoachProfileRepository;
import se.klubb.groupplanner.repo.ParticipantProfileRepository;
import se.klubb.groupplanner.repo.PersonRepository;
import se.klubb.groupplanner.repo.TimeSlotRepository;
import se.klubb.groupplanner.suggest.CommentSuggestion.Confidence;
import se.klubb.groupplanner.suggest.CommentSuggestion.ParticipantSuggestions;
import se.klubb.groupplanner.suggest.CommentSuggestion.TargetCandidate;
import se.klubb.groupplanner.suggest.CommentSuggestionParser.RawMatch;
import se.klubb.groupplanner.suggest.RosterNameResolver.Resolution;
import se.klubb.groupplanner.suggest.RosterNameResolver.RosterEntry;

/**
 * "Tolkningsförslag" (WP2) — rule-based, local, NON-AI suggestions parsed from a participant's
 * free-text {@code imported_comment}, proposing which structured field(s) the council might want
 * to fill in based on it (e.g. "vill gärna spela med Anna Svensson" -&gt; suggest adding Anna to
 * {@code playWith}). Every suggestion is NON-BINDING: this service only ever READS data and returns
 * a proposal; nothing is written unless the council clicks "Lägg till", which goes through the
 * EXISTING {@link FieldValueService}/participant-PATCH write paths — this class never writes
 * anything itself.
 *
 * <h2>Hard privacy contract (CLAUDE.md + the five leak tests)</h2>
 *
 * Comment text must never reach solver input, any {@code *_json} column, or the default export.
 * This service satisfies that the only way a class with no persistence at all can: it computes
 * every suggestion ON DEMAND from the live {@code imported_comment} column and PERSISTS NOTHING —
 * no new table, no migration, no cache in the database (contrast {@code ImprovementSuggestionCache},
 * which caches a solver-derived response that never touches comment text in the first place). The
 * response DTO ({@link CommentSuggestion}) does echo a verbatim comment span in {@code matchedText}
 * — that is intentional and has the exact same privacy status as {@code GET .../participants/{id}}
 * already returning the whole {@code importedComment}: both are authenticated, same-plan-scoped
 * reads of a field the council is already trusted to see in the Deltagarvy drawer. Dismissals are
 * session-local React state on the frontend, never sent back to this service.
 *
 * <p>The parsing itself ({@link CommentSuggestionParser}) is pure regex/string matching — no LLM,
 * no network call, no cloud service — satisfying the spec's "no AI/cloud comment interpretation"
 * rule the same way {@code SwedishTimeParser} (M4) and {@code PersonMatcher} (M3) already do for
 * their own free-text inputs.
 */
@Service
public class CommentSuggestionService {

    private final ParticipantProfileRepository participantProfileRepository;
    private final PersonRepository personRepository;
    private final CoachProfileRepository coachProfileRepository;
    private final TimeSlotRepository timeSlotRepository;
    private final FieldValueService fieldValueService;

    public CommentSuggestionService(
            ParticipantProfileRepository participantProfileRepository,
            PersonRepository personRepository,
            CoachProfileRepository coachProfileRepository,
            TimeSlotRepository timeSlotRepository,
            FieldValueService fieldValueService) {
        this.participantProfileRepository = participantProfileRepository;
        this.personRepository = personRepository;
        this.coachProfileRepository = coachProfileRepository;
        this.timeSlotRepository = timeSlotRepository;
        this.fieldValueService = fieldValueService;
    }

    /** Suggestions for one participant. Caller (controller) is responsible for the plan/participant
     *  existence guard, matching {@code ParticipantFieldValueController}'s convention. */
    public ParticipantSuggestions suggestionsForParticipant(String planId, String participantId) {
        ParticipantProfile participant = participantProfileRepository.findById(participantId).orElseThrow();
        RosterCache cache = buildRosterCache(planId);
        return buildFor(planId, participant, cache);
    }

    /** Every participant in the plan with at least one suggestion (WP2: "Plan-level method aggregates
     *  participants with ≥1 suggestion"), in stable participant-id order. Full detail (including
     *  {@code matchedText}) - used internally by {@link #suggestionCountsForPlan(String)} and by
     *  tests; the actual plan-wide REST endpoint uses the counts-only projection instead (review fix
     *  MAJOR 6 — see that method's javadoc). */
    public List<ParticipantSuggestions> suggestionsForPlan(String planId) {
        RosterCache cache = buildRosterCache(planId);
        List<ParticipantSuggestions> out = new ArrayList<>();
        for (ParticipantProfile participant : participantProfileRepository.findByActivityPlanId(planId)) {
            ParticipantSuggestions result = buildFor(planId, participant, cache);
            if (!result.suggestions().isEmpty()) {
                out.add(result);
            }
        }
        return out;
    }

    /**
     * Review fix (MAJOR 6, "comment minimization"): the plan-wide grid badge only needs a count, not
     * verbatim {@code matchedText}/candidate names for the ENTIRE roster - {@code GET
     * /api/plans/{planId}/comment-suggestions} uses this instead of {@link #suggestionsForPlan}. The
     * count is of NOT-YET-applied suggestions (mirrors the frontend's pre-existing "unapplied" grid
     * badge filter, now computed server-side); a participant with zero unapplied suggestions is
     * omitted entirely.
     */
    public List<ParticipantSuggestionCount> suggestionCountsForPlan(String planId) {
        List<ParticipantSuggestionCount> out = new ArrayList<>();
        for (ParticipantSuggestions ps : suggestionsForPlan(planId)) {
            long unapplied = ps.suggestions().stream().filter(s -> !s.alreadyApplied()).count();
            if (unapplied > 0) {
                out.add(new ParticipantSuggestionCount(ps.participantId(), (int) unapplied));
            }
        }
        return out;
    }

    private ParticipantSuggestions buildFor(String planId, ParticipantProfile participant, RosterCache cache) {
        String comment = participant.importedComment();
        if (comment == null || comment.isBlank()) {
            return new ParticipantSuggestions(participant.id(), List.of());
        }
        List<RawMatch> rawMatches = CommentSuggestionParser.parse(comment);
        if (rawMatches.isEmpty()) {
            return new ParticipantSuggestions(participant.id(), List.of());
        }

        Map<String, FieldValueView> currentByKey = new HashMap<>();
        for (FieldValueView view : fieldValueService.getValues(planId, CustomFieldValue.ENTITY_TYPE_PARTICIPANT, participant.id())) {
            currentByKey.put(view.key(), view);
        }

        List<CommentSuggestion> suggestions = new ArrayList<>();
        for (RawMatch match : rawMatches) {
            CommentSuggestion suggestion = resolve(match, participant, cache, currentByKey);
            if (suggestion != null) {
                suggestions.add(suggestion);
            }
        }
        return new ParticipantSuggestions(participant.id(), suggestions);
    }

    private CommentSuggestion resolve(
            RawMatch match, ParticipantProfile participant, RosterCache cache, Map<String, FieldValueView> currentByKey) {
        SuggestionKind kind = match.kind();
        if (kind.isFlagKind()) {
            boolean alreadyApplied = participant.manualReviewFlag();
            return build(kind, match, List.of(), List.of(), Confidence.HIGH, alreadyApplied, participant.id());
        }
        if (kind == SuggestionKind.NEW_TO_CLUB) {
            boolean alreadyApplied = isBooleanTrue(currentByKey.get(kind.fieldKey()));
            return build(kind, match, List.of(), List.of(), Confidence.HIGH, alreadyApplied, participant.id());
        }
        if (kind.targetsParticipants()) {
            List<RosterEntry> roster = cache.participantEntries().stream()
                    .filter(entry -> !entry.id().equals(participant.id()))
                    .toList();
            Resolution resolution = RosterNameResolver.resolve(match.nameWindowText(), roster);
            if (resolution == null) {
                return null;
            }
            List<TargetCandidate> targets = withAppliedFlags(resolution.candidates(), currentByKey.get(kind.fieldKey()));
            return build(kind, match, targets, List.of(), resolution.confidence(), suggestionAlreadyApplied(targets), participant.id());
        }
        if (kind.targetsCoaches()) {
            Resolution resolution = RosterNameResolver.resolve(match.nameWindowText(), cache.coachEntries());
            if (resolution == null) {
                return null;
            }
            List<TargetCandidate> targets = withAppliedFlags(resolution.candidates(), currentByKey.get(kind.fieldKey()));
            return build(kind, match, targets, List.of(), resolution.confidence(), suggestionAlreadyApplied(targets), participant.id());
        }
        if (kind.targetsTimeSlots()) {
            List<String> slotIds = resolveTimeSlots(match.clauseText(), cache.timeSlots());
            if (slotIds.isEmpty()) {
                return null;
            }
            boolean alreadyApplied = allSlotsAlreadyPresent(currentByKey.get(kind.fieldKey()), slotIds);
            return build(kind, match, List.of(), slotIds, Confidence.HIGH, alreadyApplied, participant.id());
        }
        return null;
    }

    private CommentSuggestion build(
            SuggestionKind kind, RawMatch match, List<TargetCandidate> targets, List<String> timeSlotIds,
            Confidence confidence, boolean alreadyApplied, String participantId) {
        List<String> idsForFingerprint = !timeSlotIds.isEmpty()
                ? timeSlotIds
                : targets.stream().map(TargetCandidate::id).toList();
        String fingerprint = fingerprint(participantId, kind, match.cueOffset(), idsForFingerprint);
        return new CommentSuggestion(fingerprint, kind, match.matchedText(), kind.fieldKey(), targets, timeSlotIds, confidence, alreadyApplied);
    }

    // ─────────────────────────────────────────────────────────────────────── roster/time helpers

    /** Review fix (minor 5): a plan-scoped lookup, not {@code personRepository.findAll()} — that
     *  method scans EVERY person ever created across every season/plan, unbounded by this plan's
     *  actual roster size. No plan-scoped bulk lookup exists on {@code PersonRepository} (checked
     *  every other caller — {@code SolverInputAssembler}/{@code ExportDataAssembler} both do the same
     *  per-id {@code findById}, some behind a per-call cache), so this mirrors that established
     *  convention: one {@code findById} per participant/coach, bounded by the plan's own roster
     *  rather than the whole database. Called once per {@link #suggestionsForPlan}/{@link
     *  #suggestionsForParticipant} invocation, not per participant inside the loop. */
    private RosterCache buildRosterCache(String planId) {
        List<ParticipantProfile> participants = participantProfileRepository.findByActivityPlanId(planId);
        List<CoachProfile> coaches = coachProfileRepository.findByActivityPlanId(planId);

        Map<String, Person> personById = new HashMap<>();
        for (ParticipantProfile pp : participants) {
            personById.computeIfAbsent(pp.personId(), id -> personRepository.findById(id).orElse(null));
        }
        for (CoachProfile cp : coaches) {
            personById.computeIfAbsent(cp.personId(), id -> personRepository.findById(id).orElse(null));
        }

        List<RosterEntry> participantEntries = new ArrayList<>();
        for (ParticipantProfile pp : participants) {
            Person person = personById.get(pp.personId());
            if (person != null) {
                participantEntries.add(new RosterEntry(pp.id(), person.displayName(), person.firstName(), person.lastName()));
            }
        }
        List<RosterEntry> coachEntries = new ArrayList<>();
        for (CoachProfile cp : coaches) {
            Person person = personById.get(cp.personId());
            if (person != null) {
                coachEntries.add(new RosterEntry(cp.id(), person.displayName(), person.firstName(), person.lastName()));
            }
        }
        List<TimeSlot> timeSlots = timeSlotRepository.findByActivityPlanId(planId);
        return new RosterCache(participantEntries, coachEntries, timeSlots);
    }

    private record RosterCache(List<RosterEntry> participantEntries, List<RosterEntry> coachEntries, List<TimeSlot> timeSlots) {
    }

    /** Resolves a TIME_CANNOT/TIME_PREFER clause's weekday/explicit-time cues against the plan's
     *  actual {@code TimeSlot}s (spec: "no match -&gt; drop"; "multiple matching slots -&gt; all ids
     *  in one suggestion"). Review fix MAJORS 3/4: {@link #extractTimeSpec} refuses to treat a bare,
     *  unqualified number as a clock hour at all (no false "18 augusti" -&gt; 18:00 reading), and a
     *  qualifying time carries a direction (efter/före/plain) that changes which slots match — see
     *  {@link #matchesTime}. */
    private static List<String> resolveTimeSlots(String clauseText, List<TimeSlot> slots) {
        if (clauseText == null) {
            return List.of();
        }
        Set<String> matchedDays = new LinkedHashSet<>();
        for (CommentRuleLexicon.WeekdayCue cue : CommentRuleLexicon.WEEKDAYS) {
            if (cue.pattern().matcher(clauseText).find()) {
                matchedDays.add(cue.dayOfWeek());
            }
        }
        TimeSpec timeSpec = extractTimeSpec(clauseText);
        if (matchedDays.isEmpty() && timeSpec == null) {
            return List.of();
        }
        List<String> ids = new ArrayList<>();
        for (TimeSlot slot : slots) {
            boolean dayOk = matchedDays.isEmpty() || matchedDays.contains(weekdayOf(slot));
            boolean timeOk = timeSpec == null || matchesTime(slot, timeSpec);
            if (dayOk && timeOk) {
                ids.add(slot.id());
            }
        }
        return ids;
    }

    private enum TimeDirection { AFTER, BEFORE, COVERS }

    /** {@code minutes} = minutes since midnight; {@code direction} says how a plan {@code TimeSlot}
     *  must relate to it (see {@link #matchesTime}). */
    private record TimeSpec(int minutes, TimeDirection direction) {
    }

    /** Review fix MAJOR 3: a bare 1-2 digit number is NEVER read as a clock hour on its own — only
     *  when it carries minutes ({@code "18:30"}/{@code "18.30"}), a {@code "kl(ockan)?"} prefix, or is
     *  directly governed by a before/after preposition ({@code "efter 19"}) does it qualify. Tried in
     *  before/after-first order so a direction-governed number (which also happens to match the bare
     *  {@link CommentRuleLexicon#TIME_WITH_MINUTES}/{@link CommentRuleLexicon#TIME_KL_PREFIX} patterns
     *  too) keeps its direction rather than falling through to {@link TimeDirection#COVERS}. */
    private static TimeSpec extractTimeSpec(String clauseText) {
        Matcher after = CommentRuleLexicon.TIME_AFTER.matcher(clauseText);
        if (after.find()) {
            Integer minutes = toMinutes(after.group(1), after.group(2));
            if (minutes != null) {
                return new TimeSpec(minutes, TimeDirection.AFTER);
            }
        }
        Matcher before = CommentRuleLexicon.TIME_BEFORE.matcher(clauseText);
        if (before.find()) {
            Integer minutes = toMinutes(before.group(1), before.group(2));
            if (minutes != null) {
                return new TimeSpec(minutes, TimeDirection.BEFORE);
            }
        }
        Matcher withMinutes = CommentRuleLexicon.TIME_WITH_MINUTES.matcher(clauseText);
        if (withMinutes.find()) {
            Integer minutes = toMinutes(withMinutes.group(1), withMinutes.group(2));
            if (minutes != null) {
                return new TimeSpec(minutes, TimeDirection.COVERS);
            }
        }
        Matcher klPrefix = CommentRuleLexicon.TIME_KL_PREFIX.matcher(clauseText);
        if (klPrefix.find()) {
            Integer minutes = toMinutes(klPrefix.group(1), klPrefix.group(2));
            if (minutes != null) {
                return new TimeSpec(minutes, TimeDirection.COVERS);
            }
        }
        return null; // No qualifying time-like number anywhere in the clause - ignore it entirely.
    }

    private static Integer toMinutes(String hourText, String minuteText) {
        try {
            int hour = Integer.parseInt(hourText);
            int minute = minuteText == null ? 0 : Integer.parseInt(minuteText);
            if (hour < 0 || hour > 23 || minute < 0 || minute > 59) {
                return null;
            }
            return hour * 60 + minute;
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private static String weekdayOf(TimeSlot slot) {
        if (slot.dayOfWeek() != null) {
            return slot.dayOfWeek();
        }
        if (slot.date() != null) {
            try {
                return LocalDate.parse(slot.date()).getDayOfWeek().name();
            } catch (DateTimeParseException ignored) {
                return null;
            }
        }
        return null;
    }

    /** Review fix MAJOR 4: {@code AFTER}/{@code BEFORE} compare against the slot's START time only
     *  (spec: "efter|från och med|från -&gt; slots with start &gt;= that time"; "före|innan|fram till
     *  -&gt; slots with start &lt; that time"); a plain, direction-less time must fall WITHIN the
     *  slot's [start, end) interval ({@code TimeSlot.startTime()}/{@code endTime()} are "HH:mm" text,
     *  see that record's javadoc) rather than exactly equal its start, so "18.30" correctly resolves
     *  the 18:00-19:30 slot that CONTAINS it. */
    private static boolean matchesTime(TimeSlot slot, TimeSpec spec) {
        Integer startMinutes = parseMinutes(slot.startTime());
        if (startMinutes == null) {
            return false;
        }
        return switch (spec.direction()) {
            case AFTER -> startMinutes >= spec.minutes();
            case BEFORE -> startMinutes < spec.minutes();
            case COVERS -> {
                Integer endMinutes = parseMinutes(slot.endTime());
                yield endMinutes == null
                        ? startMinutes.equals(spec.minutes())
                        : startMinutes <= spec.minutes() && spec.minutes() < endMinutes;
            }
        };
    }

    private static Integer parseMinutes(String hhmm) {
        if (hhmm == null) {
            return null;
        }
        String[] parts = hhmm.split(":");
        if (parts.length < 2) {
            return null;
        }
        try {
            return Integer.parseInt(parts[0]) * 60 + Integer.parseInt(parts[1]);
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private static boolean isBooleanTrue(FieldValueView view) {
        return view != null && view.value() != null && view.value().isBoolean() && view.value().asBoolean();
    }

    /** Review fix (minor 1): fills in each candidate's OWN {@code applied} flag independently, rather
     *  than a single suggestion-wide "any candidate present" check that would block applying a
     *  DIFFERENT, not-yet-applied UNCERTAIN candidate just because some unrelated one already is. */
    private static List<TargetCandidate> withAppliedFlags(List<TargetCandidate> candidates, FieldValueView view) {
        Set<String> current = currentIds(view);
        return candidates.stream()
                .map(c -> new TargetCandidate(c.id(), c.displayName(), c.score(), current.contains(c.id())))
                .toList();
    }

    /** Review fix (minor 1): a single-candidate (HIGH-confidence) suggestion is "already applied" iff
     *  its one target is; a multi-candidate (UNCERTAIN) suggestion only counts as applied once EVERY
     *  candidate is (otherwise there is still a legitimate not-yet-applied option to pick). */
    private static boolean suggestionAlreadyApplied(List<TargetCandidate> targets) {
        return !targets.isEmpty() && targets.stream().allMatch(TargetCandidate::applied);
    }

    private static boolean allSlotsAlreadyPresent(FieldValueView view, List<String> slotIds) {
        Set<String> current = currentIds(view);
        return current.containsAll(slotIds);
    }

    private static Set<String> currentIds(FieldValueView view) {
        if (view == null || view.value() == null || !view.value().isArray()) {
            return Set.of();
        }
        Set<String> ids = new LinkedHashSet<>();
        for (JsonNode node : view.value()) {
            if (node.isTextual()) {
                ids.add(node.asText());
            }
        }
        return ids;
    }

    /** Stable id derived from {@code participantId|kind|cueOffset|sortedTargetIds} — never stored,
     *  recomputed on every request; only used by the frontend's session-local dismiss set. */
    private static String fingerprint(String participantId, SuggestionKind kind, int cueOffset, List<String> targetIds) {
        List<String> sorted = new ArrayList<>(targetIds);
        Collections.sort(sorted);
        String raw = participantId + "|" + kind + "|" + cueOffset + "|" + String.join(",", sorted);
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(raw.getBytes(StandardCharsets.UTF_8));
            StringBuilder hex = new StringBuilder();
            for (int i = 0; i < 8; i++) {
                hex.append(String.format("%02x", hash[i]));
            }
            return hex.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 must be available", e);
        }
    }
}
