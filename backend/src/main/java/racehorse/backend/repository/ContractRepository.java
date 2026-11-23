package racehorse.backend.repository;

import java.math.BigDecimal;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Optional;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import racehorse.backend.entity.ContractRow;

/**
 * HORSE_CONTRACT (V49). Every status change is a conditional UPDATE
 * ("... WHERE status = 'SIGNED'") and callers read the row count: 0 means
 * someone else (another tab, the payOS webhook, the nightly job) moved the
 * contract first. Same pattern as HorseRequestRepository.markReviewed.
 */
@Repository
public class ContractRepository {

    private final JdbcClient jdbc;

    public ContractRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    private static final String SELECT_ROW = """
            SELECT c.contract_id, c.request_id, c.horse_id, c.owner_id, o.full_name AS owner_name,
                   c.renews_contract_id,
                   q.horse_name, q.record_type, q.breed, q.horse_gender, q.date_of_birth, q.color,
                   q.photo_url, q.weight, q.height_cm, q.surface, q.distance_pref, q.running_style,
                   q.preferred_trainer_id, pt.full_name AS preferred_trainer_name,
                   c.months, c.training_weeks, c.monthly_fee, c.discount_pct, c.total_amount,
                   c.planned_start, c.start_date, c.end_date, c.status,
                   sb.full_name AS sent_by_name, c.sent_at, c.respond_by,
                   c.signed_name, c.signed_at,
                   c.order_code, c.checkout_url, c.paid_at, c.paid_amount, c.payment_ref,
                   rb.full_name AS received_by_name,
                   h.registration_code, h.stall_no, h.horse_name AS current_horse_name,
                   c.terms_snapshot, c.terms_sha256, c.closed_note,
                   c.training_start, c.training_end
            FROM HORSE_CONTRACT c
            JOIN HORSE_REQUEST q ON q.request_id = c.request_id
            JOIN APP_USER o ON o.user_id = c.owner_id
            LEFT JOIN APP_USER pt ON pt.user_id = q.preferred_trainer_id
            LEFT JOIN APP_USER sb ON sb.user_id = c.sent_by
            LEFT JOIN APP_USER rb ON rb.user_id = c.received_by
            LEFT JOIN HORSE h ON h.horse_id = c.horse_id
            """;

    private static ContractRow map(ResultSet rs, int n) throws SQLException {
        LocalDate end = rs.getObject("end_date", LocalDate.class);
        String status = rs.getString("status");
        Long daysLeft = end != null && ("ACTIVE".equals(status) || "EXPIRED".equals(status))
                ? ChronoUnit.DAYS.between(LocalDate.now(), end) : null;
        BigDecimal paid = rs.getBigDecimal("paid_amount");
        String currentName = rs.getString("current_horse_name");
        return new ContractRow(
                rs.getInt("contract_id"), rs.getInt("request_id"),
                rs.getObject("horse_id", Integer.class),
                rs.getInt("owner_id"), rs.getString("owner_name"),
                rs.getObject("renews_contract_id", Integer.class),
                // The owner may have renamed the horse since registering; the live name wins.
                currentName != null ? currentName : rs.getString("horse_name"),
                rs.getString("record_type"), rs.getString("breed"), rs.getString("horse_gender"),
                rs.getObject("date_of_birth", LocalDate.class), rs.getString("color"),
                rs.getString("photo_url"),
                rs.getBigDecimal("weight"), rs.getBigDecimal("height_cm"),
                rs.getString("surface"), rs.getString("distance_pref"), rs.getString("running_style"),
                rs.getObject("preferred_trainer_id", Integer.class), rs.getString("preferred_trainer_name"),
                rs.getInt("months"), rs.getObject("training_weeks", Integer.class),
                rs.getBigDecimal("monthly_fee").longValue(), rs.getBigDecimal("discount_pct"),
                rs.getBigDecimal("total_amount").longValue(),
                rs.getObject("planned_start", LocalDate.class),
                rs.getObject("start_date", LocalDate.class), end,
                status,
                rs.getString("sent_by_name"), rs.getObject("sent_at", LocalDateTime.class),
                rs.getObject("respond_by", LocalDate.class),
                rs.getString("signed_name"), rs.getObject("signed_at", LocalDateTime.class),
                rs.getObject("order_code", Long.class), rs.getString("checkout_url"),
                rs.getObject("paid_at", LocalDateTime.class), paid == null ? null : paid.longValue(),
                rs.getString("payment_ref"),
                rs.getString("received_by_name"),
                rs.getString("registration_code"), rs.getString("stall_no"),
                rs.getString("terms_snapshot"), rs.getString("terms_sha256"),
                rs.getString("closed_note"),
                daysLeft,
                rs.getObject("training_start", LocalDate.class), rs.getObject("training_end", LocalDate.class));
    }

    /* ---------------- reads ---------------- */

    public Optional<ContractRow> findById(int contractId) {
        return jdbc.sql(SELECT_ROW + " WHERE c.contract_id = :id")
                .param("id", contractId).query(ContractRepository::map).optional();
    }

    public List<ContractRow> findByOwner(int ownerId) {
        return jdbc.sql(SELECT_ROW + " WHERE c.owner_id = :owner ORDER BY c.contract_id DESC")
                .param("owner", ownerId).query(ContractRepository::map).list();
    }

    /** Manager's list: things waiting on someone first, then the rest, newest first. */
    public List<ContractRow> findAll() {
        return jdbc.sql(SELECT_ROW + """
                 ORDER BY CASE c.status WHEN 'PAID' THEN 0 WHEN 'EXPIRED' THEN 1 WHEN 'SIGNED' THEN 2
                                        WHEN 'SENT' THEN 3 WHEN 'ACTIVE' THEN 4 ELSE 5 END,
                          c.contract_id DESC
                """)
                .query(ContractRepository::map).list();
    }

    public List<ContractRow> findByHorse(int horseId) {
        return jdbc.sql(SELECT_ROW + " WHERE c.horse_id = :horse ORDER BY c.contract_id DESC")
                .param("horse", horseId).query(ContractRepository::map).list();
    }

    public Optional<Integer> findIdByOrderCode(long orderCode) {
        return jdbc.sql("SELECT contract_id FROM HORSE_CONTRACT WHERE order_code = :code")
                .param("code", orderCode).query(Integer.class).optional();
    }

    /** The running stay of a horse (ACTIVE or EXPIRED), if any. */
    public Optional<ContractRow> findRunning(int horseId) {
        return jdbc.sql(SELECT_ROW + " WHERE c.horse_id = :horse AND c.status IN ('ACTIVE', 'EXPIRED')")
                .param("horse", horseId).query(ContractRepository::map).optional();
    }

    /** A renewal of this horse already waiting on the owner or on payment. */
    public boolean hasOpenRenewal(int horseId) {
        return jdbc.sql("""
                SELECT COUNT(*) FROM HORSE_CONTRACT
                WHERE horse_id = :horse AND status IN ('SENT', 'SIGNED', 'PAID')
                """)
                .param("horse", horseId).query(Integer.class).single() > 0;
    }

    /* ---------------- writes ---------------- */

    public record NewContract(int requestId, Integer horseId, int ownerId, Integer renewsContractId,
                              int months, Integer trainingWeeks, long monthlyFee, BigDecimal discountPct,
                              long totalAmount, LocalDate plannedStart, String terms, String termsSha256,
                              int sentBy, LocalDate respondBy,
                              LocalDate trainingStart, LocalDate trainingEnd) {}

    public int insert(NewContract c) {
        return jdbc.sql("""
                INSERT INTO HORSE_CONTRACT (request_id, horse_id, owner_id, renews_contract_id,
                        months, training_weeks, monthly_fee, discount_pct, total_amount, planned_start,
                        terms_snapshot, terms_sha256, status, sent_by, respond_by,
                        training_start, training_end)
                OUTPUT INSERTED.contract_id
                VALUES (:request, :horse, :owner, :renews, :months, :weeks, :fee, :discount, :total,
                        :planned, :terms, :sha, 'SENT', :sentBy, :respondBy,
                        :trainingStart, :trainingEnd)
                """)
                .param("request", c.requestId())
                .param("horse", c.horseId())
                .param("owner", c.ownerId())
                .param("renews", c.renewsContractId())
                .param("months", c.months())
                .param("weeks", c.trainingWeeks())
                .param("fee", BigDecimal.valueOf(c.monthlyFee()))
                .param("discount", c.discountPct())
                .param("total", BigDecimal.valueOf(c.totalAmount()))
                .param("planned", c.plannedStart())
                .param("terms", c.terms())
                .param("sha", c.termsSha256())
                .param("sentBy", c.sentBy())
                .param("respondBy", c.respondBy())
                .param("trainingStart", c.trainingStart())
                .param("trainingEnd", c.trainingEnd())
                .query(Integer.class).single();
    }

    public int markSigned(int contractId, int ownerId, String signedName, String ip, String sha) {
        return jdbc.sql("""
                UPDATE HORSE_CONTRACT
                SET status = 'SIGNED', signed_name = :name, signed_at = SYSUTCDATETIME(),
                    signed_ip = :ip, signed_sha256 = :sha, updated_at = SYSUTCDATETIME()
                WHERE contract_id = :id AND owner_id = :owner AND status = 'SENT'
                  AND terms_sha256 = :sha
                """)
                .param("name", signedName).param("ip", ip).param("sha", sha)
                .param("id", contractId).param("owner", ownerId)
                .update();
    }

    public int setPaymentLink(int contractId, long orderCode, String paymentLinkId, String checkoutUrl) {
        return jdbc.sql("""
                UPDATE HORSE_CONTRACT
                SET order_code = :code, payment_link_id = :link, checkout_url = :url,
                    updated_at = SYSUTCDATETIME()
                WHERE contract_id = :id AND status = 'SIGNED'
                """)
                .param("code", orderCode).param("link", paymentLinkId).param("url", checkoutUrl)
                .param("id", contractId)
                .update();
    }

    /**
     * payOS says the money arrived. A VOID contract is accepted too: if the owner paid
     * through a link that was still open when the offer lapsed, the money is real and the
     * club must see it, not lose it.
     */
    public int markPaid(int contractId, long amount, String reference) {
        return jdbc.sql("""
                UPDATE HORSE_CONTRACT
                SET status = 'PAID', paid_at = SYSUTCDATETIME(), paid_amount = :amount,
                    payment_ref = :ref, closed_note = NULL, updated_at = SYSUTCDATETIME()
                WHERE contract_id = :id AND status IN ('SIGNED', 'VOID') AND signed_at IS NOT NULL
                """)
                .param("amount", BigDecimal.valueOf(amount)).param("ref", reference)
                .param("id", contractId)
                .update();
    }

    /** The horse arrived: the stay starts today. */
    public int markActive(int contractId, int horseId, LocalDate start, LocalDate end, Integer receivedBy) {
        return jdbc.sql("""
                UPDATE HORSE_CONTRACT
                SET status = 'ACTIVE', horse_id = :horse, start_date = :start, end_date = :end,
                    received_by = :by, reminded_days = NULL, updated_at = SYSUTCDATETIME()
                WHERE contract_id = :id AND status = 'PAID'
                """)
                .param("horse", horseId).param("start", start).param("end", end)
                .param("by", receivedBy).param("id", contractId)
                .update();
    }

    public int markEnded(int contractId, String note) {
        return jdbc.sql("""
                UPDATE HORSE_CONTRACT SET status = 'ENDED', closed_note = :note, updated_at = SYSUTCDATETIME()
                WHERE contract_id = :id AND status IN ('ACTIVE', 'EXPIRED')
                """)
                .param("note", note).param("id", contractId)
                .update();
    }

    public int markVoid(int contractId, String note) {
        return jdbc.sql("""
                UPDATE HORSE_CONTRACT SET status = 'VOID', closed_note = :note, updated_at = SYSUTCDATETIME()
                WHERE contract_id = :id AND status IN ('SENT', 'SIGNED')
                """)
                .param("note", note).param("id", contractId)
                .update();
    }

    /* ---------------- nightly job ---------------- */

    /** Offers not signed and paid by respond_by. */
    public List<ContractRow> findOverdueOffers(LocalDate today) {
        return jdbc.sql(SELECT_ROW + " WHERE c.status IN ('SENT', 'SIGNED') AND c.respond_by < :today")
                .param("today", today).query(ContractRepository::map).list();
    }

    /** Running stays whose end date has passed. */
    public List<ContractRow> findLapsed(LocalDate today) {
        return jdbc.sql(SELECT_ROW + " WHERE c.status = 'ACTIVE' AND c.end_date <= :today")
                .param("today", today).query(ContractRepository::map).list();
    }

    public int markExpired(int contractId) {
        return jdbc.sql("""
                UPDATE HORSE_CONTRACT SET status = 'EXPIRED', updated_at = SYSUTCDATETIME()
                WHERE contract_id = :id AND status = 'ACTIVE'
                """)
                .param("id", contractId).update();
    }

    /** ACTIVE stays ending within maxDays that have not yet had a reminder this close. */
    public List<ContractRow> findToRemind(LocalDate today, int maxDays) {
        return jdbc.sql(SELECT_ROW + """
                 WHERE c.status = 'ACTIVE' AND c.end_date > :today
                   AND DATEDIFF(DAY, :today, c.end_date) <= :max
                """)
                .param("today", today).param("max", maxDays)
                .query(ContractRepository::map).list();
    }

    public Optional<Integer> findRemindedDays(int contractId) {
        return jdbc.sql("SELECT reminded_days FROM HORSE_CONTRACT WHERE contract_id = :id")
                .param("id", contractId)
                .query((rs, n) -> rs.getObject("reminded_days", Integer.class))
                .list().stream().filter(java.util.Objects::nonNull).findFirst();
    }

    public int markReminded(int contractId, int days) {
        return jdbc.sql("""
                UPDATE HORSE_CONTRACT SET reminded_days = :days
                WHERE contract_id = :id AND (reminded_days IS NULL OR reminded_days > :days)
                """)
                .param("days", days).param("id", contractId).update();
    }
}
