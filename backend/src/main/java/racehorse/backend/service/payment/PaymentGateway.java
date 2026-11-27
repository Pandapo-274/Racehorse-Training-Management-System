package racehorse.backend.service.payment;

/**
 * Cổng thanh toán, nhìn từ phía ContractService. Tách thành interface để
 * ContractService không phụ thuộc trực tiếp vào SDK payOS: unit test thay bằng
 * mock, và đổi cổng khác sau này chỉ phải viết một lớp mới.
 *
 * Bản dùng thật: {@link PayOsGateway}.
 */
public interface PaymentGateway {

    /** false khi server chưa được cấu hình khoá payOS (.env thiếu PAYOS_*). */
    boolean isConfigured();

    /** Một link thanh toán vừa tạo. */
    record Link(long orderCode, String paymentLinkId, String checkoutUrl) {}

    /**
     * @param orderCode  mã đơn duy nhất phía mình (payOS bắt buộc là số dương)
     * @param amount     số tiền VND
     * @param description nội dung chuyển khoản ngắn, ví dụ "RTMS HD12"
     * @param expiresAtEpochSeconds link tự hết hạn lúc này (cùng hạn của lời mời hợp đồng)
     */
    Link createLink(long orderCode, long amount, String description, String itemName,
                    String buyerName, String returnUrl, String cancelUrl, long expiresAtEpochSeconds);

    /**
     * Một khoản đã trả, đã được xác thực chữ ký (webhook) hoặc hỏi lại payOS (polling).
     * status là trạng thái link của payOS (PENDING, PAID, CANCELLED, EXPIRED...), null với webhook.
     */
    record Payment(long orderCode, long amount, String reference, boolean paid, String status) {}

    /**
     * Xác thực body webhook payOS gửi tới bằng checksum key. Ném
     * {@link IllegalArgumentException} khi chữ ký sai: không được tin dữ liệu đó.
     */
    Payment verifyWebhook(String rawBody);

    /** Hỏi payOS trạng thái hiện tại của một đơn (dùng khi chủ ngựa quay về từ trang thanh toán). */
    Payment fetch(long orderCode);

    /**
     * Huỷ một link còn mở, để link cũ không thể trả được nữa trước khi tạo link mới (V57).
     * Ném ApiException khi payOS không huỷ được: khi đó không tạo link mới.
     */
    void cancel(long orderCode, String reason);
}
