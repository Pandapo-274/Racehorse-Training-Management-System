package racehorse.backend.staff;

import java.time.LocalDateTime;

/**
 * One account, as the Staff screen sees it.
 *
 * There is NO password_hash here. This record is serialised straight to JSON,
 * so every field on it is a field the client can read - and a password hash
 * has no reason to leave the database, not even for a club manager. Its
 * absence is deliberate, not an oversight.
 *
 * roleName is the code (CLUB_MANAGER...), roleLabel is the text to display.
 * Both are returned because the screen groups by code but shows the label to
 * the reader; letting the frontend translate codes into labels would mean two
 * places keeping the same mapping, and they drift.
 */
public record StaffRow(
        int userId,
        String username,
        String fullName,
        String email,
        String phone,
        String roleName,
        String roleLabel,
        String status,
        String avatarUrl,
        LocalDateTime createdAt,
        /** Horses currently registered to this account. Non-zero only for HORSE_OWNER. */
        int horseCount
) {}
