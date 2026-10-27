package racehorse.backend.staff;

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
import org.springframework.web.bind.annotation.RestController;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import racehorse.backend.auth.AuthInterceptor;
import racehorse.backend.auth.AuthenticatedUser;
import racehorse.backend.auth.RequireRole;

/**
 * UC19 - the club manager's Staff screen.
 *
 * CLUB_MANAGER ONLY. This is the single route in the system that returns
 * every account with its full name, email and phone number, so the role list
 * here has to be as narrow as it can be. A head trainer who needs to know
 * which grooms exist already has GET /api/training-sessions/assignees, which
 * returns just that group and carries no contact details.
 *
 * Write half: staff accounts (Head Trainer, Vet, Groom) are no longer
 * self-registered - AuthService only lets HORSE_OWNER sign up - so the manager
 * creates them here, moves them between the three staff roles, and can
 * suspend / re-activate any non-manager account. Permissions belong to roles
 * (RBAC), so changing someone's role is how the manager changes what they can
 * do. See StaffService for the rules.
 */
@RestController
@RequestMapping("/api/staff")
public class StaffController {

    private final StaffRepository repository;
    private final StaffService service;

    public StaffController(StaffRepository repository, StaffService service) {
        this.repository = repository;
        this.service = service;
    }

    @GetMapping
    @RequireRole({"CLUB_MANAGER"})
    public List<StaffGroup> list() {
        return repository.findAllGrouped();
    }

    @PostMapping
    @RequireRole({"CLUB_MANAGER"})
    public ResponseEntity<StaffRow> create(
            @Valid @RequestBody CreateRequest request,
            @RequestAttribute(AuthInterceptor.USER_ATTR) AuthenticatedUser user,
            HttpServletRequest http) {
        StaffRow body = service.create(request, user, http.getRemoteAddr());
        return ResponseEntity.status(HttpStatus.CREATED).body(body);
    }

    @PutMapping("/{id}/status")
    @RequireRole({"CLUB_MANAGER"})
    public StaffService.StaffUpdate setStatus(
            @PathVariable int id,
            @Valid @RequestBody StatusRequest request,
            @RequestAttribute(AuthInterceptor.USER_ATTR) AuthenticatedUser user,
            HttpServletRequest http) {
        return service.setStatus(id, request.status(), user, http.getRemoteAddr());
    }

    @PutMapping("/{id}/role")
    @RequireRole({"CLUB_MANAGER"})
    public StaffService.StaffUpdate changeRole(
            @PathVariable int id,
            @Valid @RequestBody RoleRequest request,
            @RequestAttribute(AuthInterceptor.USER_ATTR) AuthenticatedUser user,
            HttpServletRequest http) {
        return service.changeRole(id, request.roleName(), user, http.getRemoteAddr());
    }

    /** Cùng luật kiểm với RegisterRequest. Mật khẩu là mật khẩu TẠM do manager đặt:
        người dùng phải đổi ở lần đăng nhập đầu (V14). */
    public record CreateRequest(
            @NotBlank(message = "Username is required")
            @Size(min = 3, max = 50, message = "Username must be 3-50 characters")
            @Pattern(regexp = "^[A-Za-z0-9._]*$", message = "Username may only contain letters, digits, dots and underscores")
            String username,

            @NotBlank(message = "Password is required")
            @Size(min = 8, max = 72, message = "Password must be 8-72 characters")
            @Pattern(regexp = "^(?=.*[A-Za-z])(?=.*\\d).*$", message = "Password must contain both letters and digits")
            String password,

            @NotBlank(message = "Full name is required")
            @Size(max = 100, message = "Full name must be at most 100 characters")
            String fullName,

            @NotBlank(message = "Email is required")
            @Email(message = "Email is invalid")
            @Size(max = 100, message = "Email must be at most 100 characters")
            String email,

            @Pattern(regexp = "^(\\+?[0-9]{9,15})?$", message = "Phone number is invalid")
            String phone,

            @NotBlank(message = "Role is required")
            String roleName) {}

    public record RoleRequest(
            @NotBlank(message = "Role is required")
            String roleName) {}

    public record StatusRequest(
            @NotBlank(message = "Status is required")
            @Pattern(regexp = "^(ACTIVE|SUSPENDED)$", message = "Status must be ACTIVE or SUSPENDED")
            String status) {}
}