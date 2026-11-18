package racehorse.backend.controller;

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

import racehorse.backend.dto.FinishSessionRequest;
import racehorse.backend.dto.TrainingSessionRequest;
import racehorse.backend.dto.TrainingSessionResponse;
import racehorse.backend.dto.TrainingSessionScheduleRequest;
import racehorse.backend.repository.TrainingSessionRepository;
import racehorse.backend.security.AuthInterceptor;
import racehorse.backend.security.AuthenticatedUser;
import racehorse.backend.security.RequireRole;
import racehorse.backend.service.TrainingSessionService;

/*
 * UC10 - buổi tập (TRAINING_SESSION), tiền tố /api/training-sessions.
 *   POST /                  HLV xếp lịch một buổi tập cho một giai đoạn của giáo án ACTIVE
 *   GET  /                  danh sách (Groom chỉ thấy buổi của mình, chủ ngựa chỉ ngựa của mình)
 *   GET  /assignees         Groom trong đội HLV để chọn khi xếp lịch
 *   GET  /{id}              chi tiết
 *   PUT  /{id}/start        Groom/HLV bắt đầu: SCHEDULED -> RUNNING
 *   PUT  /{id}/finish       kết thúc: RUNNING -> COMPLETED (hoặc STOPPED_EARLY nếu sớm hơn giờ dự kiến)
 *   PUT  /{id}/schedule     HLV đổi giờ / Groom / thời lượng của buổi SCHEDULED
 *   PUT  /{id}/cancel       HLV hoặc Manager huỷ buổi SCHEDULED
 * Gửi nhịp tim/vận tốc trong lúc tập: POST /api/sessions/{id}/vitals (AlertController).
 * Dừng vì ngựa bất thường: PUT /api/training-sessions/{id}/stop (IncidentController).
 * Nhận xét sau buổi tập: PUT /api/sessions/{id}/evaluation (SessionController).
 */
@RestController
@RequestMapping("/api/training-sessions")
public class TrainingSessionController {

    private final TrainingSessionService service;

    public TrainingSessionController(TrainingSessionService service) {
        this.service = service;
    }

    @PostMapping
    @RequireRole({ "HEAD_TRAINER" })
    public ResponseEntity<TrainingSessionResponse> create(
            @Valid @RequestBody TrainingSessionRequest request,
            @RequestAttribute(AuthInterceptor.USER_ATTR) AuthenticatedUser user,
            HttpServletRequest http) {
        TrainingSessionResponse body = service.create(request, user, http.getRemoteAddr());
        return ResponseEntity.status(HttpStatus.CREATED).body(body);
    }

    @GetMapping
    @RequireRole({ "HEAD_TRAINER", "CLUB_MANAGER", "GROOM", "HORSE_OWNER" })
    public List<TrainingSessionResponse> list(
            @RequestParam(required = false) Integer horseId,
            @RequestParam(required = false) String status,
            @RequestAttribute(AuthInterceptor.USER_ATTR) AuthenticatedUser user) {
        return service.list(horseId, status, user);
    }

    /**
     * GET /api/training-sessions/assignees
     * V37: Head Trainer chỉ nhận được danh sách Groom thuộc quyền quản lý của mình (tối đa 3 Groom).
     */
    @GetMapping("/assignees")
    @RequireRole({ "HEAD_TRAINER" })
    public List<TrainingSessionRepository.GroomOption> assignees(
            @RequestAttribute(AuthInterceptor.USER_ATTR) AuthenticatedUser user) {
        return service.listActiveGrooms(user);
    }

    @GetMapping("/{id}")
    @RequireRole({ "HEAD_TRAINER", "CLUB_MANAGER", "GROOM", "HORSE_OWNER" })
    public TrainingSessionResponse get(@PathVariable int id,
            @RequestAttribute(AuthInterceptor.USER_ATTR) AuthenticatedUser user) {
        return service.get(id, user);
    }

    @PutMapping("/{id}/start")
    @RequireRole({ "GROOM", "HEAD_TRAINER" })
    public TrainingSessionResponse start(@PathVariable int id,
            @RequestAttribute(AuthInterceptor.USER_ATTR) AuthenticatedUser user,
            HttpServletRequest http) {
        return service.start(id, user, http.getRemoteAddr());
    }

    /**
     * PUT /api/training-sessions/{id}/finish
     * Kết thúc bình thường (RUNNING -> COMPLETED) hoặc kết thúc sớm bởi Groom (RUNNING -> STOPPED_EARLY)
     * với lý do từ danh sách và yêu cầu đã báo cáo cả 2 chỉ số Heart Rate + Speed.
     */
    @PutMapping("/{id}/finish")
    @RequireRole({ "GROOM", "HEAD_TRAINER" })
    public TrainingSessionResponse finish(@PathVariable int id,
            // required = false: kết thúc bình thường có thể không gửi body.
            @RequestBody(required = false) FinishSessionRequest request,
            @RequestAttribute(AuthInterceptor.USER_ATTR) AuthenticatedUser user,
            HttpServletRequest http) {
        return service.finish(id, request, user, http.getRemoteAddr());
    }

    @PutMapping("/{id}/schedule")
    @RequireRole({ "HEAD_TRAINER" })
    public TrainingSessionResponse reschedule(
            @PathVariable int id,
            @Valid @RequestBody TrainingSessionScheduleRequest request,
            @RequestAttribute(AuthInterceptor.USER_ATTR) AuthenticatedUser user,
            HttpServletRequest http) {

        return service.reschedule(
                id,
                request,
                user,
                http.getRemoteAddr());
    }

    @PutMapping("/{id}/cancel")
    @RequireRole({ "HEAD_TRAINER", "CLUB_MANAGER" })
    public TrainingSessionResponse cancel(
            @PathVariable int id,
            @RequestAttribute(AuthInterceptor.USER_ATTR) AuthenticatedUser user,
            HttpServletRequest http) {
        return service.cancel(id, user, http.getRemoteAddr());
    }
}
