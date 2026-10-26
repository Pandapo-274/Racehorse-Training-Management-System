package racehorse.backend.session;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestAttribute;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import racehorse.backend.auth.AuthInterceptor;
import racehorse.backend.auth.AuthenticatedUser;
import racehorse.backend.auth.RequireRole;

@RestController
@RequestMapping("/api/sessions")
public class TrialRunController {

    private final TrialRunService service;

    public TrialRunController(TrialRunService service) {
        this.service = service;
    }

    /** Head Trainer đánh dấu "chạy thử" và gắn link video ở màn Session review. */
    @PutMapping("/{id}/trial-run")
    @RequireRole({"HEAD_TRAINER"})
    public TrialRunResponse update(@PathVariable("id") int id,
                                   @Valid @RequestBody TrialRunRequest request,
                                   @RequestAttribute(AuthInterceptor.USER_ATTR) AuthenticatedUser trainer,
                                   HttpServletRequest http) {
        return service.update(trainer, id, request, http.getRemoteAddr());
    }
}
