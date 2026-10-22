package racehorse.backend.staff;

import java.util.List;

/**
 * One role and the accounts that hold it.
 *
 * The backend groups rather than returning a flat list for the frontend to
 * group, because two things only the backend knows in full:
 *
 *  - The LABEL of a role lives in ROLE.description. Grouping on the frontend
 *    means reading the label off an arbitrary member, and an EMPTY group has
 *    no member to read it from - the screen then shows the raw code
 *    "VETERINARIAN" among the proper labels.
 *  - The ORDER of roles. It used to be declared twice (a CASE in SQL and a
 *    ROLE_ORDER array in JS), so one day the two would disagree and nobody
 *    would know which was right.
 *
 * An empty members list is a valid and meaningful state: "no veterinarians
 * yet" is something a staff screen needs to show.
 */
public record StaffGroup(
        String roleName,
        String roleLabel,
        List<StaffRow> members
) {}
