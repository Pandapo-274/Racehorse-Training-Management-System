package racehorse.backend.entity;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

/**
 * Thanh toán online các khoản tính riêng ngoài tiền tháng (V57): y tế (MEDICAL) và lệ phí
 * giải, phí khác (OTHER). Tiền tháng đã gồm chuồng, ăn uống, chăm sóc và huấn luyện.
 */
public final class CostPaymentModels {

    private CostPaymentModels() {
    }

    /** Một khoản chủ ngựa phải trả (hoặc đã nằm trong một lần thanh toán). */
    public record Item(int expenseId, int horseId, String horseName, String category, BigDecimal amount,
                       LocalDate expenseDate, String note) {
    }

    /** Một lần thanh toán payOS. */
    public record Payment(int paymentId, int ownerId, long totalAmount, String status, Long orderCode,
                          String checkoutUrl, LocalDateTime createdAt, LocalDateTime paidAt,
                          Long paidAmount, String paymentRef, int itemCount) {
    }

    /**
     * Trang Payments của chủ ngựa. items: khoản đang phải trả; held: khoản đang khiếu nại, chưa
     * thu cho tới khi Club Manager xử lý; open: lần thanh toán đang chờ (nếu có).
     */
    public record Overview(List<Item> items, long total, int heldCount, BigDecimal heldTotal,
                           Payment open, List<Payment> recent, boolean onlinePayment, String syncProblem) {
    }

    /** checkoutUrl null khi hoá ra đã trả rồi (alreadyPaid). */
    public record PaymentStart(int paymentId, String checkoutUrl, long amount, boolean alreadyPaid) {
    }
}
