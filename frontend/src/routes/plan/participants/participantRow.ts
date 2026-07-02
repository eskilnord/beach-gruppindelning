import type { ParticipantProfile } from "../../../api/types";

/** A participant profile joined with its person's display name (spec §19.4 Deltagarvy) - the row
 *  shape backing both the AG Grid and the detail drawer. */
export interface ParticipantRow extends ParticipantProfile {
  name: string;
}
