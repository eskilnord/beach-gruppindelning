package se.klubb.groupplanner.importer;

/**
 * The set of column-mapping targets the import wizard supports (spec §8.4, extended per
 * docs/plan.md's red-team correction with {@code coachName}/{@code isCoach} for coach import, spec
 * §13.1). {@link #CUSTOM_FIELD} carries an extra field key (see {@link ColumnMapping}) so the
 * wizard is generic - a council-created custom field behaves exactly like a standard one, no
 * hardcoded column names anywhere (CLAUDE.md).
 */
public enum MappingTargetKind {
    FIRST_NAME("firstName"),
    LAST_NAME("lastName"),
    DISPLAY_NAME("displayName"),
    EMAIL("email"),
    PHONE("phone"),
    EXTERNAL_ID("externalId"),
    RANKING_POINTS("rankingPoints"),
    PREVIOUS_GROUP_NAME("previousGroupName"),
    PREVIOUS_GROUP_LEVEL("previousGroupLevel"),
    MANUAL_LEVEL_SCORE("manualLevelScore"),
    COMMENT("comment"),
    INTERNAL_NOTE("internalNote"),
    /** Free-text coach wish carried by a participant row (docs/plan.md: "rows can carry a coach wish"). */
    COACH_NAME("coachName"),
    /** Marks the row as importing a coach rather than (or in addition to) a participant. */
    IS_COACH("isCoach"),
    CUSTOM_FIELD("customField"),
    IGNORE("ignore");

    private final String wireName;

    MappingTargetKind(String wireName) {
        this.wireName = wireName;
    }

    public String wireName() {
        return wireName;
    }

    public static MappingTargetKind fromWireName(String wireName) {
        for (MappingTargetKind kind : values()) {
            if (kind.wireName.equals(wireName)) {
                return kind;
            }
        }
        throw new IllegalArgumentException("Unknown mapping target: " + wireName);
    }
}
