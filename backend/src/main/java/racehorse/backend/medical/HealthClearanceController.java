package racehorse.backend.medical;

import java.util.List;

import org.springframework.web.bind.annotation.GetMapping;
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
 * UC18 - Clear Horse Health Status (Recovery Confirmation).
 *
 * Đường dẫn đặt theo tên màn hình trong đặc tả ("Recovery Confirmation") chứ
 * không gắn vào /api/horses/{id}: bảng HORSE do package horse sở hữu, và việc
 * xác nhận hồi phục là một hành vi y tế, cùng vai trò và cùng luồng với UC14.
 */
@RestController
@RequestMapping("/api/recovery-confirmations")
public class HealthClearanceController {

    private final HealthClearanceService service;

    public HealthClearanceController(HealthClearanceService service) {
        this.service = service;
    }

    /** Xác nhận hồi phục và trả con ngựa về diện sẵn sàng tập. */
    @PostMapping
    @RequireRole({"VETERINARIAN"})
    public RecoveryConfirmationResponse confirm(
            @Valid @RequestBody RecoveryConfirmationRequest request,
            @RequestAttribute(AuthInterceptor.USER_ATTR) AuthenticatedUser vet,
            HttpServletRequest http) {
        return service.confirm(request, vet, http.getRemoteAddr());
    }

    /**
     * Những chấn thương còn chặn việc clear. Màn hình gọi trước khi mở form,
     * để hiện sẵn danh sách cần tích thay vì để bác sĩ bấm rồi nhận lỗi 400.
     */
    @GetMapping("/blockers")
    public List<InjuryRepository.OpenInjury> blockers(
            @RequestParam int horseId,
            @RequestAttribute(AuthInterceptor.USER_ATTR) AuthenticatedUser user) {
        return service.openInjuries(horseId, user);
    }
}
