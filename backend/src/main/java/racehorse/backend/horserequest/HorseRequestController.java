package racehorse.backend.horserequest;

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
import org.springframework.web.bind.annotation.RestController;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import racehorse.backend.auth.AuthInterceptor;
import racehorse.backend.auth.AuthenticatedUser;
import racehorse.backend.auth.RequireRole;

/**
 * Chủ ngựa xin học viện đăng ký một con ngựa.
 *
 * Tồn tại vì HorseController.create chỉ cho HEAD_TRAINER và CLUB_MANAGER gọi,
 * nên chủ ngựa mới vào không có đường nào báo với học viện rằng mình có ngựa.
 * Đây là đường đó - và nó không phải cửa sau: yêu cầu chỉ thành hồ sơ ngựa
 * khi học viện bấm duyệt, và lúc duyệt vẫn đi qua đúng HorseService.create.
 *
 * ownerId luôn lấy từ token, không nhận từ client, nên không ai gửi yêu cầu
 * thay người khác được.
 */
@RestController
@RequestMapping("/api/horse-requests")
public class HorseRequestController {

    private final HorseRequestService service;

    public HorseRequestController(HorseRequestService service) {
        this.service = service;
    }

    @PostMapping
    @RequireRole({"HORSE_OWNER"})
    public ResponseEntity<HorseRequestRow> submit(
            @Valid @RequestBody SubmitRequest request,
            @RequestAttribute(AuthInterceptor.USER_ATTR) AuthenticatedUser user,
            HttpServletRequest http) {
        HorseRequestRow body = service.submit(request, user, http.getRemoteAddr());
        return ResponseEntity.status(HttpStatus.CREATED).body(body);
    }

    /** Chủ ngựa nhận yêu cầu của mình; học viện nhận cả hàng chờ. */
    @GetMapping
    public List<HorseRequestRow> list(
            @RequestAttribute(AuthInterceptor.USER_ATTR) AuthenticatedUser user) {
        return service.list(user);
    }

    @PutMapping("/{id}/approve")
    @RequireRole({"HEAD_TRAINER", "CLUB_MANAGER"})
    public HorseRequestRow approve(
            @PathVariable int id,
            @Valid @RequestBody ReviewRequest review,
            @RequestAttribute(AuthInterceptor.USER_ATTR) AuthenticatedUser user,
            HttpServletRequest http) {
        return service.approve(id, review, user, http.getRemoteAddr());
    }

    @PutMapping("/{id}/reject")
    @RequireRole({"HEAD_TRAINER", "CLUB_MANAGER"})
    public HorseRequestRow reject(
            @PathVariable int id,
            @Valid @RequestBody ReviewRequest review,
            @RequestAttribute(AuthInterceptor.USER_ATTR) AuthenticatedUser user,
            HttpServletRequest http) {
        return service.reject(id, review, user, http.getRemoteAddr());
    }
}
