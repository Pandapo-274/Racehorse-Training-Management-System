package racehorse.backend.controller;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import java.util.List;
import java.util.Map;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestAttribute;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import racehorse.backend.dto.ReceiveRequest;
import racehorse.backend.dto.RenewContractRequest;
import racehorse.backend.dto.SignContractRequest;
import racehorse.backend.entity.ContractRow;
import racehorse.backend.security.AuthInterceptor;
import racehorse.backend.security.AuthenticatedUser;
import racehorse.backend.security.RequireRole;
import racehorse.backend.service.ContractService;

/**
 * Hợp đồng gửi ngựa (V49). Chủ ngựa: xem, ký, trả tiền, gia hạn, từ chối.
 * Club Manager: xem tất cả, nhận ngựa khi đã trả tiền, rút lại lời mời chưa trả.
 * Quyền theo từng hợp đồng (chủ chỉ thấy hợp đồng của mình) kiểm trong ContractService.
 */
@RestController
@RequestMapping("/api/contracts")
public class ContractController {

    private final ContractService service;

    public ContractController(ContractService service) {
        this.service = service;
    }

    @GetMapping
    @RequireRole({"HORSE_OWNER", "CLUB_MANAGER"})
    public List<ContractRow> list(@RequestAttribute(AuthInterceptor.USER_ATTR) AuthenticatedUser user) {
        return service.list(user);
    }

    @GetMapping("/{id}")
    @RequireRole({"HORSE_OWNER", "CLUB_MANAGER"})
    public ContractRow get(@PathVariable int id,
                           @RequestAttribute(AuthInterceptor.USER_ATTR) AuthenticatedUser user) {
        return service.get(id, user);
    }

    /** Hợp đồng của một con ngựa: thời hạn đang chạy và lần gia hạn đang chờ (trang chi tiết ngựa). */
    @GetMapping("/horse/{horseId}")
    @RequireRole({"HORSE_OWNER", "CLUB_MANAGER", "HEAD_TRAINER", "VETERINARIAN", "GROOM"})
    public List<ContractRow> forHorse(@PathVariable int horseId,
                                      @RequestAttribute(AuthInterceptor.USER_ATTR) AuthenticatedUser user) {
        return service.forHorse(horseId, user);
    }

    @PostMapping("/{id}/sign")
    @RequireRole({"HORSE_OWNER"})
    public ContractRow sign(@PathVariable int id, @Valid @RequestBody SignContractRequest body,
                            @RequestAttribute(AuthInterceptor.USER_ATTR) AuthenticatedUser user,
                            HttpServletRequest http) {
        return service.sign(id, body, user, http.getRemoteAddr());
    }

    /** Mở trang thanh toán payOS. Trả checkoutUrl để frontend chuyển trang. */
    @PostMapping("/{id}/pay")
    @RequireRole({"HORSE_OWNER"})
    public ContractService.PaymentStart pay(@PathVariable int id,
                                            @RequestAttribute(AuthInterceptor.USER_ATTR) AuthenticatedUser user) {
        return service.startPayment(id, user);
    }

    /** Hỏi lại payOS (khi quay về từ trang thanh toán, hoặc bấm "Check payment"). */
    @PostMapping("/{id}/sync")
    @RequireRole({"HORSE_OWNER", "CLUB_MANAGER"})
    public ContractRow sync(@PathVariable int id,
                            @RequestAttribute(AuthInterceptor.USER_ATTR) AuthenticatedUser user) {
        return service.syncPayment(id, user);
    }

    @PostMapping("/{id}/receive")
    @RequireRole({"CLUB_MANAGER"})
    public ContractRow receive(@PathVariable int id, @Valid @RequestBody ReceiveRequest body,
                               @RequestAttribute(AuthInterceptor.USER_ATTR) AuthenticatedUser user,
                               HttpServletRequest http) {
        return service.receive(id, body, user, http.getRemoteAddr());
    }

    @PostMapping("/{id}/cancel")
    @RequireRole({"HORSE_OWNER", "CLUB_MANAGER"})
    public ContractRow cancel(@PathVariable int id, @RequestBody(required = false) Map<String, String> body,
                              @RequestAttribute(AuthInterceptor.USER_ATTR) AuthenticatedUser user,
                              HttpServletRequest http) {
        return service.cancel(id, body == null ? null : body.get("reason"), user, http.getRemoteAddr());
    }

    @PostMapping("/renew")
    @RequireRole({"HORSE_OWNER"})
    public ContractRow renew(@Valid @RequestBody RenewContractRequest body,
                             @RequestAttribute(AuthInterceptor.USER_ATTR) AuthenticatedUser user,
                             HttpServletRequest http) {
        return service.renew(body.horseId(), body.months(), user, http.getRemoteAddr());
    }
}
