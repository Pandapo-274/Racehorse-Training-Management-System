package racehorse.backend.vetcall;

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
public class VetCallController {

    private final VetCallService service;

    public VetCallController(VetCallService service) {
        this.service = service;
    }

    /** Nút "Call vet" trên màn Live session: gọi bác sĩ cho một cảnh báo. */
    @PostMapping("/alerts/{alertId}/vet-call")
    @RequireRole({"HEAD_TRAINER"})
    public VetCallResponse call(@RequestAttribute(AuthInterceptor.USER_ATTR) AuthenticatedUser me,
                                @PathVariable("alertId") int alertId,
                                @Valid @RequestBody VetCallRequest request,
                                HttpServletRequest http) {
        return service.call(me, alertId, request, http.getRemoteAddr());
    }

    /** status = PENDING | ANSWERED | ALL (mặc định ALL): ca chờ trả lời luôn đứng đầu. */
    @GetMapping("/vet-calls")
    @RequireRole({"HEAD_TRAINER", "VETERINARIAN"})
    public List<VetCallResponse> list(@RequestParam(value = "status", required = false) String status,
                                      @RequestParam(value = "limit", defaultValue = "30") int limit) {
        return service.list(status, limit);
    }

    @GetMapping("/vet-calls/{callId}")
    @RequireRole({"HEAD_TRAINER", "VETERINARIAN"})
    public VetCallResponse get(@PathVariable("callId") int callId) {
        return service.get(callId);
    }

    @PutMapping("/vet-calls/{callId}/answer")
    @RequireRole({"VETERINARIAN"})
    public VetCallResponse answer(@RequestAttribute(AuthInterceptor.USER_ATTR) AuthenticatedUser me,
                                  @PathVariable("callId") int callId,
                                  @Valid @RequestBody VetCallAnswer request,
                                  HttpServletRequest http) {
        return service.answer(me, callId, request, http.getRemoteAddr());
    }
}
