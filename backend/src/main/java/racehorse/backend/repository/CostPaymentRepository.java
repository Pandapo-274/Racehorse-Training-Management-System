package racehorse.backend.repository;

import java.math.BigDecimal;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import racehorse.backend.entity.CostPaymentModels.Item;
import racehorse.backend.entity.CostPaymentModels.Payment;

/**
 * COST_PAYMENT, COST_PAYMENT_ITEM và hai cột paid_* của EXPENSE_LOG (V57).
 * Mọi bước đổi trạng thái là UPDATE có điều kiện trạng thái cũ; người gọi đọc số dòng:
 * 0 nghĩa là đường khác (webhook, tab khác) đã đổi trước.
 */
@Repository
public class CostPaymentRepository {

    /** Khoản thu online: y tế và lệ phí giải, phí khác. Ăn uống và huấn luyện đã nằm trong tiền tháng. */
    static final String PAYABLE = """
            e.cancelled_at IS NULL AND e.paid_at IS NULL
              AND e.category IN ('MEDICAL', 'OTHER')
              AND h.owner_id = :owner AND h.record_type = 'ACADEMY'
            """;

    private final JdbcClient jdbc;

    public CostPaymentRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    private static Item item(ResultSet rs, int n) throws SQLException {
        return new Item(rs.getInt("expense_id"), rs.getInt("horse_id"), rs.getString("horse_name"),
                rs.getString("category"), rs.getBigDecimal("amount"),
                rs.getObject("expense_date", LocalDate.class), rs.getString("note"));
    }

    private static final String PAYMENT_COLUMNS = """
            SELECT p.payment_id, p.owner_id, p.total_amount, p.status, p.order_code, p.checkout_url,
                   p.created_at, p.paid_at, p.paid_amount, p.payment_ref,
                   (SELECT COUNT(*) FROM COST_PAYMENT_ITEM i WHERE i.payment_id = p.payment_id) AS item_count
            FROM COST_PAYMENT p
            """;

    private static Payment payment(ResultSet rs, int n) throws SQLException {
        BigDecimal paid = rs.getBigDecimal("paid_amount");
        return new Payment(rs.getInt("payment_id"), rs.getInt("owner_id"), rs.getBigDecimal("total_amount").longValue(),
                rs.getString("status"), rs.getObject("order_code", Long.class), rs.getString("checkout_url"),
                rs.getObject("created_at", LocalDateTime.class), rs.getObject("paid_at", LocalDateTime.class),
                paid == null ? null : paid.longValue(), rs.getString("payment_ref"), rs.getInt("item_count"));
    }

    /** Khoản đang phải trả: chưa huỷ, chưa trả, không bị khiếu nại. Cũ nhất trước. */
    public List<Item> outstanding(int ownerId) {
        return jdbc.sql("""
                SELECT e.expense_id, e.horse_id, h.horse_name, e.category, e.amount, e.expense_date, e.note
                FROM EXPENSE_LOG e JOIN HORSE h ON h.horse_id = e.horse_id
                WHERE""" + " " + PAYABLE + " AND e.status IN ('BILLED', 'RESOLVED')"
                        + " ORDER BY e.expense_date, e.expense_id")
                .param("owner", ownerId)
                .query(CostPaymentRepository::item)
                .list();
    }

    public record Held(int count, BigDecimal total) {}

    /** Khoản đang khiếu nại: chưa thu cho tới khi Club Manager xử lý. */
    public Held held(int ownerId) {
        return jdbc.sql("""
                SELECT COUNT(*) AS n, ISNULL(SUM(e.amount), 0) AS total
                FROM EXPENSE_LOG e JOIN HORSE h ON h.horse_id = e.horse_id
                WHERE""" + " " + PAYABLE + " AND e.status = 'DISPUTED'")
                .param("owner", ownerId)
                .query((rs, n) -> new Held(rs.getInt("n"), rs.getBigDecimal("total")))
                .single();
    }

    public Optional<Payment> findById(int paymentId) {
        return jdbc.sql(PAYMENT_COLUMNS + " WHERE p.payment_id = :id")
                .param("id", paymentId).query(CostPaymentRepository::payment).optional();
    }

    public Optional<Payment> findOpen(int ownerId) {
        return jdbc.sql(PAYMENT_COLUMNS + " WHERE p.owner_id = :owner AND p.status = 'PENDING'")
                .param("owner", ownerId).query(CostPaymentRepository::payment).optional();
    }

    public Optional<Integer> findIdByOrderCode(long orderCode) {
        return jdbc.sql("SELECT payment_id FROM COST_PAYMENT WHERE order_code = :code")
                .param("code", orderCode).query(Integer.class).optional();
    }

    /** Các lần đã trả, mới nhất trước. */
    public List<Payment> recentPaid(int ownerId, int limit) {
        return jdbc.sql("SELECT TOP (:limit) * FROM (" + PAYMENT_COLUMNS
                        + " WHERE p.owner_id = :owner AND p.status = 'PAID') x ORDER BY x.paid_at DESC")
                .param("limit", limit).param("owner", ownerId)
                .query(CostPaymentRepository::payment).list();
    }

    public List<Integer> itemIds(int paymentId) {
        return jdbc.sql("SELECT expense_id FROM COST_PAYMENT_ITEM WHERE payment_id = :id ORDER BY expense_id")
                .param("id", paymentId).query(Integer.class).list();
    }

    public int insert(int ownerId, long total) {
        return jdbc.sql("""
                INSERT INTO COST_PAYMENT (owner_id, total_amount) OUTPUT INSERTED.payment_id VALUES (:owner, :total)
                """)
                .param("owner", ownerId).param("total", total).query(Integer.class).single();
    }

    public void insertItem(int paymentId, int expenseId, BigDecimal amount) {
        jdbc.sql("INSERT INTO COST_PAYMENT_ITEM (payment_id, expense_id, amount) VALUES (:p, :e, :a)")
                .param("p", paymentId).param("e", expenseId).param("a", amount).update();
    }

    public int setLink(int paymentId, long orderCode, String linkId, String url) {
        return jdbc.sql("""
                UPDATE COST_PAYMENT SET order_code = :code, payment_link_id = :link, checkout_url = :url
                WHERE payment_id = :id AND status = 'PENDING'
                """)
                .param("code", orderCode).param("link", linkId).param("url", url).param("id", paymentId)
                .update();
    }

    public int markCancelled(int paymentId) {
        return jdbc.sql("UPDATE COST_PAYMENT SET status = 'CANCELLED' WHERE payment_id = :id AND status = 'PENDING'")
                .param("id", paymentId).update();
    }

    public int markPaid(int paymentId, long amount, String reference) {
        return jdbc.sql("""
                UPDATE COST_PAYMENT
                SET status = 'PAID', paid_at = SYSUTCDATETIME(), paid_amount = :amount, payment_ref = :ref
                WHERE payment_id = :id AND status = 'PENDING'
                """)
                .param("amount", amount).param("ref", reference).param("id", paymentId).update();
    }

    /**
     * Đánh dấu đã trả những khoản của lần thanh toán này mà vẫn còn hợp lệ. Khoản bị huỷ
     * hoặc bị khiếu nại sau khi tạo link thì không đánh dấu: CLB hoàn số tiền đó.
     * @return số khoản được đánh dấu.
     */
    public int markExpensesPaid(int paymentId) {
        return jdbc.sql("""
                UPDATE e SET e.paid_at = SYSUTCDATETIME(), e.paid_payment_id = :id
                FROM EXPENSE_LOG e
                JOIN COST_PAYMENT_ITEM i ON i.expense_id = e.expense_id AND i.payment_id = :id
                WHERE e.paid_at IS NULL AND e.cancelled_at IS NULL AND e.status IN ('BILLED', 'RESOLVED')
                """)
                .param("id", paymentId).update();
    }

    /** Phần tiền của lần thanh toán này rơi vào khoản không còn hợp lệ (CLB phải hoàn). */
    public BigDecimal unappliedAmount(int paymentId) {
        return jdbc.sql("""
                SELECT ISNULL(SUM(i.amount), 0) FROM COST_PAYMENT_ITEM i
                JOIN EXPENSE_LOG e ON e.expense_id = i.expense_id
                WHERE i.payment_id = :id AND (e.paid_payment_id IS NULL OR e.paid_payment_id <> :id)
                """)
                .param("id", paymentId).query(BigDecimal.class).single();
    }
}
