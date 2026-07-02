package se.klubb.groupplanner.explain;

import java.util.LinkedHashMap;
import java.util.Map;
import se.klubb.groupplanner.fields.HardOrSoft;
import se.klubb.groupplanner.solver.constraints.ConstraintKeys;

/**
 * Static Swedish registry for every constraint key {@link ConstraintKeys} knows about (M7 scope
 * item 3, docs/design/04-solver.md §14.1's {@code explain} package note: "key -&gt; Swedish label,
 * description template, default level+weight, unit"). One canonical source both {@link
 * ExplanationService}'s message templates and the plan-level {@code constraintSummaries} (persisted
 * by {@code se.klubb.groupplanner.solver.run.OptimizationRunService}) draw from, so a label never
 * drifts between "why was I placed here" and "what constraints ran".
 *
 * <p>Labels/descriptions deliberately mirror the {@code constraint_definition} seed text
 * (V2/V5/V6 migrations) so a constraint reads identically in the Field Builder/weights UI and in an
 * explanation — kept as a static compiled registry (not read from the DB) because message templates
 * are code, not data (design's own framing), and because {@link #of(String)} must resolve for keys
 * that have NO {@code constraint_definition} row at all: the two "satisfied by construction" HARD
 * rows ({@link ConstraintKeys#GROUP_REQUIRES_TRAINING_BLOCK}/{@link
 * ConstraintKeys#LOCKED_ASSIGNMENT_HARD}) and the complement/fan-out children ({@code
 * groupSizeTargetEmpty}, {@code groupMinSizeEmpty}, {@code lateTimeTopGroups}/{@code
 * lateTimeBottomGroups}) that only ever exist as {@code .asConstraint(...)} keys in {@link
 * se.klubb.groupplanner.solver.constraints.GroupPlanConstraintProvider}.
 *
 * <p>{@code unit} distinguishes constraints whose DB weight is a flat per-occurrence cost/reward
 * ({@link Unit#PER_MATCH}, e.g. one broken pair wish) from constraints whose weight is multiplied by
 * some magnitude ({@link Unit#PER_POINT}, e.g. level-spread points, players over/under a size bound,
 * groupOrder steps) — this is exactly what the design's §4 table's "50/spelare", "2/nivåpoäng" style
 * annotations encode, made machine-readable for message templates ("Grupp C är full: 12/12" needs no
 * unit; "Nivåspridning ökar 82 → 141" is inherently per-point).
 */
public final class ConstraintMetadata {

    public enum Unit { PER_MATCH, PER_POINT }

    public enum Direction { PENALIZE, REWARD }

    /**
     * @param key same string as {@code .asConstraint(key)} / {@code constraint_definition.key}
     * @param label Swedish, short (matches the seeded {@code constraint_definition.label} where one exists)
     * @param descriptionTemplate Swedish, one sentence (matches the seeded {@code description} where one exists)
     * @param level {@link HardOrSoft#HARD}/{@link HardOrSoft#MEDIUM}/{@link HardOrSoft#SOFT} — the
     *     constraint's OWN level as coded (a plan may reclassify HARD&lt;-&gt;SOFT at solve time via
     *     {@code ConstraintWeightOverrides}; this is the code-default used when no override is known)
     * @param unit per-match flat cost/reward vs. per-point-of-magnitude
     * @param direction whether the constraint code calls {@code .penalize(...)} or {@code .reward(...)}
     * @param satisfiedByConstruction true for the two HARD rows with no Constraint Streams code at all
     * @param codeImplemented false only for {@link ConstraintKeys#LATE_TIME_FOR_LOWER_GROUPS} — a
     *     {@code constraint_definition} row that fans its weight out to two REAL code keys ({@link
     *     ConstraintKeys#LATE_TIME_TOP_GROUPS}/{@link ConstraintKeys#LATE_TIME_BOTTOM_GROUPS}) but has
     *     no {@code .asConstraint} call of its own (see {@link ConstraintKeys#COMPLEMENTS_OF})
     */
    public record Meta(
            String key,
            String label,
            String descriptionTemplate,
            String level,
            Unit unit,
            Direction direction,
            boolean satisfiedByConstruction,
            boolean codeImplemented) {
    }

    private static final Map<String, Meta> BY_KEY = new LinkedHashMap<>();

    private static void add(String key, String label, String description, String level, Unit unit, Direction direction) {
        BY_KEY.put(key, new Meta(key, label, description, level, unit, direction, false, true));
    }

    private static void addSatisfiedByConstruction(String key, String label, String description, String level) {
        BY_KEY.put(key, new Meta(key, label, description, level, Unit.PER_MATCH, Direction.PENALIZE, true, false));
    }

    private static void addFanOutParent(String key, String label, String description, String level, Unit unit) {
        BY_KEY.put(key, new Meta(key, label, description, level, unit, Direction.PENALIZE, false, false));
    }

    static {
        // --- HARD: satisfied by construction (docs/design/04-solver.md §1.4/§5) ---
        addSatisfiedByConstruction(ConstraintKeys.GROUP_REQUIRES_TRAINING_BLOCK,
                "Grupp kräver träningstid/bana", "Varje grupp ska ha exakt en TrainingBlock (garanteras av modellen).",
                HardOrSoft.HARD);
        addSatisfiedByConstruction(ConstraintKeys.LOCKED_ASSIGNMENT_HARD,
                "Låst placering (hård)", "Låsta placeringar får inte ändras (garanteras av låsning).", HardOrSoft.HARD);

        // --- HARD: implemented ---
        add(ConstraintKeys.TRAINING_BLOCK_CAPACITY, "En träningstid/bana per grupp",
                "En TrainingBlock kan bara användas av en grupp.", HardOrSoft.HARD, Unit.PER_MATCH, Direction.PENALIZE);
        add(ConstraintKeys.GROUP_MAX_SIZE_HARD, "Max gruppstorlek (hård)",
                "Grupp får inte ha fler spelare än maxSize.", HardOrSoft.HARD, Unit.PER_POINT, Direction.PENALIZE);
        add(ConstraintKeys.TIME_AVAILABILITY_HARD, "Tillgänglig tid (hård)",
                "Spelare får inte placeras i grupp på en tid spelaren inte kan.", HardOrSoft.HARD, Unit.PER_MATCH,
                Direction.PENALIZE);
        add(ConstraintKeys.SAME_GROUP_HARD, "Samma grupp (hård)",
                "Två spelare måste placeras i samma grupp.", HardOrSoft.HARD, Unit.PER_MATCH, Direction.PENALIZE);
        add(ConstraintKeys.DIFFERENT_GROUP_HARD, "Olika grupper (hård)",
                "Två spelare får inte placeras i samma grupp.", HardOrSoft.HARD, Unit.PER_MATCH, Direction.PENALIZE);
        add(ConstraintKeys.COACH_NO_OVERLAP, "Tränare krockar inte",
                "En tränare kan inte coacha två grupper samtidigt.", HardOrSoft.HARD, Unit.PER_MATCH, Direction.PENALIZE);
        add(ConstraintKeys.PLAYER_NO_OVERLAP, "Spelare krockar inte",
                "En spelare kan inte träna i två grupper samtidigt.", HardOrSoft.HARD, Unit.PER_MATCH, Direction.PENALIZE);
        add(ConstraintKeys.COACH_CANNOT_TRAIN_AND_COACH_SAME_TIME, "Tränare kan inte spela och coacha samtidigt",
                "Samma person kan inte vara tränare samtidigt som personen själv tränar.", HardOrSoft.HARD, Unit.PER_MATCH,
                Direction.PENALIZE);
        add(ConstraintKeys.COACH_AVAILABILITY_HARD, "Tränartillgänglighet (hård)",
                "Tränare får inte tilldelas grupp på tid tränaren inte är tillgänglig.", HardOrSoft.HARD, Unit.PER_MATCH,
                Direction.PENALIZE);
        add(ConstraintKeys.COACH_REQUIREMENT_HARD, "Tränarkrav (hård)",
                "Om grupp kräver tränare måste den ha tränare.", HardOrSoft.HARD, Unit.PER_MATCH, Direction.PENALIZE);
        add(ConstraintKeys.COACH_MAX_GROUPS, "Max antal grupper per tränare",
                "En tränare får inte tilldelas fler grupper än sitt maxantal.", HardOrSoft.HARD, Unit.PER_POINT,
                Direction.PENALIZE);
        add(ConstraintKeys.COACH_WISH_REQUIRED, "Måste ha tränare (hård)",
                "Om spelare/grupp måste ha en viss tränare måste den tränaren tilldelas.", HardOrSoft.HARD, Unit.PER_MATCH,
                Direction.PENALIZE);
        add(ConstraintKeys.COACH_WISH_FORBIDDEN, "Kan inte ha tränare (hård)",
                "Spelare/grupp får inte tilldelas en förbjuden tränare.", HardOrSoft.HARD, Unit.PER_MATCH, Direction.PENALIZE);
        add(ConstraintKeys.SAVED_PLAN_PERSON_BLOCKED, "Blockering: spelare i sparad plan",
                "En spelare som redan är bokad i en annan sparad/låst plan får inte krocka i tid.", HardOrSoft.HARD,
                Unit.PER_MATCH, Direction.PENALIZE);
        add(ConstraintKeys.SAVED_PLAN_COACH_BLOCKED, "Blockering: tränare i sparad plan",
                "En tränare som redan är bokad i en annan sparad/låst plan får inte krocka i tid.", HardOrSoft.HARD,
                Unit.PER_MATCH, Direction.PENALIZE);
        add(ConstraintKeys.SAVED_PLAN_COURT_BLOCKED, "Blockering: bana i sparad plan",
                "En bana som redan är bokad i en annan sparad/låst plan får inte krocka i tid.", HardOrSoft.HARD,
                Unit.PER_MATCH, Direction.PENALIZE);

        // --- MEDIUM: reserved waitlist penalty (ADR-006) ---
        add(ConstraintKeys.UNASSIGNED_PLAYER, "Oplacerad spelare (kölista)",
                "Minimera antal oplacerade spelare, viktat efter prioritet.", HardOrSoft.MEDIUM, Unit.PER_MATCH,
                Direction.PENALIZE);

        // --- SOFT: implemented ---
        add(ConstraintKeys.GROUP_SIZE_TARGET, "Målstorlek grupp",
                "Straffa avvikelse från targetSize.", HardOrSoft.SOFT, Unit.PER_POINT, Direction.PENALIZE);
        add(ConstraintKeys.GROUP_SIZE_TARGET_EMPTY, "Målstorlek grupp (tom grupp)",
                "Straffa avvikelse från targetSize för en tom grupp.", HardOrSoft.SOFT, Unit.PER_POINT, Direction.PENALIZE);
        add(ConstraintKeys.GROUP_MIN_SIZE_SOFT, "Minsta gruppstorlek (mjuk)",
                "Straffa grupper som är under minSize.", HardOrSoft.SOFT, Unit.PER_POINT, Direction.PENALIZE);
        add(ConstraintKeys.GROUP_MIN_SIZE_EMPTY, "Minsta gruppstorlek (tom grupp)",
                "Straffa en tom grupp för att vara under minSize.", HardOrSoft.SOFT, Unit.PER_POINT, Direction.PENALIZE);
        add(ConstraintKeys.LEVEL_BALANCE, "Nivåbalans i grupp",
                "Minimera nivåspridning inom grupp.", HardOrSoft.SOFT, Unit.PER_POINT, Direction.PENALIZE);
        add(ConstraintKeys.GROUP_ORDER_BY_LEVEL, "Gruppordning efter nivå",
                "Högre grupper ska generellt ha högre nivå än lägre grupper.", HardOrSoft.SOFT, Unit.PER_POINT,
                Direction.PENALIZE);
        add(ConstraintKeys.PREVIOUS_GROUP_CONTINUITY, "Kontinuitet från tidigare grupp",
                "Belöna placering nära tidigare gruppnivå.", HardOrSoft.SOFT, Unit.PER_POINT, Direction.PENALIZE);
        add(ConstraintKeys.TIME_PREFERENCE_SOFT, "Tidspreferens (mjuk)",
                "Straffa placering på oönskad men tillåten tid.", HardOrSoft.SOFT, Unit.PER_MATCH, Direction.PENALIZE);
        add(ConstraintKeys.SAME_GROUP_SOFT, "Samma grupp (mjuk)",
                "Två spelare bör placeras i samma grupp.", HardOrSoft.SOFT, Unit.PER_MATCH, Direction.PENALIZE);
        add(ConstraintKeys.DIFFERENT_GROUP_SOFT, "Olika grupper (mjuk)",
                "Två spelare bör helst inte placeras i samma grupp.", HardOrSoft.SOFT, Unit.PER_MATCH, Direction.PENALIZE);
        add(ConstraintKeys.COACH_LEVEL_FIT, "Tränarnivå passar grupp",
                "Straffa tränare som är för låg eller för hög nivå för gruppen.", HardOrSoft.SOFT, Unit.PER_POINT,
                Direction.PENALIZE);
        add(ConstraintKeys.COACH_PREFERENCE_SOFT, "Tränarpreferens (mjuk)",
                "Belöna om spelare/grupp får önskad tränare.", HardOrSoft.SOFT, Unit.PER_MATCH, Direction.REWARD);
        add(ConstraintKeys.LATE_TIME_TOP_GROUPS, "Sen tid för högre grupper (straff)",
                "Högre grupper straffas för sena tider när policyn är aktiv.", HardOrSoft.SOFT, Unit.PER_MATCH,
                Direction.PENALIZE);
        add(ConstraintKeys.LATE_TIME_BOTTOM_GROUPS, "Sen tid för lägre grupper (belöning)",
                "Lägre grupper belönas för sena tider när policyn är aktiv.", HardOrSoft.SOFT, Unit.PER_MATCH,
                Direction.REWARD);
        add(ConstraintKeys.COACH_PREFERRED_TIME_SLOT, "Tränarens önskade tid",
                "Belöna en tränare som hamnar på en tid tränaren föredrar.", HardOrSoft.SOFT, Unit.PER_MATCH,
                Direction.REWARD);

        // --- fan-out parent with no code of its own (ConstraintKeys.COMPLEMENTS_OF) ---
        addFanOutParent(ConstraintKeys.LATE_TIME_FOR_LOWER_GROUPS, "Sen tid för lägre grupper",
                "Lägre grupper prioriteras till sena tider om föreningen vill det (styr lateTimeTopGroups/lateTimeBottomGroups).",
                HardOrSoft.SOFT, Unit.PER_MATCH);
    }

    private ConstraintMetadata() {
    }

    /** Never returns null: an unknown key gets a synthetic fallback {@link Meta} (key as its own
     * label) so a message template can never NPE on a constraint this registry hasn't caught up
     * with yet — logged as a gap, not a crash, since explanations must never look like a black box
     * even when incomplete. */
    public static Meta of(String key) {
        Meta meta = BY_KEY.get(key);
        if (meta != null) {
            return meta;
        }
        return new Meta(key, key, "Okänd constraint: " + key, HardOrSoft.SOFT, Unit.PER_MATCH, Direction.PENALIZE, false, true);
    }

    public static boolean isKnown(String key) {
        return BY_KEY.containsKey(key);
    }

    public static Map<String, Meta> all() {
        return Map.copyOf(BY_KEY);
    }
}
