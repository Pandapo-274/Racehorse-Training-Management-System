package racehorse.backend.repository;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import racehorse.backend.entity.WithdrawalRow;
import racehorse.backend.service.RaceService;
import racehorse.backend.service.WithdrawalService;

/** Chỉ nói chuyện với bảng HORSE_WITHDRAWAL (V15), không chứa logic nghiệp vụ. */
@Repository
public class WithdrawalRepository {

    private final JdbcClient jdbc;

    public WithdrawalRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /* LEFT JOIN cho người duyệt: yêu cầu đang chờ thì chưa có ai duyệt. */
    // Hai subquery đếm số buổi SCHEDULED và giáo án chưa COMPLETED ngay lúc đọc: Manager biết
    // trước bấm duyệt sẽ huỷ bao nhiêu thứ.
    private static final String SELECT_ROW = """
            SELECT w.withdrawal_id, w.horse_id, h.horse_name, h.registration_code, h.stall_no,
                   h.activity_status,
                   w.requested_by, q.full_name AS requester_name,
                   w.reason, w.status, w.created_at,
                   w.reviewed_by, r.full_name AS reviewer_name, w.reviewed_at, w.review_note,
                   w.released_stall, w.cancelled_sessions, w.ended_plans,
                   w.leave_on, w.notice_until, w.refund_months, w.refund_amount, w.contract_id, w.by_club,
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
                rs.getInt("scheduled_sessions"), rs.getInt("open_plans"),
                rs.getObject("leave_on", LocalDate.class), rs.getObject("notice_until", LocalDate.class),
                rs.getObject("refund_months", Integer.class),
                rs.getBigDecimal("refund_amount") == null ? null : rs.getBigDecimal("refund_amount").longValue(),
                rs.getObject("contract_id", Integer.class), rs.getBoolean("by_club"));
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

        // Gán tham số có điều kiện: chỉ param(...) những tham số thực sự xuất hiện trong câu SQL.
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

    public int insert(int horseId, int requestedBy, String reason, LocalDate leaveOn, boolean byClub) {
        return jdbc.sql("""
                INSERT INTO HORSE_WITHDRAWAL (horse_id, requested_by, reason, leave_on, by_club)
                OUTPUT INSERTED.withdrawal_id
                VALUES (:horseId, :requestedBy, :reason, :leaveOn, :byClub)
                """)
                .param("horseId", horseId)
                .param("requestedBy", requestedBy)
                .param("reason", reason)
                .param("leaveOn", leaveOn)
                .param("byClub", byClub)
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

    /* ---------- Những việc của con ngựa phải dừng lại khi nó rời học viện ----------
       Đặt ở đây chứ không trong CareRepository / RaceRepository: đây là hệ quả
       của việc rút ngựa, chỉ WithdrawalService.approve gọi tới. */

    /** Một việc chăm sóc chưa làm: ai phụ trách, ngày nào. */
    public record PendingTask(int taskId, int assignedTo, LocalDate taskDate) {
    }

    /** Việc chăm sóc chưa làm của con ngựa, từ ngày from trở đi. */
    public List<PendingTask> findPendingTasks(int horseId, LocalDate from) {
        return jdbc.sql("""
                SELECT task_id, assigned_to, task_date FROM DAILY_TASK
                WHERE horse_id = :horseId AND is_completed = 0 AND task_date >= :from
                ORDER BY task_date
                """)
                .param("horseId", horseId)
                .param("from", from)
                .query((rs, n) -> new PendingTask(rs.getInt("task_id"), rs.getInt("assigned_to"),
                        rs.getObject("task_date", LocalDate.class)))
                .list();
    }

    /**
     * Xoá những việc đó. DAILY_TASK không có trạng thái "huỷ", và việc chưa làm
     * cho một con ngựa đã đi thì không bao giờ làm nữa. Việc đã làm (lịch sử)
     * và việc của những ngày trước giữ nguyên.
     */
    public int deletePendingTasks(int horseId, LocalDate from) {
        return jdbc.sql("""
                DELETE FROM DAILY_TASK
                WHERE horse_id = :horseId AND is_completed = 0 AND task_date >= :from
                """)
                .param("horseId", horseId)
                .param("from", from)
                .update();
    }

    /**
     * Rút khỏi các giải chưa đua (chưa có kết quả, ngày đua từ from trở đi), giống
     * RaceService.scratch: result = SCRATCHED, xoá tiền thưởng. Since V23 the
     * reason goes to its own column and the note is left as it was.
     */
    public int scratchFutureRaces(int horseId, LocalDate from, String reason) {
        return jdbc.sql("""
                UPDATE RACE_REGISTRATION
                SET result = 'SCRATCHED', prize_money = NULL, scratch_reason = LEFT(:reason, 500)
                WHERE horse_id = :horseId AND result IS NULL AND race_date >= :from
                """)
                .param("reason", reason)
                .param("horseId", horseId)
                .param("from", from)
                .update();
    }

    /** Duyệt, kèm những gì việc duyệt đã làm. Cùng điều kiện PENDING như close(). */
    public int markApproved(int withdrawalId, int reviewerId, String note,
                            String releasedStall, int cancelledSessions, int endedPlans,
                            LocalDate noticeUntil, int refundMonths, long refundAmount, Integer contractId) {
        return jdbc.sql("""
                UPDATE HORSE_WITHDRAWAL
                SET status = 'APPROVED', reviewed_by = :reviewer,
                    reviewed_at = SYSUTCDATETIME(), review_note = :note,
                    released_stall = :stall,
                    cancelled_sessions = :sessions, ended_plans = :plans,
                    notice_until = :notice, refund_months = :refundMonths,
                    refund_amount = :refund, contract_id = :contract
                WHERE withdrawal_id = :id AND status = 'PENDING'
                """)
                .param("notice", noticeUntil)
                .param("refundMonths", refundMonths)
                .param("refund", java.math.BigDecimal.valueOf(refundAmount))
                .param("contract", contractId)
                .param("reviewer", reviewerId)
                .param("note", note)
                .param("stall", releasedStall)
                .param("sessions", cancelledSessions)
                .param("plans", endedPlans)
                .param("id", withdrawalId)
                .update();
    }

    /* ---------- V62: what the manager decides on ---------- */

    /** An entry still to be raced, with its fee line (V36) if one was charged. */
    public record UpcomingEntry(int registrationId, String raceName, LocalDate raceDate, LocalDate entryDeadline,
                                Integer feeExpenseId, boolean feePaid) {}

    public List<UpcomingEntry> findUpcomingEntries(int horseId, LocalDate from) {
        return jdbc.sql("""
                SELECT r.registration_id, r.race_name, r.race_date, rc.entry_deadline,
                       CASE WHEN e.cancelled_at IS NULL THEN r.fee_expense_id END AS fee_expense_id,
                       CASE WHEN e.paid_at IS NOT NULL THEN 1 ELSE 0 END AS fee_paid
                FROM RACE_REGISTRATION r
                LEFT JOIN RACE rc ON rc.race_id = r.race_id
                LEFT JOIN EXPENSE_LOG e ON e.expense_id = r.fee_expense_id
                WHERE r.horse_id = :horseId AND r.result IS NULL AND r.race_date >= :from
                ORDER BY r.race_date
                """)
                .param("horseId", horseId).param("from", from)
                .query((rs, n) -> new UpcomingEntry(rs.getInt("registration_id"), rs.getString("race_name"),
                        rs.getObject("race_date", LocalDate.class), rs.getObject("entry_deadline", LocalDate.class),
                        rs.getObject("fee_expense_id", Integer.class), rs.getInt("fee_paid") == 1))
                .list();
    }

    public record Costs(int unpaidCount, long unpaidAmount, int disputedCount) {}

    /** Costs billed to the owner for this horse and not paid yet (same lines as the Payments page). */
    public Costs unpaidCosts(int horseId) {
        return jdbc.sql("""
                SELECT ISNULL(SUM(CASE WHEN e.status IN ('BILLED', 'RESOLVED') THEN 1 ELSE 0 END), 0) AS unpaid,
                       ISNULL(SUM(CASE WHEN e.status IN ('BILLED', 'RESOLVED') THEN e.amount ELSE 0 END), 0) AS amount,
                       ISNULL(SUM(CASE WHEN e.status = 'DISPUTED' THEN 1 ELSE 0 END), 0) AS disputed
                FROM EXPENSE_LOG e
                WHERE e.horse_id = :horseId AND e.cancelled_at IS NULL AND e.paid_at IS NULL
                  AND e.category IN ('MEDICAL', 'OTHER')
                """)
                .param("horseId", horseId)
                .query((rs, n) -> new Costs(rs.getInt("unpaid"), rs.getBigDecimal("amount").longValue(),
                        rs.getInt("disputed")))
                .single();
    }

    /** The reason of a veterinary training lock still in force, if any. */
    public Optional<String> activeLock(int horseId) {
        return jdbc.sql("""
                SELECT TOP 1 reason FROM TRAINING_LOCK
                WHERE horse_id = :horseId AND released_at IS NULL
                ORDER BY issued_at DESC
                """)
                .param("horseId", horseId).query(String.class).optional();
    }

    public record Activity(int scheduledSessions, int openPlans) {}

    public Activity activity(int horseId) {
        return jdbc.sql("""
                SELECT (SELECT COUNT(*) FROM TRAINING_SESSION s
                         WHERE s.horse_id = :horseId AND s.status = 'SCHEDULED') AS scheduled_sessions,
                       (SELECT COUNT(*) FROM TRAINING_PLAN p
                         WHERE p.horse_id = :horseId AND p.status <> 'COMPLETED') AS open_plans
                """)
                .param("horseId", horseId)
                .query((rs, n) -> new Activity(rs.getInt("scheduled_sessions"), rs.getInt("open_plans")))
                .single();
    }
}
