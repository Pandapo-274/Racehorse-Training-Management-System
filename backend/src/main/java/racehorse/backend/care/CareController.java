package racehorse.backend.care;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;

import org.springframework.format.annotation.DateTimeFormat;
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

/**
 * Flow 4 - khẩu phần ăn (FEEDING_SCHEDULE) và việc chăm sóc hằng ngày (DAILY_TASK).
 *
 *  GET  /api/feeding?horseId=            khẩu phần đã duyệt
 *  PUT  /api/feeding/horse/{horseId}     thay khẩu phần (Head Trainer, Vet duyệt)
 *  GET  /api/daily-tasks?date=&horseId=  việc trong ngày (Groom chỉ thấy việc của mình)
 *  POST /api/daily-tasks                 giao việc (Head Trainer, Club Manager)
 *  PUT  /api/daily-tasks/{id}/complete   đánh dấu xong
 *  PUT  /api/daily-tasks/{id}/undo       bỏ đánh dấu (trong ngày)
 *  DELETE /api/daily-tasks/{id}          xoá việc chưa làm
 */
@RestController
@RequestMapping("/api")
public class CareController {

    private final CareService service;

    public CareController(CareService service) {
        this.service = service;
    }

    @GetMapping("/feeding")
    @RequireRole({"CLUB_MANAGER", "HEAD_TRAINER", "VETERINARIAN", "GROOM"})
    public List<CareModels.FeedingItem> feeding(@RequestParam(required = false) Integer horseId) {
        return service.feeding(horseId);
    }

    @PutMapping("/feeding/horse/{horseId}")
    @RequireRole({"HEAD_TRAINER", "VETERINARIAN"})
    public List<CareModels.FeedingItem> replaceRation(
            @PathVariable int horseId,
            @Valid @RequestBody CareModels.RationRequest request,
            @RequestAttribute(AuthInterceptor.USER_ATTR) AuthenticatedUser user,
            HttpServletRequest http) {
        return service.replaceRation(horseId, request, user, http.getRemoteAddr());
    }

    @GetMapping("/daily-tasks")
    @RequireRole({"CLUB_MANAGER", "HEAD_TRAINER", "GROOM"})
    public List<CareModels.Task> tasks(
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date,
            @RequestParam(required = false) Integer horseId,
            @RequestAttribute(AuthInterceptor.USER_ATTR) AuthenticatedUser user) {
        return service.tasks(date, horseId, user);
    }

    @PostMapping("/daily-tasks")
    @RequireRole({"CLUB_MANAGER", "HEAD_TRAINER"})
    public ResponseEntity<CareModels.AssignResult> assign(
            @Valid @RequestBody CareModels.AssignRequest request,
            @RequestAttribute(AuthInterceptor.USER_ATTR) AuthenticatedUser user,
            HttpServletRequest http) {
        return ResponseEntity.status(HttpStatus.CREATED).body(service.assign(request, user, http.getRemoteAddr()));
    }

    @PutMapping("/daily-tasks/{id}/complete")
    @RequireRole({"GROOM", "HEAD_TRAINER"})
    public CareModels.Task complete(@PathVariable int id,
                                    @RequestAttribute(AuthInterceptor.USER_ATTR) AuthenticatedUser user,
                                    HttpServletRequest http) {
        return service.complete(id, user, http.getRemoteAddr());
    }

    @PutMapping("/daily-tasks/{id}/undo")
    @RequireRole({"GROOM", "HEAD_TRAINER"})
    public CareModels.Task undo(@PathVariable int id,
                                @RequestAttribute(AuthInterceptor.USER_ATTR) AuthenticatedUser user,
                                HttpServletRequest http) {
        return service.undo(id, user, http.getRemoteAddr());
    }

    @DeleteMapping("/daily-tasks/{id}")
    @RequireRole({"CLUB_MANAGER", "HEAD_TRAINER"})
    public Map<String, String> delete(@PathVariable int id,
                                      @RequestAttribute(AuthInterceptor.USER_ATTR) AuthenticatedUser user,
                                      HttpServletRequest http) {
        service.delete(id, user, http.getRemoteAddr());
        return Map.of("message", "Task deleted");
    }
}
