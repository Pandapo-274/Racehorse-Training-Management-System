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

    /** Names of the horses a Head Trainer is in charge of (any activity), for the "left without a trainer" notice. */
    /** A session running right now on one of a Head Trainer's horses, and the groom running it. */
    public record RunningSession(int sessionId, int groomId, String horseName) {}

    public List<RunningSession> runningSessionsOfTrainer(int trainerId) {
        return jdbc.sql("""
                SELECT s.session_id, s.assigned_to, h.horse_name
                FROM TRAINING_SESSION s JOIN HORSE h ON h.horse_id = s.horse_id
                WHERE h.trainer_id = :id AND s.status = 'RUNNING'
                ORDER BY h.horse_name
                """)
                .param("id", trainerId)
                .query((rs, n) -> new RunningSession(rs.getInt("session_id"), rs.getInt("assigned_to"),
                        rs.getString("horse_name")))
                .list();
    }

    /** A horse in a Head Trainer's charge, with its owner (who is told when the trainer changes). */
    public record TrainerHorse(int horseId, String horseName, int ownerId) {}

    public List<TrainerHorse> horsesOfTrainer(int trainerId) {
        return jdbc.sql("SELECT horse_id, horse_name, owner_id FROM HORSE WHERE trainer_id = :id ORDER BY horse_name")
                .param("id", trainerId)
                .query((rs, n) -> new TrainerHorse(rs.getInt("horse_id"), rs.getString("horse_name"), rs.getInt("owner_id")))
                .list();
    }

    /** Takes a Head Trainer off all their horses (they left the role or were suspended). */
    public int releaseHorsesOfTrainer(int trainerId) {
        return jdbc.sql("UPDATE HORSE SET trainer_id = NULL WHERE trainer_id = :id")
                .param("id", trainerId)
                .update();
    }

    /** Ids of the grooms on a Head Trainer's team, to tell them before the team is dissolved. */
    public List<Integer> groomIdsOfTrainer(int trainerId) {
        return jdbc.sql("SELECT user_id FROM APP_USER WHERE trainer_id = :id")
                .param("id", trainerId)
                .query(Integer.class)
                .list();
    }

    /* ---------- A Head Trainer's team, shown when the manager clicks "Grooms · Horses" ---------- */

    public record TeamGroom(int userId, String username, String fullName, String phone,
                            String status, String avatarUrl) {}

    public record TeamHorse(int horseId, String horseName, String registrationCode, String horseGender,
                            String status, String stallNo, String photoUrl) {}

    /** Grooms assigned to the trainer; ACTIVE first (only those count toward the 3-groom limit). */
    public List<TeamGroom> groomsOfTrainer(int trainerId) {
        return jdbc.sql("""
                SELECT g.user_id, g.username, g.full_name, g.phone, g.status, g.avatar_url
                FROM APP_USER g
                JOIN ROLE r ON r.role_id = g.role_id
                WHERE g.trainer_id = :id AND r.role_name = 'GROOM'
                ORDER BY CASE g.status WHEN 'ACTIVE' THEN 0 ELSE 1 END, g.full_name
                """)
                .param("id", trainerId)
                .query((rs, n) -> new TeamGroom(
                        rs.getInt("user_id"), rs.getString("username"), rs.getString("full_name"),
                        rs.getString("phone"), rs.getString("status"), rs.getString("avatar_url")))
                .list();
    }

    /** Club horses at the club in the trainer's charge: the same set as managed_horse_count. */
    public List<TeamHorse> activeHorsesOfTrainer(int trainerId) {
        return jdbc.sql("""
                SELECT horse_id, horse_name, registration_code, horse_gender, status, stall_no, photo_url
                FROM HORSE
                WHERE trainer_id = :id AND activity_status = 'ACTIVE'
                ORDER BY horse_name
                """)
                .param("id", trainerId)
                .query((rs, n) -> new TeamHorse(
                        rs.getInt("horse_id"), rs.getString("horse_name"), rs.getString("registration_code"),
                        rs.getString("horse_gender"), rs.getString("status"), rs.getString("stall_no"),
                        rs.getString("photo_url")))
                .list();
    }

    /** Sessions a groom has started and not finished: only a Head Trainer can close them now. */
    public int countRunningSessions(int groomId) {
        return jdbc.sql("SELECT COUNT(*) FROM TRAINING_SESSION WHERE assigned_to = :id AND status = 'RUNNING'")
                .param("id", groomId)
                .query(Integer.class)
                .single();
    }

    /** A groom who leaves cannot finish a running session: it is closed now and goes to review. */
    public int stopRunningSessions(int groomId, String reason) {
        return jdbc.sql("""
                UPDATE TRAINING_SESSION
                SET status = 'STOPPED_EARLY',
                    ended_at = SYSUTCDATETIME(),
                    early_stop_reason = :reason,
                    review_status = 'PENDING'
                WHERE assigned_to = :id AND status = 'RUNNING'
                """)
                .param("reason", reason)
                .param("id", groomId)
                .update();
    }

    /** Scheduled sessions from today on move to another groom. */
    public int handOverSessions(int fromGroom, int toGroom) {
        return jdbc.sql("""
                UPDATE TRAINING_SESSION SET assigned_to = :to
                WHERE assigned_to = :from AND status = 'SCHEDULED'
                  AND session_date >= CAST(GETDATE() AS DATE)
                """)
                .param("to", toGroom)
                .param("from", fromGroom)
                .update();
    }

    /** Open care tasks (feeding, stall cleaning...) from today on, for horses still at the club. */
    public int handOverCareTasks(int fromGroom, int toGroom) {
        return jdbc.sql("""
                UPDATE DAILY_TASK SET assigned_to = :to
                WHERE assigned_to = :from AND is_completed = 0
                  AND task_date >= CAST(GETDATE() AS DATE)
                  AND horse_id IN (SELECT horse_id FROM HORSE WHERE activity_status = 'ACTIVE')
                """)
                .param("to", toGroom)
                .param("from", fromGroom)
                .update();
    }

    /**
     * Who can take over a leaving person's work: active accounts of the same role,
     * not that person. For a groom with a team, only grooms of that same Head
     * Trainer (sessions may only go to a groom of the horse's trainer).
     */
    public List<StaffRow> handoverCandidates(String roleName, int excludeId, Integer trainerId) {
        String sameTeam = trainerId == null ? "" : " AND u.trainer_id = :trainer";
        var q = jdbc.sql(SELECT_STAFF
                        + " WHERE r.role_name = :role AND u.status = 'ACTIVE' AND u.user_id <> :id"
                        + sameTeam + " ORDER BY u.full_name")
                .param("role", roleName)
                .param("id", excludeId);
        if (trainerId != null) q = q.param("trainer", trainerId);
        return q.query(StaffRepository::map).list();
    }

    // ---- Head Trainer's open work (everything hangs on HORSE.trainer_id) ----

    public int countRunningSessionsOfTrainer(int trainerId) {
        return count("""
                SELECT COUNT(*) FROM TRAINING_SESSION s JOIN HORSE h ON h.horse_id = s.horse_id
                WHERE h.trainer_id = :id AND s.status = 'RUNNING'
                """, trainerId);
    }

    /** Finished / stopped sessions still waiting for the Head Trainer's review. */
    public int countSessionsToReviewOfTrainer(int trainerId) {
        return count("""
                SELECT COUNT(*) FROM TRAINING_SESSION s JOIN HORSE h ON h.horse_id = s.horse_id
                WHERE h.trainer_id = :id AND s.review_status = 'PENDING'
                """, trainerId);
    }

    public int countOpenPlansOfTrainer(int trainerId) {
        return count("""
                SELECT COUNT(*) FROM TRAINING_PLAN p JOIN HORSE h ON h.horse_id = p.horse_id
                WHERE h.trainer_id = :id AND p.status IN ('DRAFT', 'ACTIVE', 'PAUSED')
                """, trainerId);
    }

    /** All horses of one Head Trainer go to another (plans, sessions and reviews follow the horse). */
    public int moveHorsesToTrainer(int fromTrainer, int toTrainer) {
        return jdbc.sql("UPDATE HORSE SET trainer_id = :to WHERE trainer_id = :from")
                .param("to", toTrainer)
                .param("from", fromTrainer)
                .update();
    }

    public int moveGroomsToTrainer(int fromTrainer, int toTrainer) {
        return jdbc.sql("UPDATE APP_USER SET trainer_id = :to WHERE trainer_id = :from")
                .param("to", toTrainer)
                .param("from", fromTrainer)
                .update();
    }

    // ---- Veterinarian's open work ----

    /** Incident cases this vet has taken (RECEIVED) and not examined yet. */
    public int countCasesOfVet(int vetId) {
        return count("SELECT COUNT(*) FROM INCIDENT_REPORT WHERE received_by = :id AND status = 'RECEIVED'", vetId);
    }

    public int handOverCases(int fromVet, int toVet) {
        return jdbc.sql("""
                UPDATE INCIDENT_REPORT SET received_by = :to, received_at = SYSUTCDATETIME()
                WHERE received_by = :from AND status = 'RECEIVED'
                """)
                .param("to", toVet)
                .param("from", fromVet)
                .update();
    }

    /** Nobody takes them over: the cases go back to the open queue every vet sees. */
    public int returnCasesToQueue(int vetId) {
        return jdbc.sql("""
                UPDATE INCIDENT_REPORT SET status = 'PENDING', received_by = NULL, received_at = NULL
                WHERE received_by = :id AND status = 'RECEIVED'
                """)
                .param("id", vetId)
                .update();
    }

    /** Vet calls nobody has answered yet (a shared queue, not tied to one vet). */
    public int countPendingVetCalls() {
        return jdbc.sql("SELECT COUNT(*) FROM VET_CALL WHERE status = 'PENDING'").query(Integer.class).single();
    }

    public int countActiveOfRole(String roleName, int excludeId) {
        return jdbc.sql("""
                SELECT COUNT(*) FROM APP_USER u JOIN ROLE r ON r.role_id = u.role_id
                WHERE r.role_name = :role AND u.status = 'ACTIVE' AND u.user_id <> :id
                """)
                .param("role", roleName)
                .param("id", excludeId)
                .query(Integer.class)
                .single();
    }

    private int count(String sql, int id) {
        return jdbc.sql(sql).param("id", id).query(Integer.class).single();
    }
}
