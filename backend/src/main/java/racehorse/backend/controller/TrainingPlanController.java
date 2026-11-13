package racehorse.backend.controller;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
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
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import racehorse.backend.dto.TrainingPlanRequest;
import racehorse.backend.dto.TrainingPlanResponse;
import racehorse.backend.security.AuthInterceptor;
import racehorse.backend.security.AuthenticatedUser;
import racehorse.backend.security.RequireRole;
import racehorse.backend.service.TrainingPlanService;

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

    @GetMapping({"/grooms", "/assignees"})
    @RequireRole({"HEAD_TRAINER", "CLUB_MANAGER"})
    public List<TrainingPlanService.TrainerGroomOption> listMyGrooms(
            @RequestAttribute(AuthInterceptor.USER_ATTR) AuthenticatedUser user) {
        return service.listGroomsForPlan(user);
    }

    @GetMapping
    @RequireRole({"HEAD_TRAINER", "CLUB_MANAGER", "HORSE_OWNER"})
    public List<TrainingPlanResponse.Summary> list(@RequestParam(required = false) Integer horseId,
                                                   @RequestParam(required = false) String status,
                                                   @RequestAttribute(AuthInterceptor.USER_ATTR) AuthenticatedUser user) {
        return service.list(horseId, status, user);
    }

    @GetMapping("/{id:\\d+}")
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

    /** Chủ ngựa duyệt hoặc từ chối bản cập nhật giáo án đang chờ. */
    @PutMapping("/{id}/update-review")
    @RequireRole({"HORSE_OWNER"})
    public TrainingPlanResponse reviewPendingUpdate(
            @PathVariable int id,
            @Valid @RequestBody TrainingPlanRequest.OwnerReview request,
            @RequestAttribute(AuthInterceptor.USER_ATTR) AuthenticatedUser user,
            HttpServletRequest http) {
        return service.reviewPendingUpdate(id, request.approve(), request.note(), user, http.getRemoteAddr());
    }

    /** Chủ ngựa gửi phản hồi/nhận xét (optional) cho giáo án. */
    @PutMapping("/{id}/owner-feedback")
    @RequireRole({"HORSE_OWNER"})
    public TrainingPlanResponse ownerFeedback(
            @PathVariable int id,
            @Valid @RequestBody TrainingPlanRequest.OwnerFeedback request,
            @RequestAttribute(AuthInterceptor.USER_ATTR) AuthenticatedUser user,
            HttpServletRequest http) {
        return service.ownerFeedback(id, request.note(), user, http.getRemoteAddr());
    }

    /**
     * Cập nhật giáo án: Chỉ cập nhật nội dung các phase và groom.
     * Thông tin chính của plan như horseId, startDate, endDate được giữ nguyên.
     */
    @RequestMapping(
            value = {"/{id}", "/{id:\\d+}", "/{id}/update", "/{id:\\d+}/update"},
            method = {RequestMethod.PUT, RequestMethod.POST}
    )
    @RequireRole({"HEAD_TRAINER"})
    public TrainingPlanResponse update(
            @PathVariable int id,
            @Valid @RequestBody TrainingPlanRequest.Update request,
            @RequestAttribute(AuthInterceptor.USER_ATTR) AuthenticatedUser user,
            HttpServletRequest http) {
        return service.update(id, request, user, http.getRemoteAddr());
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
