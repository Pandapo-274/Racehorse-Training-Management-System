package racehorse.backend.auth;

import java.util.Map;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestAttribute;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** CHỈ để test RBAC. Xoá file này khi đã có controller thật dùng @RequireRole (vd UC Manage Roles). */
@RestController
@RequestMapping("/api/rbac-demo")
public class RbacDemoController {

    @GetMapping("/manager-only")
    @RequireRole({"CLUB_MANAGER"})
    public Map<String, Object> managerOnly(@RequestAttribute(AuthInterceptor.USER_ATTR) AuthenticatedUser me) {
        return Map.of("message", "Chào sếp " + me.username(), "role", me.role());
    }
}
