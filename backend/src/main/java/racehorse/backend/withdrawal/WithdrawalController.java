package racehorse.backend.withdrawal;

import java.util.List;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestAttribute;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import racehorse.backend.auth.AuthInterceptor;
import racehorse.backend.auth.AuthenticatedUser;
import racehorse.backend.auth.RequireRole;

/**
 * Rút ngựa khỏi học viện (V15).
 *
 * Chủ ngựa gửi và huỷ yêu cầu của mình; Club Manager duyệt hoặc từ chối.
 * Quyền sở hữu con ngựa kiểm ở WithdrawalService, không chỉ dựa vào role.
 */
@RestController
@RequestMapping("/api/horse-withdrawals")
public class WithdrawalController {

    private final WithdrawalService service;

    public WithdrawalController(WithdrawalService service) {
        this.service = service;
    }

    @PostMapping
    @RequireRole({"HORSE_OWNER"})
    public ResponseEntity<WithdrawalRow> submit(
            @Valid @RequestBody WithdrawalSubmitRequest request,
            @RequestAttribute(AuthInterceptor.USER_ATTR) AuthenticatedUser user,
            HttpServletRequest http) {
        WithdrawalRow body = service.submit(request, user, http.getRemoteAddr());
        return ResponseEntity.status(HttpStatus.CREATED).body(body);
    }

    /** Chủ ngựa nhận yêu cầu về ngựa của mình; Club Manager nhận cả hàng chờ. */
    @GetMapping
    @RequireRole({"HORSE_OWNER", "CLUB_MANAGER"})
    public List<WithdrawalRow> list(
            @RequestParam(required = false) Integer horseId,
            @RequestAttribute(AuthInterceptor.USER_ATTR) AuthenticatedUser user) {
        return service.list(horseId, user);
    }

    @PutMapping("/{id}/cancel")
    @RequireRole({"HORSE_OWNER"})
    public WithdrawalRow cancel(
            @PathVariable int id,
            @RequestAttribute(AuthInterceptor.USER_ATTR) AuthenticatedUser user,
            HttpServletRequest http) {
        return service.cancel(id, user, http.getRemoteAddr());
    }

    @PutMapping("/{id}/approve")
    @RequireRole({"CLUB_MANAGER"})
    public WithdrawalRow approve(
            @PathVariable int id,
            @Valid @RequestBody(required = false) WithdrawalReviewRequest review,
            @RequestAttribute(AuthInterceptor.USER_ATTR) AuthenticatedUser user,
            HttpServletRequest http) {
        return service.approve(id, review, user, http.getRemoteAddr());
    }

    @PutMapping("/{id}/reject")
    @RequireRole({"CLUB_MANAGER"})
    public WithdrawalRow reject(
            @PathVariable int id,
            @Valid @RequestBody WithdrawalReviewRequest review,
            @RequestAttribute(AuthInterceptor.USER_ATTR) AuthenticatedUser user,
            HttpServletRequest http) {
        return service.reject(id, review, user, http.getRemoteAddr());
    }
}
