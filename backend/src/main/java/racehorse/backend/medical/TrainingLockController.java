package racehorse.backend.medical;

import java.util.List;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
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
 * UC16 - Issue Emergency Training Block (Training Lock Management).
 *
 * Hai danh sách vai trò khác nhau, cố ý: ra lệnh thì ba vai trò đều được, mở
 * lệnh thì chỉ bác sĩ và quản lý. Xem ghi chú ở TrainingLockService về lý do
 * một chốt an toàn phải dễ bật và khó tắt.
 */
@RestController
@RequestMapping("/api/training-locks")
public class TrainingLockController {

    private final TrainingLockService service;

    public TrainingLockController(TrainingLockService service) {
        this.service = service;
    }

    @PostMapping
    @RequireRole({"VETERINARIAN", "HEAD_TRAINER", "CLUB_MANAGER"})
    public ResponseEntity<TrainingLockRow> issue(
            @Valid @RequestBody TrainingLockRequest request,
            @RequestAttribute(AuthInterceptor.USER_ATTR) AuthenticatedUser issuer,
            HttpServletRequest http) {
        TrainingLockRow body = service.issue(request, issuer, http.getRemoteAddr());
        return ResponseEntity.status(HttpStatus.CREATED).body(body);
    }

    /** Mở một lệnh ra sai hoặc đã hết lý do. Không nâng trạng thái con ngựa - đó là UC18. */
    @PostMapping("/{id}/release")
    @RequireRole({"VETERINARIAN", "CLUB_MANAGER"})
    public TrainingLockRow release(
            @PathVariable int id,
            @RequestAttribute(AuthInterceptor.USER_ATTR) AuthenticatedUser user,
            HttpServletRequest http) {
        return service.release(id, user, http.getRemoteAddr());
    }

    /** Màn quản lý: mọi lệnh đang hiệu lực, FULL lên trước, lệnh cũ lên trước. */
    @GetMapping("/active")
    public List<TrainingLockRow> active() {
        return service.listActive();
    }

    /** Bệnh sử khoá tập của một con ngựa, kể cả lệnh đã mở. */
    @GetMapping
    public List<TrainingLockRow> listForHorse(
            @RequestParam int horseId,
            @RequestAttribute(AuthInterceptor.USER_ATTR) AuthenticatedUser user) {
        return service.listForHorse(horseId, user);
    }
}
