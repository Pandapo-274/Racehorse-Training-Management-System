package racehorse.backend.training;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
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
import racehorse.backend.auth.AuthInterceptor;
import racehorse.backend.auth.AuthenticatedUser;
import racehorse.backend.auth.RequireRole;

@RestController
@RequestMapping("/api/training-sessions")
public class TrainingSessionController {

    private final TrainingSessionService service;

    public TrainingSessionController(TrainingSessionService service) {
        this.service = service;
    }

    @PostMapping
    @RequireRole({"HEAD_TRAINER"})
    public ResponseEntity<TrainingSessionResponse> create(
            @Valid @RequestBody TrainingSessionRequest request,
            @RequestAttribute(AuthInterceptor.USER_ATTR) AuthenticatedUser user,
            HttpServletRequest http) {
        TrainingSessionResponse body = service.create(request, user, http.getRemoteAddr());
        return ResponseEntity.status(HttpStatus.CREATED).body(body);
    }

    @GetMapping
    @RequireRole({"HEAD_TRAINER", "CLUB_MANAGER", "GROOM"})
    public List<TrainingSessionResponse> list(
            @RequestParam(required = false) Integer horseId,
            @RequestParam(required = false) String status,
            @RequestAttribute(AuthInterceptor.USER_ATTR) AuthenticatedUser user) {
        return service.list(horseId, status, user);
    }

    @GetMapping("/{id}")
    @RequireRole({"HEAD_TRAINER", "CLUB_MANAGER", "GROOM"})
    public TrainingSessionResponse get(@PathVariable int id,
                                       @RequestAttribute(AuthInterceptor.USER_ATTR) AuthenticatedUser user) {
        return service.get(id, user);
    }

    @PutMapping("/{id}/start")
    @RequireRole({"GROOM", "HEAD_TRAINER"})
    public TrainingSessionResponse start(@PathVariable int id,
                                         @RequestAttribute(AuthInterceptor.USER_ATTR) AuthenticatedUser user,
                                         HttpServletRequest http) {
        return service.start(id, user, http.getRemoteAddr());
    }
}