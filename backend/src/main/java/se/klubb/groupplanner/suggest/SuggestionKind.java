package se.klubb.groupplanner.suggest;

/**
 * The 10 kinds of "Tolkningsförslag" (WP2) {@link CommentSuggestionParser} can propose from one
 * participant's free-text {@code imported_comment}, and the structured field/flag each one maps
 * onto if the council clicks "Lägg till" — see {@link #fieldKey()}/{@link #isFlagKind()} and
 * {@link CommentSuggestionService}'s class javadoc for the full privacy contract.
 */
public enum SuggestionKind {

    /** "spela med"/"samma grupp som" -&gt; {@code playWith} (SAME_GROUP SOFT). */
    PLAY_WITH("playWith"),
    /** "måste spela med"/"endast om ... med" -&gt; {@code mustPlayWith} (SAME_GROUP HARD). Only ever
     *  emitted for an EXPLICIT "måste"/"endast om" cue — never inferred from plain enthusiasm. */
    MUST_PLAY_WITH("mustPlayWith"),
    /** "inte (tillsammans) med"/"ej samma grupp som" -&gt; {@code avoidPlayWith} (DIFFERENT_GROUP SOFT). */
    AVOID_PLAY_WITH("avoidPlayWith"),
    /** "vill ha ... som tränare" -&gt; {@code wantsCoach} (COACH_PREFERENCE SOFT). */
    COACH_WISH("wantsCoach"),
    /** "inte ... som tränare" -&gt; {@code cannotHaveCoach} (COACH_FORBIDDEN HARD). */
    COACH_AVOID("cannotHaveCoach"),
    /** "kan inte"/"kan ej" + weekday/time -&gt; {@code cannotTimes} (TIME_AVAILABILITY HARD). Only
     *  emitted when the weekday/time text resolves to at least one real plan {@code TimeSlot}. */
    TIME_CANNOT("cannotTimes"),
    /** "föredrar"/"helst på"/"passar bäst" + weekday/time -&gt; {@code preferTimes} (TIME_PREFERENCE SOFT). */
    TIME_PREFER("preferTimes"),
    /** "ny i klubben"/"nybörjare"/"känner ingen" -&gt; {@code newToClub} boolean flag = true. */
    NEW_TO_CLUB("newToClub"),
    /** "gå ner/upp en nivå"/"lättare grupp"/"svårare grupp"/"mer tävlingsinriktad" -&gt; NEVER a level
     *  number guess; only ever raises {@code participant_profile.manual_review_flag}. */
    LEVEL_CHANGE(null),
    /** "skada"/"ont i"/"rehab"/"opererad" -&gt; raises {@code manual_review_flag} for a human to read
     *  the actual comment - never interpreted further. */
    INJURY_NOTE(null);

    private final String fieldKey;

    SuggestionKind(String fieldKey) {
        this.fieldKey = fieldKey;
    }

    /** The {@code custom_field_value} field key this suggestion writes to via {@code
     *  FieldValueService.putValues} when applied — {@code null} for the two "flag" kinds ({@link
     *  #LEVEL_CHANGE}/{@link #INJURY_NOTE}), which set {@code participant_profile.manual_review_flag}
     *  via the participant PATCH endpoint instead. {@link #NEW_TO_CLUB} also returns a field key
     *  ({@code newToClub}) since that standard field IS a {@code CUSTOM}-storage boolean field. */
    public String fieldKey() {
        return fieldKey;
    }

    /** {@code true} for the two kinds that never touch a field value and only ever raise {@code
     *  manualReviewFlag} — the frontend must never let these apply a target id. */
    public boolean isFlagKind() {
        return this == LEVEL_CHANGE || this == INJURY_NOTE;
    }

    /** {@code true} for kinds whose target is a set of participant ids (as opposed to coach ids or
     *  time slot ids or no target at all). */
    public boolean targetsParticipants() {
        return this == PLAY_WITH || this == MUST_PLAY_WITH || this == AVOID_PLAY_WITH;
    }

    /** {@code true} for kinds whose target is a coach id. */
    public boolean targetsCoaches() {
        return this == COACH_WISH || this == COACH_AVOID;
    }

    /** {@code true} for kinds whose target is one or more {@code time_slot} ids. */
    public boolean targetsTimeSlots() {
        return this == TIME_CANNOT || this == TIME_PREFER;
    }
}
