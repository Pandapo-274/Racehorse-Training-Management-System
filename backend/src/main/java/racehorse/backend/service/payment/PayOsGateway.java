package racehorse.backend.service.payment;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;

import racehorse.backend.exception.ApiException;
import vn.payos.PayOS;
import vn.payos.exception.PayOSException;
import vn.payos.model.v2.paymentRequests.CreatePaymentLinkRequest;
import vn.payos.model.v2.paymentRequests.CreatePaymentLinkResponse;
import vn.payos.model.v2.paymentRequests.PaymentLink;
import vn.payos.model.v2.paymentRequests.PaymentLinkItem;
import vn.payos.model.v2.paymentRequests.PaymentLinkStatus;
import vn.payos.model.webhooks.WebhookData;

/**
 * payOS (https://payos.vn) qua SDK chính thức vn.payos:payos-java.
 *
 * Ba khoá lấy từ kênh thanh toán trên trang quản trị payOS: Client ID, API Key,
 * Checksum Key, đặt trong backend/.env (PAYOS_CLIENT_ID, PAYOS_API_KEY,
 * PAYOS_CHECKSUM_KEY). Thiếu một khoá thì cổng coi như chưa cấu hình: chủ ngựa
 * thấy lỗi rõ ràng thay vì server không khởi động được.
 *
 * Tiền là tiền thật: người trả quét VietQR, tiền về tài khoản ngân hàng gắn với
 * kênh payOS. Không có tên ngân hàng hay ví nào trong code; ngân hàng nhận tiền
 * do chủ kênh chọn trên payOS.
 */
@Component
public class PayOsGateway implements PaymentGateway {

    private static final Logger log = LoggerFactory.getLogger(PayOsGateway.class);

    private final PayOS client;

    public PayOsGateway(@Value("${app.payos.client-id:}") String clientId,
                        @Value("${app.payos.api-key:}") String apiKey,
                        @Value("${app.payos.checksum-key:}") String checksumKey) {
        boolean ready = !clientId.isBlank() && !apiKey.isBlank() && !checksumKey.isBlank();
        this.client = ready ? new PayOS(clientId.trim(), apiKey.trim(), checksumKey.trim()) : null;
        if (!ready) {
            log.warn("payOS keys are not set (PAYOS_CLIENT_ID / PAYOS_API_KEY / PAYOS_CHECKSUM_KEY): "
                    + "contracts can be signed but not paid online.");
        }
    }

    @Override
    public boolean isConfigured() {
        return client != null;
    }

    @Override
    public Link createLink(long orderCode, long amount, String description, String itemName,
                           String buyerName, String returnUrl, String cancelUrl, long expiresAtEpochSeconds) {
        PayOS payos = require();
        CreatePaymentLinkRequest request = CreatePaymentLinkRequest.builder()
                .orderCode(orderCode)
                .amount(amount)
                .description(description)
                .item(PaymentLinkItem.builder().name(itemName).quantity(1).price(amount).build())
                .buyerName(buyerName)
                .returnUrl(returnUrl)
                .cancelUrl(cancelUrl)
                .expiredAt(expiresAtEpochSeconds)
                .build();
        try {
            CreatePaymentLinkResponse res = payos.paymentRequests().create(request);
            return new Link(orderCode, res.getPaymentLinkId(), res.getCheckoutUrl());
        } catch (PayOSException e) {
            log.error("payOS refused to create payment link for order {}: {}", orderCode, e.getMessage());
            throw new ApiException(HttpStatus.BAD_GATEWAY,
                    "The payment gateway did not accept the request: " + e.getMessage());
        }
    }

    @Override
    public Payment verifyWebhook(String rawBody) {
        PayOS payos = require();
        try {
            WebhookData data = payos.webhooks().verify(rawBody);
            // code "00" = the transfer succeeded; anything else is reported but not counted as paid.
            boolean paid = "00".equals(data.getCode());
            return new Payment(data.getOrderCode(), data.getAmount() == null ? 0 : data.getAmount(),
                    data.getReference(), paid, null);
        } catch (PayOSException e) {
            throw new IllegalArgumentException("Invalid payOS webhook: " + e.getMessage(), e);
        }
    }

    @Override
    public Payment fetch(long orderCode) {
        PayOS payos = require();
        try {
            PaymentLink link = payos.paymentRequests().get(orderCode);
            boolean paid = link.getStatus() == PaymentLinkStatus.PAID;
            long amount = link.getAmountPaid() == null ? 0 : link.getAmountPaid();
            String reference = null;
            if (link.getTransactions() != null && !link.getTransactions().isEmpty()) {
                reference = link.getTransactions().get(0).getReference();
            }
            return new Payment(orderCode, amount, reference, paid,
                    link.getStatus() == null ? null : link.getStatus().getValue());
        } catch (PayOSException e) {
            log.warn("payOS lookup failed for order {}: {}", orderCode, e.getMessage());
            throw new ApiException(HttpStatus.BAD_GATEWAY,
                    "Could not reach the payment gateway. Try again in a moment.");
        }
    }

    @Override
    public void cancel(long orderCode, String reason) {
        PayOS payos = require();
        try {
            payos.paymentRequests().cancel(orderCode, reason);
        } catch (PayOSException e) {
            log.warn("payOS could not cancel order {}: {}", orderCode, e.getMessage());
            throw new ApiException(HttpStatus.BAD_GATEWAY,
                    "Could not replace the previous payment link. Try again in a moment.");
        }
    }

    private PayOS require() {
        if (client == null) {
            throw new ApiException(HttpStatus.SERVICE_UNAVAILABLE,
                    "Online payment is not set up on this server (payOS keys are missing)");
        }
        return client;
    }
}
