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
        int horseCount,
        /** V37: Head Trainer phụ trách Groom này (chỉ có ở vai trò GROOM). */
        Integer trainerId,
        String trainerName,
        /** V37: Số Groom đang ACTIVE thuộc quyền quản lý của Head Trainer này (tối đa 3). */
        int groomCount,
        /** V37: Số ngựa đang thuộc quyền quản lý của Head Trainer này. */
        int managedHorseCount,
        /** V37: Hạn mức ngựa tối đa Club Manager cấp cho Head Trainer này. */
        Integer maxHorses
) {
    public StaffRow(
            int userId, String username, String fullName, String email, String phone,
            String roleName, String roleLabel, String status, String avatarUrl,
            LocalDateTime createdAt, int horseCount) {
        this(userId, username, fullName, email, phone, roleName, roleLabel, status,
                avatarUrl, createdAt, horseCount, null, null, 0, 0, null);
    }
}
