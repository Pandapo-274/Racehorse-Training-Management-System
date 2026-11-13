package racehorse.backend.controller;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import racehorse.backend.dto.ManagerDashboard;
import racehorse.backend.security.RequireRole;
import racehorse.backend.service.ManagerDashboardService;

/** Trang "Club overview" của Club Manager. weeks: số tuần của biểu đồ khối lượng tập (4 đến 26). */
@RestController
@RequestMapping("/api/manager/dashboard")
public class ManagerDashboardController {

    private final ManagerDashboardService service;

    public ManagerDashboardController(ManagerDashboardService service) {
        this.service = service;
    }

    @GetMapping
    @RequireRole({"CLUB_MANAGER"})
    public ManagerDashboard.Response get(
            @RequestParam(defaultValue = "" + ManagerDashboardService.DEFAULT_WEEKS) int weeks) {
        return service.load(weeks);
    }
}
