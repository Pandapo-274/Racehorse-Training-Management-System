package racehorse.backend.incident;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import java.util.List;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
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
public class IncidentController {

    private final IncidentService service;

    public IncidentController(IncidentService service) {
        this.service = service;
    }

    /** Nút "Stop session" trên trang Groom (và Head Trainer). Kèm lý do bắt buộc. */
    @PutMapping("/training-sessions/{id}/stop")
    @RequireRole({"GROOM", "HEAD_TRAINER"})
    public StopSessionResponse stop(@PathVariable("id") int id,
                                    @Valid @RequestBody StopSessionRequest request,
                                    @RequestAttribute(AuthInterceptor.USER_ATTR) AuthenticatedUser user,
                                    HttpServletRequest http) {
        return service.stopSession(user, id, request, http.getRemoteAddr());
    }

    /** Hàng chờ khám trên dashboard Vet: GET /api/incident-reports (mặc định chỉ báo cáo còn mở). */
    @GetMapping("/incident-reports")
    @RequireRole({"VETERINARIAN", "HEAD_TRAINER", "CLUB_MANAGER", "GROOM", "HORSE_OWNER"})
    public List<IncidentReportResponse> list(@RequestParam(value = "status", required = false) String status,
                                             @RequestParam(value = "horseId", required = false) Integer horseId,
                                             @RequestParam(value = "limit", defaultValue = "50") int limit,
                                             @RequestAttribute(AuthInterceptor.USER_ATTR) AuthenticatedUser user) {
        return service.list(status, horseId, limit, user);
    }

    @GetMapping("/incident-reports/{id}")
    @RequireRole({"VETERINARIAN", "HEAD_TRAINER", "CLUB_MANAGER", "GROOM", "HORSE_OWNER"})
    public IncidentReportResponse get(@PathVariable("id") int id,
                                      @RequestAttribute(AuthInterceptor.USER_ATTR) AuthenticatedUser user) {
        return service.get(id, user);
    }

    @PutMapping("/incident-reports/{id}/receive")
    @RequireRole({"VETERINARIAN"})
    public IncidentReportResponse receive(@PathVariable("id") int id,
                                          @RequestAttribute(AuthInterceptor.USER_ATTR) AuthenticatedUser vet,
                                          HttpServletRequest http) {
        return service.receive(vet, id, http.getRemoteAddr());
    }
}
