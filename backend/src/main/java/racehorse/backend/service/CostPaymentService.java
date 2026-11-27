package racehorse.backend.service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import racehorse.backend.entity.CostPaymentModels.Item;
import racehorse.backend.entity.CostPaymentModels.Overview;
import racehorse.backend.entity.CostPaymentModels.Payment;
import racehorse.backend.entity.CostPaymentModels.PaymentStart;
import racehorse.backend.entity.Notice;
import racehorse.backend.exception.ApiException;
import racehorse.backend.repository.CostPaymentRepository;
import racehorse.backend.repository.UserRepository;
import racehorse.backend.security.AuthenticatedUser;
import racehorse.backend.service.payment.PaymentGateway;

/**
 * Chủ ngựa trả online các khoản tính riêng ngoài tiền tháng (V57): y tế (MEDICAL) và lệ phí
 * giải, phí khác (OTHER). Tiền tháng (hợp đồng) đã gồm chuồng, ăn uống, chăm sóc và huấn luyện.
 *
 *   Pay      -> COST_PAYMENT PENDING, chốt danh sách khoản (COST_PAYMENT_ITEM), tạo link payOS
 *   Tiền về  -> PAID, đánh dấu các khoản đã trả (webhook, hoặc chủ quay về và server hỏi payOS)
 *   Danh sách khoản đổi, hoặc link hết hạn -> huỷ link cũ trên payOS, tạo lần mới
 *
 * Khoản đang khiếu nại không được thu cho tới khi Club Manager xử lý. Một khoản bị huỷ hay bị
 * khiếu nại sau khi đã tạo link thì không được đánh dấu là đã trả: phần tiền đó CLB hoàn lại,
 * và Club Manager được báo số tiền phải hoàn.
 */
@Service
public class CostPaymentService {

    private static final Logger log = LoggerFactory.getLogger(CostPaymentService.class);
    private static final ZoneId CLUB_ZONE = ZoneId.of("Asia/Ho_Chi_Minh");
    /** Mã đơn payOS của khoản phí nằm ở dải riêng, không trùng mã đơn của hợp đồng (contractId * 100000 + ...). */
    static final long ORDER_BASE = 50_000_000_000L;
    private static final int LINK_HOURS = 24;

    private final CostPaymentRepository payments;
    private final UserRepository users;
    private final NotificationService notifications;
    private final PaymentGateway gateway;
    private final String frontendBaseUrl;

    public CostPaymentService(CostPaymentRepository payments, UserRepository users,
                              NotificationService notifications, PaymentGateway gateway,
                              @Value("${app.frontend-base-url:http://localhost:3000}") String frontendBaseUrl) {
        this.payments = payments;
        this.users = users;
        this.notifications = notifications;
        this.gateway = gateway;
        this.frontendBaseUrl = frontendBaseUrl.endsWith("/")
                ? frontendBaseUrl.substring(0, frontendBaseUrl.length() - 1) : frontendBaseUrl;
    }

    public Overview overview(AuthenticatedUser owner) {
        return overview(owner.userId(), null);
    }

    private Overview overview(int ownerId, String syncProblem) {
        List<Item> items = payments.outstanding(ownerId);
        CostPaymentRepository.Held held = payments.held(ownerId);
        return new Overview(items, totalOf(items), held.count(), held.total(),
                payments.findOpen(ownerId).orElse(null), payments.recentPaid(ownerId, 10),
                gateway.isConfigured(), syncProblem);
    }

    /**
     * Chủ quay về từ trang payOS (hoặc bấm Check payment): hỏi payOS về lần thanh toán đang chờ.
     * payOS không gọi được thì vẫn trả trang, kèm lời nhắc thử lại.
     */
    @Transactional
    public Overview sync(AuthenticatedUser owner) {
        String problem = null;
        Payment open = payments.findOpen(owner.userId()).orElse(null);
        if (open != null && open.orderCode() != null && gateway.isConfigured()) {
            try {
                PaymentGateway.Payment p = gateway.fetch(open.orderCode());
                if (p.paid()) {
                    apply(open, p);
                }
            } catch (ApiException e) {
                problem = e.getMessage();
            }
        }
        return overview(owner.userId(), problem);
    }

    /**
     * Mở (hoặc dùng lại) link thanh toán cho mọi khoản đang phải trả. Link cũ còn mở mà danh sách
     * khoản không đổi thì dùng lại, để hai link cùng lúc không thể đều được trả.
     */
    @Transactional
    public PaymentStart start(AuthenticatedUser owner) {
        if (!"HORSE_OWNER".equals(owner.role())) {
            throw new ApiException(HttpStatus.FORBIDDEN, "Only the horse owner pays these costs");
        }
        List<Item> items = payments.outstanding(owner.userId());
        if (items.isEmpty()) {
            throw new ApiException(HttpStatus.CONFLICT, "There is nothing to pay right now");
        }
        long total = totalOf(items);
        Payment open = payments.findOpen(owner.userId()).orElse(null);
        if (open != null) {
            if (open.orderCode() != null) {
                PaymentGateway.Payment p = gateway.fetch(open.orderCode());
                if (p.paid()) {
                    apply(open, p);
                    return new PaymentStart(open.paymentId(), null, open.totalAmount(), true);
                }
                boolean live = "PENDING".equals(p.status()) || "PROCESSING".equals(p.status());
                boolean same = open.totalAmount() == total
                        && new HashSet<>(payments.itemIds(open.paymentId())).equals(idsOf(items));
                if (live && same && open.checkoutUrl() != null) {
                    return new PaymentStart(open.paymentId(), open.checkoutUrl(), total, false);
                }
                if (live) {
                    // The list changed: close the old link first so it can no longer be paid.
                    gateway.cancel(open.orderCode(), "Replaced by a new payment");
                }
            }
            payments.markCancelled(open.paymentId());
        }

        int id;
        try {
            id = payments.insert(owner.userId(), total);
        } catch (DuplicateKeyException e) {
            throw new ApiException(HttpStatus.CONFLICT, "A payment is already being prepared. Reload the page");
        }
        for (Item it : items) {
            payments.insertItem(id, it.expenseId(), it.amount());
        }
        long orderCode = ORDER_BASE + id * 100_000L + (System.currentTimeMillis() / 1000) % 100_000L;
        long expiresAt = ZonedDateTime.now(CLUB_ZONE).plusHours(LINK_HOURS).toEpochSecond();
        String back = frontendBaseUrl + "/horse-owner/payments";
        String buyer = users.findById(owner.userId()).map(u -> u.fullName()).orElse(null);
        PaymentGateway.Link link = gateway.createLink(orderCode, total, description(id),
                items.size() + " cost item" + (items.size() == 1 ? "" : "s"), buyer, back, back, expiresAt);
        if (payments.setLink(id, link.orderCode(), link.paymentLinkId(), link.checkoutUrl()) == 0) {
            throw new ApiException(HttpStatus.CONFLICT, "This payment has just changed. Reload the page");
        }
        return new PaymentStart(id, link.checkoutUrl(), total, false);
    }

    /**
     * Webhook payOS cho một mã đơn không phải của hợp đồng (ContractService gọi sang).
     * @return false khi mã đơn cũng không phải của khoản phí nào.
     */
    @Transactional
    public boolean applyWebhook(PaymentGateway.Payment p) {
        var id = payments.findIdByOrderCode(p.orderCode());
        if (id.isEmpty()) {
            return false;
        }
        if (p.paid()) {
            payments.findById(id.get()).ifPresent(row -> apply(row, p));
        }
        return true;
    }

    /** Dùng chung cho webhook, sync và Pay: đánh dấu PAID một lần, rồi đánh dấu từng khoản. */
    void apply(Payment row, PaymentGateway.Payment p) {
        if (p.amount() < row.totalAmount()) {
            log.warn("Cost payment {} received {} VND of {}: not marked paid", row.paymentId(), p.amount(),
                    row.totalAmount());
            return;
        }
        if (payments.markPaid(row.paymentId(), p.amount(), p.reference()) == 0) {
            return; // the other path got here first
        }
        int marked = payments.markExpensesPaid(row.paymentId());
        BigDecimal refund = payments.unappliedAmount(row.paymentId());
        String refundNote = refund.signum() > 0
                ? " " + vnd(refund) + " was for costs voided or disputed after the link was made: the club refunds it."
                : "";
        users.writeAudit(row.ownerId(), "COST_PAYMENT_PAID", "COST_PAYMENT", row.paymentId(), "SUCCESS", null,
                vnd(p.amount()) + " via payOS, order " + p.orderCode() + ", " + marked + " cost item(s) paid"
                        + (p.reference() == null ? "" : ", bank ref " + p.reference()) + "." + refundNote);
        notifications.notifyUser(row.ownerId(), new Notice("COST_PAYMENT_PAID", "Payment received",
                "We received " + vnd(p.amount()) + " for " + marked + " cost item(s)." + refundNote, null, null));
        String ownerName = users.findById(row.ownerId()).map(u -> u.fullName()).orElse("An owner");
        notifications.notifyRole("CLUB_MANAGER", null, new Notice("COST_PAYMENT_PAID", "Costs paid online",
                ownerName + " paid " + vnd(p.amount()) + " for " + marked + " cost item(s)." + refundNote, null, null));
    }

    /** payOS limits the description to 9 characters for a bank account not linked through payOS. */
    static String description(int paymentId) {
        return "CP" + paymentId;
    }

    /** payOS charges whole VND: the sum is rounded up. */
    static long totalOf(List<Item> items) {
        return items.stream().map(Item::amount).reduce(BigDecimal.ZERO, BigDecimal::add)
                .setScale(0, RoundingMode.CEILING).longValue();
    }

    private static Set<Integer> idsOf(List<Item> items) {
        return items.stream().map(Item::expenseId).collect(Collectors.toSet());
    }

    private static String vnd(BigDecimal amount) {
        return ContractTerms.vnd(amount.setScale(0, RoundingMode.CEILING).longValue());
    }

    private static String vnd(long amount) {
        return ContractTerms.vnd(amount);
    }
}
