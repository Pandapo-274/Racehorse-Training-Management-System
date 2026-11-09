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
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import racehorse.backend.auth.AuthInterceptor;
import racehorse.backend.auth.AuthenticatedUser;
import racehorse.backend.auth.RequireRole;

/**
 * UC19 - the club manager's Staff screen.
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

    /** Sửa họ tên, email, số điện thoại (không đổi username, vai trò, mật khẩu). */
    @PutMapping("/{id}")
    @RequireRole({"CLUB_MANAGER"})
    public StaffRow updateContact(
            @PathVariable int id,
            @Valid @RequestBody ContactRequest request,
            @RequestAttribute(AuthInterceptor.USER_ATTR) AuthenticatedUser user,
            HttpServletRequest http) {
        return service.updateContact(id, request, user, http.getRemoteAddr());
    }

    /** Mật khẩu tạm mới: phải đổi ở lần đăng nhập kế, mọi phiên cũ bị đá, gỡ khoá đăng nhập. */
    @PutMapping("/{id}/password")
    @RequireRole({"CLUB_MANAGER"})
    public StaffRow setTemporaryPassword(
            @PathVariable int id,
            @Valid @RequestBody PasswordRequest request,
            @RequestAttribute(AuthInterceptor.USER_ATTR) AuthenticatedUser user,
            HttpServletRequest http) {
        return service.setTemporaryPassword(id, request.password(), user, http.getRemoteAddr());
    }

    public record ContactRequest(
            @NotBlank(message = "Full name is required")
            @Size(max = 100, message = "Full name must be at most 100 characters")
            String fullName,

            @NotBlank(message = "Email is required")
            @Email(message = "Email is invalid")
            @Size(max = 100, message = "Email must be at most 100 characters")
            String email,

            @Pattern(regexp = "^(\\+?[0-9]{9,15})?$", message = "Phone number is invalid")
            String phone) {}

    public record PasswordRequest(
            @NotBlank(message = "Password is required")
            @Size(min = 8, max = 72, message = "Password must be 8-72 characters")
            @Pattern(regexp = "^(?=.*[A-Za-z])(?=.*\\d).*$", message = "Password must contain both letters and digits")
            String password) {}

    /** V37: Club Manager gán hoặc gỡ Groom cho một Head Trainer (tối đa 3 Groom / Head Trainer). */
    @PutMapping("/{id}/trainer")
    @RequireRole({"CLUB_MANAGER"})
    public StaffRow assignTrainer(
            @PathVariable int id,
            @RequestBody TrainerAssignRequest request,
            @RequestAttribute(AuthInterceptor.USER_ATTR) AuthenticatedUser user,
            HttpServletRequest http) {
        return service.assignTrainer(id, request == null ? null : request.trainerId(), user, http.getRemoteAddr());
    }

    /** V37: Club Manager thiết lập hạn mức số ngựa tối đa cho một Head Trainer. */
    @PutMapping("/{id}/max-horses")
    @RequireRole({"CLUB_MANAGER"})
    public StaffRow updateMaxHorses(
            @PathVariable int id,
            @Valid @RequestBody MaxHorsesRequest request,
            @RequestAttribute(AuthInterceptor.USER_ATTR) AuthenticatedUser user,
            HttpServletRequest http) {
        return service.updateMaxHorses(id, request.maxHorses(), user, http.getRemoteAddr());
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
            String roleName,

            Integer trainerId,
            Integer maxHorses) {

        public CreateRequest(String username, String password, String fullName,
                             String email, String phone, String roleName) {
            this(username, password, fullName, email, phone, roleName, null, null);
        }
    }

    public record RoleRequest(
            @NotBlank(message = "Role is required")
            String roleName) {}

    public record StatusRequest(
            @NotBlank(message = "Status is required")
            @Pattern(regexp = "^(ACTIVE|SUSPENDED)$", message = "Status must be ACTIVE or SUSPENDED")
            String status) {}

    public record TrainerAssignRequest(Integer trainerId) {}

    public record MaxHorsesRequest(
            @NotNull(message = "Max horses is required")
            @Min(value = 1, message = "Max horses must be at least 1")
            @Max(value = 100, message = "Max horses must be at most 100")
            Integer maxHorses) {}
}
