package racehorse.backend.controller;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestAttribute;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import racehorse.backend.entity.CostPaymentModels;
import racehorse.backend.security.AuthInterceptor;
import racehorse.backend.security.AuthenticatedUser;
import racehorse.backend.security.RequireRole;
import racehorse.backend.service.CostPaymentService;

/**
 * Chủ ngựa trả online các khoản tính riêng ngoài tiền tháng (V57): y tế và lệ phí giải, phí khác.
 *
 *   GET  /api/owner/payments        khoản đang phải trả, lần thanh toán đang chờ, các lần đã trả
 *   POST /api/owner/payments        mở (hoặc dùng lại) link payOS; trả checkoutUrl để chuyển trang
 *   POST /api/owner/payments/sync   hỏi lại payOS (khi quay về từ trang thanh toán)
 * Tiền về qua webhook dùng chung /api/payments/payos/webhook (ContractService chuyển sang).
 */
@RestController
@RequestMapping("/api/owner/payments")
public class OwnerPaymentController {

    private final CostPaymentService service;

    public OwnerPaymentController(CostPaymentService service) {
        this.service = service;
    }

    @GetMapping
    @RequireRole({"HORSE_OWNER"})
    public CostPaymentModels.Overview overview(@RequestAttribute(AuthInterceptor.USER_ATTR) AuthenticatedUser user) {
        return service.overview(user);
    }

    @PostMapping
    @RequireRole({"HORSE_OWNER"})
    public CostPaymentModels.PaymentStart pay(@RequestAttribute(AuthInterceptor.USER_ATTR) AuthenticatedUser user) {
        return service.start(user);
    }

    @PostMapping("/sync")
    @RequireRole({"HORSE_OWNER"})
    public CostPaymentModels.Overview sync(@RequestAttribute(AuthInterceptor.USER_ATTR) AuthenticatedUser user) {
        return service.sync(user);
    }
}
