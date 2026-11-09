package racehorse.backend.staff;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/**
 * UC19 (read half) - every account in the system.
 *
 * This is the first time the system can read a LIST of users: until now
 * APP_USER was only ever looked up by one id, by one username, or counted.
 * The Staff screen needs the whole table, and because it does, the controller
 * locks it down to CLUB_MANAGER.
 */
@Repository
public class StaffRepository {

    private final JdbcClient jdbc;

    public StaffRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * ROLE.description is the display text of a role and already exists from
     * V1. Reading it here rather than keeping a code-to-label map in the
     * frontend: two copies of the same mapping eventually disagree, and the
     * ROLE table is the real one.
     *
     * horse_count uses a subquery instead of LEFT JOIN + GROUP BY. With a
     * GROUP BY, every other selected column has to be listed in it, so adding
     * a column to the SELECT later is a silent breakage waiting to happen.
     *
     * password_hash is NOT selected. That omission is deliberate - see
     * StaffRow.
     */
    private static final String SELECT_STAFF = """
            SELECT u.user_id, u.username, u.full_name, u.email, u.phone,
                   r.role_name, r.description AS role_label,
                   u.status, u.avatar_url, u.created_at,
                   (SELECT COUNT(*) FROM HORSE h WHERE h.owner_id = u.user_id) AS horse_count,
                   u.trainer_id,
                   tr.full_name AS trainer_name,
                   (SELECT COUNT(*) FROM APP_USER g
                    JOIN ROLE gr ON gr.role_id = g.role_id
                    WHERE g.trainer_id = u.user_id AND gr.role_name = 'GROOM' AND g.status = 'ACTIVE') AS groom_count,
                   (SELECT COUNT(*) FROM HORSE mh
                    WHERE mh.trainer_id = u.user_id AND mh.activity_status = 'ACTIVE') AS managed_horse_count,
                   u.max_horses
            FROM APP_USER u
            JOIN ROLE r ON r.role_id = u.role_id
            LEFT JOIN APP_USER tr ON tr.user_id = u.trainer_id
            """;

    private static StaffRow map(ResultSet rs, int rowNum) throws SQLException {
        return new StaffRow(
                rs.getInt("user_id"),
                rs.getString("username"),
                rs.getString("full_name"),
                rs.getString("email"),
                rs.getString("phone"),
                rs.getString("role_name"),
                rs.getString("role_label"),
                rs.getString("status"),
                rs.getString("avatar_url"),
                rs.getObject("created_at", LocalDateTime.class),
                rs.getInt("horse_count"),
                rs.getObject("trainer_id", Integer.class),
                rs.getString("trainer_name"),
                rs.getInt("groom_count"),
                rs.getInt("managed_horse_count"),
                rs.getObject("max_horses", Integer.class));
    }

    /**
     * Every role in the ROLE table, each with the accounts that hold it.
     *
     * This walks from ROLE to APP_USER, not the other way round. "Which roles
     * exist" is a question only the ROLE table answers in full; grouping a
     * list of accounts yields only the roles that CURRENTLY have someone, so
     * a role nobody holds would vanish from the screen instead of showing up
     * as empty.
     *
     * The order comes from a CASE rather than alphabetical sorting: the screen
     * groups by role, and a meaningful order (manager, trainer, vet, groom,
     * owner) reads better than whatever the alphabet produces. A role added
     * later falls into ELSE 5 and lands at the end rather than disappearing.
     *
     * SUSPENDED accounts are included: this is the staff-management screen,
     * and an account that is locked but invisible is one nobody can unlock.
     */
    public List<StaffGroup> findAllGrouped() {
        List<StaffRow> everyone = jdbc.sql(SELECT_STAFF + " ORDER BY u.full_name")
                .query(StaffRepository::map)
                .list();

        return jdbc.sql("""
                SELECT role_name, description
                FROM ROLE
                ORDER BY CASE role_name
                             WHEN 'CLUB_MANAGER' THEN 0
                             WHEN 'HEAD_TRAINER' THEN 1
                             WHEN 'VETERINARIAN' THEN 2
                             WHEN 'GROOM'        THEN 3
                             WHEN 'HORSE_OWNER'  THEN 4
                             ELSE 5
                         END,
                         role_name
                """)
                .query((rs, n) -> {
                    String roleName = rs.getString("role_name");
                    return new StaffGroup(
                            roleName,
                            rs.getString("description"),
                            everyone.stream().filter(u -> roleName.equals(u.roleName())).toList());
                })
                .list();
    }

    /** Một tài khoản theo id, cùng dạng với từng dòng của danh sách Staff. */
    public Optional<StaffRow> findRow(int userId) {
        return jdbc.sql(SELECT_STAFF + " WHERE u.user_id = :id")
                .param("id", userId)
                .query(StaffRepository::map)
                .optional();
    }

    /** Đổi vai trò: chỉ đổi role_id. Service đã kiểm vai trò đích hợp lệ. */
    public int updateRole(int userId, int roleId) {
        return jdbc.sql("UPDATE APP_USER SET role_id = :roleId WHERE user_id = :id")
                .param("roleId", roleId)
                .param("id", userId)
                .update();
    }

    /**
     * Số buổi tập đang giao cho người này mà chưa xong: đang chạy, hoặc đã lên
     * lịch từ hôm nay trở đi. Buổi SCHEDULED của ngày đã qua không tính: không
     * ai giao lại được một buổi trong quá khứ.
     */
    public int countOpenAssignments(int userId) {
        return jdbc.sql("""
                SELECT COUNT(*) FROM TRAINING_SESSION
                WHERE assigned_to = :id
                  AND (status = 'RUNNING'
                       OR (status = 'SCHEDULED' AND session_date >= CAST(GETDATE() AS DATE)))
                """)
                .param("id", userId)
                .query(Integer.class)
                .single();
    }

    /**
     * Việc chăm sóc (cho ăn, dọn chuồng...) chưa làm của người này từ hôm nay trở
     * đi. Groom bị khoá mà không ai giao lại thì sáng mai chuồng không ai dọn và
     * việc chỉ hiện ra khi đã thành MISSED.
     */
    public int countOpenCareTasks(int userId) {
        return jdbc.sql("""
                SELECT COUNT(*) FROM DAILY_TASK t JOIN HORSE h ON h.horse_id = t.horse_id
                WHERE t.assigned_to = :id AND t.is_completed = 0
                  AND t.task_date >= CAST(GETDATE() AS DATE) AND h.activity_status = 'ACTIVE'
                """)
                .param("id", userId)
                .query(Integer.class)
                .single();
    }

    /** Khoá / mở khoá: chỉ đổi cột status (CK_USER_status chỉ nhận ACTIVE, SUSPENDED). */
    public int updateStatus(int userId, String status) {
        return jdbc.sql("UPDATE APP_USER SET status = :status WHERE user_id = :id")
                .param("status", status)
                .param("id", userId)
                .update();
    }

    /** Manager corrects name, email or phone of an account (not a Club Manager's). */
    public int updateContact(int userId, String fullName, String email, String phone) {
        return jdbc.sql("""
                UPDATE APP_USER SET full_name = :name, email = :email, phone = :phone
                WHERE user_id = :id
                """)
                .param("name", fullName).param("email", email).param("phone", phone).param("id", userId)
                .update();
    }

    /** Another account already uses this email. */
    public boolean emailTakenByOther(String email, int userId) {
        return jdbc.sql("SELECT COUNT(*) FROM APP_USER WHERE email = :e AND user_id <> :id")
                .param("e", email).param("id", userId).query(Integer.class).single() > 0;
    }

    /** New temporary password: must be changed at next sign-in (V14), lock cleared (V39). */
    public int setTemporaryPassword(int userId, String hash) {
        return jdbc.sql("""
                UPDATE APP_USER SET password_hash = :h, must_change_password = 1,
                       failed_login_count = 0, locked_until = NULL
                WHERE user_id = :id
                """)
                .param("h", hash).param("id", userId).update();
    }

    /* ---------- An owner is suspended (Flow 1 #6) ---------- */

    /** Closes the owner's registration requests still waiting: nobody can answer for them now. */
    public int cancelOpenHorseRequests(int ownerId, int byUserId) {
        return jdbc.sql("""
                UPDATE HORSE_REQUEST
                SET status = 'CANCELLED', reviewed_by = :by, reviewed_at = SYSUTCDATETIME(),
                    review_note = N'Cancelled: the owner account was suspended'
                WHERE owner_id = :owner AND status = 'PENDING'
                """)
                .param("by", byUserId).param("owner", ownerId).update();
    }

    /** Training plans of the owner's horses waiting for the owner's approval (V34). */
    public int countPlansAwaitingOwner(int ownerId) {
        return jdbc.sql("""
                SELECT COUNT(*) FROM TRAINING_PLAN p JOIN HORSE h ON h.horse_id = p.horse_id
                WHERE h.owner_id = :owner AND p.status = 'PENDING_APPROVAL'
                """).param("owner", ownerId).query(Integer.class).single();
    }

    /** Race entries of the owner's horses waiting for the owner's approval (V28). */
    public int countRaceEntriesAwaitingOwner(int ownerId) {
        return jdbc.sql("""
                SELECT COUNT(*) FROM RACE_REGISTRATION r JOIN HORSE h ON h.horse_id = r.horse_id
                WHERE h.owner_id = :owner AND r.approval_status = 'PENDING_OWNER' AND r.result IS NULL
                """).param("owner", ownerId).query(Integer.class).single();
    }

    /** Club horses the owner still has at the club. */
    public int countActiveHorses(int ownerId) {
        return jdbc.sql("""
                SELECT COUNT(*) FROM HORSE
                WHERE owner_id = :owner AND record_type = 'ACADEMY' AND activity_status = 'ACTIVE'
                """).param("owner", ownerId).query(Integer.class).single();
    }

    /** V37: Đếm số Groom đang ACTIVE được gán cho một Head Trainer (trừ excludeUserId nếu đang cập nhật). */
    public int countActiveGroomsOfTrainer(int trainerId, Integer excludeUserId) {
        return jdbc.sql("""
                SELECT COUNT(*)
                FROM APP_USER g
                JOIN ROLE r ON r.role_id = g.role_id
                WHERE g.trainer_id = :trainerId
                  AND r.role_name = 'GROOM'
                  AND g.status = 'ACTIVE'
                  AND (:excludeId IS NULL OR g.user_id <> :excludeId)
                """)
                .param("trainerId", trainerId)
                .param("excludeId", excludeUserId)
                .query(Integer.class)
                .single();
    }

    /** V37: Gán hoặc gỡ Head Trainer phụ trách một Groom. */
    public int updateTrainer(int groomId, Integer trainerId) {
        return jdbc.sql("UPDATE APP_USER SET trainer_id = :trainerId WHERE user_id = :id")
                .param("trainerId", trainerId)
                .param("id", groomId)
                .update();
    }

    /** V37: Cập nhật hạn mức số ngựa tối đa cho một Head Trainer. */
    public int updateMaxHorses(int trainerId, int maxHorses) {
        return jdbc.sql("UPDATE APP_USER SET max_horses = :maxHorses WHERE user_id = :id")
                .param("maxHorses", maxHorses)
                .param("id", trainerId)
                .update();
    }

    /** V37: Khi một Head Trainer đổi vai trò sang vai trò khác, gỡ liên kết trainer_id của các Groom thuộc quyền. */
    public int detachGroomsFromTrainer(int trainerId) {
        return jdbc.sql("UPDATE APP_USER SET trainer_id = NULL WHERE trainer_id = :trainerId")
                .param("trainerId", trainerId)
                .update();
    }
}
