package racehorse.backend.health;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import racehorse.backend.auth.RequireRole;

@RestController
@RequestMapping("/api/health")
public class HealthController {

    private final HealthService service;

    public HealthController(HealthService service) {
        this.service = service;
    }

    /** Màn "Health Status Board". Bác sĩ thú y là chính; Head Trainer và Manager được xem (chỉ đọc). */
    @GetMapping("/overview")
    @RequireRole({"VETERINARIAN", "HEAD_TRAINER", "CLUB_MANAGER"})
    public HealthOverviewResponse overview(@RequestParam(value = "status", required = false) String status) {
        return service.overview(status);
    }
}
