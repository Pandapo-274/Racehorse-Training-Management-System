package racehorse.backend.controller;

import java.util.Map;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import racehorse.backend.config.WebConfig;
import racehorse.backend.service.ContractService;

/**
 * payOS gọi vào đây khi có tiền về (đăng ký URL này trong kênh thanh toán trên
 * trang payOS). Đường này nằm trong {@link WebConfig#PUBLIC_PATHS}: payOS không
 * có JWT của mình. Thay cho JWT là chữ ký: ContractService chỉ tin body sau khi
 * kiểm chữ ký bằng checksum key (payOS SDK), sai chữ ký thì trả 400 và không
 * đụng tới hợp đồng nào.
 *
 * Nhận body dạng String nguyên văn (không để Spring parse thành object) vì chữ ký
 * được tính trên đúng dữ liệu payOS gửi.
 */
@RestController
@RequestMapping("/api/payments/payos")
public class PaymentWebhookController {

    private final ContractService contracts;

    public PaymentWebhookController(ContractService contracts) {
        this.contracts = contracts;
    }

    @PostMapping("/webhook")
    public ResponseEntity<Map<String, Object>> webhook(@RequestBody String body) {
        boolean ok = contracts.handleWebhook(body);
        return ok
                ? ResponseEntity.ok(Map.of("success", true))
                : ResponseEntity.badRequest().body(Map.of("success", false));
    }
}
