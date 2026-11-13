package racehorse.backend.controller;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import racehorse.backend.dto.PermissionMatrix;
import racehorse.backend.security.RequireRole;
import racehorse.backend.service.PermissionService;

/**
 * UC19 (phần xem) - trang Permissions của Club Manager: vai trò nào được làm
 * chức năng nào, đúng như hệ thống đang chặn.
 *
 * Chỉ để xem. Quyền gắn với vai trò và được thực thi bằng @RequireRole; muốn
 * đổi việc một người làm được thì đổi vai trò của họ ở trang Staff.
 */
@RestController
@RequestMapping("/api/permissions")
public class PermissionController {

    private final PermissionService service;

    public PermissionController(PermissionService service) {
        this.service = service;
    }

    @GetMapping
    @RequireRole({"CLUB_MANAGER"})
    public PermissionMatrix.Response matrix() {
        return service.load();
    }
}
