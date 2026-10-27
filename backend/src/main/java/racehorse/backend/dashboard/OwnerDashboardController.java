package racehorse.backend.dashboard;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestAttribute;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import racehorse.backend.auth.AuthInterceptor;
import racehorse.backend.auth.AuthenticatedUser;
import racehorse.backend.auth.RequireRole;

/**
 * Màn tổng quan của Horse Owner.
 *
 * Một endpoint trả cả màn, giống TrainerDashboardController. ownerId luôn lấy
 * từ token nên chủ ngựa chỉ thấy ngựa, chi phí và tiền thưởng của chính mình.
 */
@RestController
@RequestMapping("/api/owner/dashboard")
public class OwnerDashboardController {

    private final OwnerDashboardService service;

    public OwnerDashboardController(OwnerDashboardService service) {
        this.service = service;
    }

    /** @param period khoảng thời gian của bảng chi phí: LAST_90_DAYS, THIS_YEAR hoặc ALL. */
    @GetMapping
    @RequireRole({"HORSE_OWNER"})
    public OwnerDashboard.Response get(
            @RequestParam(defaultValue = OwnerDashboardService.DEFAULT_PERIOD) String period,
            @RequestAttribute(AuthInterceptor.USER_ATTR) AuthenticatedUser user) {
        return service.load(user.userId(), period);
    }
}
