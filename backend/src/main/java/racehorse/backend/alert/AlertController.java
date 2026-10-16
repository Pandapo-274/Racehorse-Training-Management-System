package racehorse.backend.alert;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import java.util.List;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestAttribute;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import racehorse.backend.auth.AuthInterceptor;
import racehorse.backend.auth.AuthenticatedUser;
import racehorse.backend.auth.RequireRole;

@RestController
@RequestMapping("/api")
public class AlertController {

    private final AlertService service;

    public AlertController(AlertService service) {
        this.service = service;
    }

    /** Cảm biến / Groom / Trainer gửi số đo trong lúc ngựa chạy. */
    @PostMapping("/sessions/{sessionId}/vitals")
    @RequireRole({"HEAD_TRAINER", "GROOM"})
    public VitalsResult recordVitals(@RequestAttribute(AuthInterceptor.USER_ATTR) AuthenticatedUser me,
                                     @PathVariable("sessionId") int sessionId,
                                     @Valid @RequestBody VitalsRequest request) {
        return service.recordVitals(me, sessionId, request);
    }

    /**
     * Màn "Live Session Monitor".
     * Mở màn hình: GET /api/alerts            -> 50 cảnh báo chưa xử lý, mới nhất trước.
     * Polling mỗi 3-5s: GET /api/alerts?afterId=<alertId lớn nhất đã có> -> chỉ cảnh báo mới.
     * Xem cả lịch sử đã xử lý: thêm openOnly=false.
     */
    @GetMapping("/alerts")
    @RequireRole({"HEAD_TRAINER", "CLUB_MANAGER"})
    public List<AlertResponse> list(@RequestParam(value = "afterId", required = false) Integer afterId,
                                    @RequestParam(value = "openOnly", defaultValue = "true") boolean openOnly,
                                    @RequestParam(value = "limit", defaultValue = "50") int limit) {
        return service.list(afterId, openOnly, limit);
    }

    @PutMapping("/alerts/{alertId}/resolve")
    @RequireRole({"HEAD_TRAINER"})
    public AlertResponse resolve(@RequestAttribute(AuthInterceptor.USER_ATTR) AuthenticatedUser me,
                                 @PathVariable("alertId") int alertId,
                                 @Valid @RequestBody ResolveAlertRequest request,
                                 HttpServletRequest http) {
        return service.resolve(me, alertId, request, http.getRemoteAddr());
    }
}
