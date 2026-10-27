package racehorse.backend.race;

import java.time.LocalDate;
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

import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import racehorse.backend.auth.AuthInterceptor;
import racehorse.backend.auth.AuthenticatedUser;
import racehorse.backend.auth.RequireRole;

/**
 * Flow 5 - giải đua.
 *
 *  GET  /api/races?year=&horseId=   lịch sử và lịch đua (chủ ngựa: chỉ ngựa của mình)
 *  GET  /api/races/years            các năm có dữ liệu
 *  GET  /api/races/candidates       ngựa đăng ký được và lý do nếu không
 *  GET  /api/races/summary?year=    thành tích và tổng tiền thưởng trong năm
 *  POST /api/races                  đăng ký (Head Trainer)
 *  PUT  /api/races/{id}/result      nhập kết quả (Head Trainer, Club Manager)
 *  PUT  /api/races/{id}/scratch     rút khỏi giải trước ngày đua (Head Trainer)
 */
@RestController
@RequestMapping("/api/races")
public class RaceController {

    private final RaceService service;

    public RaceController(RaceService service) {
        this.service = service;
    }

    @GetMapping
    @RequireRole({"CLUB_MANAGER", "HEAD_TRAINER", "HORSE_OWNER"})
    public List<RaceModels.Entry> list(@RequestParam(required = false) Integer year,
                                       @RequestParam(required = false) Integer horseId,
                                       @RequestAttribute(AuthInterceptor.USER_ATTR) AuthenticatedUser user) {
        return service.list(year, horseId, user);
    }

    @GetMapping("/years")
    @RequireRole({"CLUB_MANAGER", "HEAD_TRAINER", "HORSE_OWNER"})
    public List<Integer> years() {
        return service.years();
    }

    @GetMapping("/candidates")
    @RequireRole({"HEAD_TRAINER"})
    public List<RaceModels.Candidate> candidates() {
        return service.candidates();
    }

    @GetMapping("/summary")
    @RequireRole({"CLUB_MANAGER", "HEAD_TRAINER"})
    public RaceModels.Summary summary(@RequestParam(required = false) Integer year) {
        return service.summary(year == null ? LocalDate.now().getYear() : year);
    }

    @PostMapping
    @RequireRole({"HEAD_TRAINER"})
    public ResponseEntity<RaceModels.Entry> register(@Valid @RequestBody RaceModels.RegisterRequest req,
                                                     @RequestAttribute(AuthInterceptor.USER_ATTR) AuthenticatedUser user,
                                                     HttpServletRequest http) {
        return ResponseEntity.status(HttpStatus.CREATED).body(service.register(req, user, http.getRemoteAddr()));
    }

    @PutMapping("/{id}/result")
    @RequireRole({"HEAD_TRAINER", "CLUB_MANAGER"})
    public RaceModels.Entry result(@PathVariable int id, @Valid @RequestBody RaceModels.ResultRequest req,
                                   @RequestAttribute(AuthInterceptor.USER_ATTR) AuthenticatedUser user,
                                   HttpServletRequest http) {
        return service.recordResult(id, req, user, http.getRemoteAddr());
    }

    @PutMapping("/{id}/scratch")
    @RequireRole({"HEAD_TRAINER"})
    public RaceModels.Entry scratch(@PathVariable int id, @Valid @RequestBody RaceModels.ScratchRequest req,
                                    @RequestAttribute(AuthInterceptor.USER_ATTR) AuthenticatedUser user,
                                    HttpServletRequest http) {
        return service.scratch(id, req.reason(), user, http.getRemoteAddr());
    }
}
