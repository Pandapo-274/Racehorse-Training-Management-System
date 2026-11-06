package racehorse.backend.training;

import java.util.List;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
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

@RestController
@RequestMapping("/api/training-plans")
public class TrainingPlanController {

    private final TrainingPlanService service;

    public TrainingPlanController(TrainingPlanService service) {
        this.service = service;
    }

    @PostMapping
    @RequireRole({"HEAD_TRAINER"})
    public ResponseEntity<TrainingPlanResponse> create(
            @Valid @RequestBody TrainingPlanRequest request,
            @RequestAttribute(AuthInterceptor.USER_ATTR) AuthenticatedUser user,
            HttpServletRequest http) {
        TrainingPlanResponse body = service.create(request, user, http.getRemoteAddr());
        return ResponseEntity.status(HttpStatus.CREATED).body(body);
    }

    @GetMapping
    @RequireRole({"HEAD_TRAINER", "CLUB_MANAGER", "HORSE_OWNER"})
    public List<TrainingPlanResponse.Summary> list(@RequestParam(required = false) Integer horseId,
                                                   @RequestParam(required = false) String status,
                                                   @RequestAttribute(AuthInterceptor.USER_ATTR) AuthenticatedUser user) {
        return service.list(horseId, status, user);
    }

    @GetMapping("/{id}")
    @RequireRole({"HEAD_TRAINER", "CLUB_MANAGER", "HORSE_OWNER"})
    public TrainingPlanResponse get(@PathVariable int id,
                                    @RequestAttribute(AuthInterceptor.USER_ATTR) AuthenticatedUser user) {
        return service.get(id, user);
    }

    /** Chủ ngựa duyệt hoặc từ chối giáo án đang chờ (service kiểm đúng chủ). */
    @PutMapping("/{id}/owner-review")
    @RequireRole({"HORSE_OWNER"})
    public TrainingPlanResponse ownerReview(
            @PathVariable int id,
            @Valid @RequestBody TrainingPlanRequest.OwnerReview request,
            @RequestAttribute(AuthInterceptor.USER_ATTR) AuthenticatedUser user,
            HttpServletRequest http) {
        return service.ownerReview(id, request.approve(), request.note(), user, http.getRemoteAddr());
    }

    /** Chỉ giáo án chưa từng chạy; giáo án đã chạy thì kết thúc bằng COMPLETED, không xoá. */
    @DeleteMapping("/{id}")
    @RequireRole({"HEAD_TRAINER"})
    public ResponseEntity<Void> delete(@PathVariable int id,
                                       @RequestAttribute(AuthInterceptor.USER_ATTR) AuthenticatedUser user,
                                       HttpServletRequest http) {
        service.deleteDraft(id, user, http.getRemoteAddr());
        return ResponseEntity.noContent().build();
    }

    @PutMapping("/{id}/status")
    @RequireRole({"HEAD_TRAINER"})
    public TrainingPlanResponse changeStatus(
            @PathVariable int id,
            @Valid @RequestBody TrainingPlanRequest.StatusChange request,
            @RequestAttribute(AuthInterceptor.USER_ATTR) AuthenticatedUser user,
            HttpServletRequest http) {
        return service.changeStatus(id, request.status(), user, http.getRemoteAddr());
    }
}