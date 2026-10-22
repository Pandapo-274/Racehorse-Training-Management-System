package racehorse.backend.staff;

import java.util.List;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import racehorse.backend.auth.RequireRole;

/**
 * UC19 (read half) - the club manager's Staff screen.
 *
 * CLUB_MANAGER ONLY. This is the single route in the system that returns
 * every account with its full name, email and phone number, so the role list
 * here has to be as narrow as it can be. A head trainer who needs to know
 * which grooms exist already has GET /api/training-sessions/assignees, which
 * returns just that group and carries no contact details.
 *
 * There is no write route yet: changing a role or a permission belongs to the
 * Permissions screen, which is not built. Staff is read-only.
 */
@RestController
@RequestMapping("/api/staff")
public class StaffController {

    private final StaffRepository repository;

    public StaffController(StaffRepository repository) {
        this.repository = repository;
    }

    @GetMapping
    @RequireRole({"CLUB_MANAGER"})
    public List<StaffGroup> list() {
        return repository.findAllGrouped();
    }
}
