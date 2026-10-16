package racehorse.backend.session;

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
@RequestMapping("/api/sessions")
public class SessionController {

    private final SessionService service;

    public SessionController(SessionService service) {
        this.service = service;
    }

    /** Màn "Session Evaluation": lọc theo trạng thái (vd. RUNNING, STOPPED_EARLY) để tìm ca cần chấm. */
    @GetMapping
    @RequireRole({"HEAD_TRAINER", "CLUB_MANAGER"})
    public List<SessionResponse> list(@RequestParam(value = "status", required = false) String status,
                                      @RequestParam(value = "horseId", required = false) Integer horseId,
                                      @RequestParam(value = "limit", defaultValue = "50") int limit) {
        return service.list(status, horseId, limit);
    }

    @GetMapping("/{id}")
    @RequireRole({"HEAD_TRAINER", "CLUB_MANAGER"})
    public SessionResponse get(@PathVariable("id") int id) {
        return service.get(id);
    }

    @PutMapping("/{id}/evaluation")
    @RequireRole({"HEAD_TRAINER"})
    public SessionResponse evaluate(@RequestAttribute(AuthInterceptor.USER_ATTR) AuthenticatedUser me,
                                    @PathVariable("id") int id,
                                    @Valid @RequestBody EvaluateSessionRequest request,
                                    HttpServletRequest http) {
        return service.evaluate(me, id, request, http.getRemoteAddr());
    }
}
