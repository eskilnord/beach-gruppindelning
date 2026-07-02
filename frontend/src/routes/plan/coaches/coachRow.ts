import type { CoachProfile } from "../../../api/types";

/** A coach profile joined with its person's display name (spec §19.7 Tränarvy) - the row shape
 *  backing both the coach table and the detail drawer, mirroring ParticipantRow. */
export interface CoachRow extends CoachProfile {
  name: string;
}
