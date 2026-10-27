package racehorse.backend.withdrawal;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/** Chỉ nói chuyện với bảng HORSE_WITHDRAWAL (V15), không chứa logic nghiệp vụ. */
@Repository
public class WithdrawalRepository {

    private final JdbcClient jdbc;

    public WithdrawalRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /* LEFT JOIN cho người duyệt: yêu cầu đang chờ thì chưa có ai duyệt. */
    private static final String SELECT_ROW = """
            SELECT w.withdrawal_id, w.horse_id, h.horse_name, h.registration_code, h.stall_no,
                   h.activity_status,
                   w.requested_by, q.full_name AS requester_name,
                   w.reason, w.status, w.created_at,
                   w.reviewed_by, r.full_name AS reviewer_name, w.reviewed_at, w.review_note,
                   w.released_stall, w.cancelled_sessions, w.ended_plans,
                   (SELECT COUNT(*) FROM TRAINING_SESSION s
                     WHERE s.horse_id = w.horse_id AND s.status = 'SCHEDULED') AS scheduled_sessions,
                   (SELECT COUNT(*) FROM TRAINING_PLAN p
                     WHERE p.horse_id = w.horse_id AND p.status <> 'COMPLETED') AS open_plans
            FROM HORSE_WITHDRAWAL w
            JOIN HORSE h    ON h.horse_id = w.horse_id
            JOIN APP_USER q ON q.user_id  = w.requested_by
            LEFT JOIN APP_USER r ON r.user_id = w.reviewed_by
            """;

    private static WithdrawalRow map(ResultSet rs, int rowNum) throws SQLException {
        return new WithdrawalRow(
                rs.getInt("withdrawal_id"),
                rs.getInt("horse_id"), rs.getString("horse_name"),
                rs.getString("registration_code"), rs.getString("stall_no"),
                rs.getString("activity_status"),
                rs.getInt("requested_by"), rs.getString("requester_name"),
                rs.getString("reason"),
                rs.getString("status"),
                rs.getObject("created_at", LocalDateTime.class),
                rs.getObject("reviewed_by", Integer.class), rs.getString("reviewer_name"),
                rs.getObject("reviewed_at", LocalDateTime.class), rs.getString("review_note"),
                rs.getString("released_stall"),
                rs.getObject("cancelled_sessions", Integer.class),
                rs.getObject("ended_plans", Integer.class),
                rs.getInt("scheduled_sessions"), rs.getInt("open_plans"));
    }

    public Optional<WithdrawalRow> findById(int withdrawalId) {
        return jdbc.sql(SELECT_ROW + " WHERE w.withdrawal_id = :id")
                .param("id", withdrawalId)
                .query(WithdrawalRepository::map)
                .optional();
    }

    /**
     * ownerId lọc theo chủ ngựa (vai trò HORSE_OWNER), horseId lọc theo một
     * con ngựa. Cả hai tuỳ chọn. Đang chờ lên trước, trong đó mới nhất trước.
     */
    public List<WithdrawalRow> find(Integer ownerId, Integer horseId) {
        StringBuilder sql = new StringBuilder(SELECT_ROW).append(" WHERE 1 = 1");
        if (ownerId != null) sql.append(" AND h.owner_id = :ownerId");
        if (horseId != null) sql.append(" AND w.horse_id = :horseId");
        sql.append(" ORDER BY CASE WHEN w.status = 'PENDING' THEN 0 ELSE 1 END, w.created_at DESC");

        JdbcClient.StatementSpec spec = jdbc.sql(sql.toString());
        if (ownerId != null) spec = spec.param("ownerId", ownerId);
        if (horseId != null) spec = spec.param("horseId", horseId);
        return spec.query(WithdrawalRepository::map).list();
    }

    public boolean hasOpenRequest(int horseId) {
        return jdbc.sql("""
                SELECT COUNT(*) FROM HORSE_WITHDRAWAL
                WHERE horse_id = :horseId AND status = 'PENDING'
                """)
                .param("horseId", horseId)
                .query(Integer.class).single() > 0;
    }

    public int insert(int horseId, int requestedBy, String reason) {
        return jdbc.sql("""
                INSERT INTO HORSE_WITHDRAWAL (horse_id, requested_by, reason)
                OUTPUT INSERTED.withdrawal_id
                VALUES (:horseId, :requestedBy, :reason)
                """)
                .param("horseId", horseId)
                .param("requestedBy", requestedBy)
                .param("reason", reason)
                .query(Integer.class)
                .single();
    }

    /**
     * Đóng yêu cầu (REJECTED hoặc CANCELLED), CHỈ khi nó vẫn đang chờ.
     * Điều kiện nằm trong câu UPDATE để hai người bấm cùng lúc không cùng thắng.
     *
     * @return 1 nếu thắng, 0 nếu người khác đã xử lý.
     */
    public int close(int withdrawalId, String status, int reviewerId, String note) {
        return jdbc.sql("""
                UPDATE HORSE_WITHDRAWAL
                SET status = :status, reviewed_by = :reviewer,
                    reviewed_at = SYSUTCDATETIME(), review_note = :note
                WHERE withdrawal_id = :id AND status = 'PENDING'
                """)
                .param("status", status)
                .param("reviewer", reviewerId)
                .param("note", note)
                .param("id", withdrawalId)
                .update();
    }

    /** Duyệt, kèm những gì việc duyệt đã làm. Cùng điều kiện PENDING như close(). */
    public int markApproved(int withdrawalId, int reviewerId, String note,
                            String releasedStall, int cancelledSessions, int endedPlans) {
        return jdbc.sql("""
                UPDATE HORSE_WITHDRAWAL
                SET status = 'APPROVED', reviewed_by = :reviewer,
                    reviewed_at = SYSUTCDATETIME(), review_note = :note,
                    released_stall = :stall,
                    cancelled_sessions = :sessions, ended_plans = :plans
                WHERE withdrawal_id = :id AND status = 'PENDING'
                """)
                .param("reviewer", reviewerId)
                .param("note", note)
                .param("stall", releasedStall)
                .param("sessions", cancelledSessions)
                .param("plans", endedPlans)
                .param("id", withdrawalId)
                .update();
    }
}
