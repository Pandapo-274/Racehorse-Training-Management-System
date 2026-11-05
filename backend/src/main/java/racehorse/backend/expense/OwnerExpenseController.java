package racehorse.backend.expense;

import java.time.LocalDate;

import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestAttribute;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import racehorse.backend.auth.AuthInterceptor;
import racehorse.backend.auth.AuthenticatedUser;
import racehorse.backend.auth.RequireRole;
import racehorse.backend.expense.ExpenseModels.ExpenseList;

/**
 * #9: each cost line of the owner's own horses (voided lines excluded), so the
 * owner can check what makes up the totals on the dashboard.
 *
 *   GET /api/owner/expenses?horseId=&category=&from=&to=&limit=
 */
@RestController
@RequestMapping("/api/owner/expenses")
public class OwnerExpenseController {

    private final ExpenseService service;

    public OwnerExpenseController(ExpenseService service) {
        this.service = service;
    }

    @GetMapping
    @RequireRole({"HORSE_OWNER"})
    public ExpenseList list(
            @RequestParam(required = false) Integer horseId,
            @RequestParam(required = false) String category,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
            @RequestParam(required = false) Integer limit,
            @RequestAttribute(AuthInterceptor.USER_ATTR) AuthenticatedUser user) {
        return service.listForOwner(horseId, category, from, to, limit, user);
    }

    @org.springframework.web.bind.annotation.PutMapping("/{id}/dispute")
    @RequireRole({"HORSE_OWNER"})
    public ExpenseModels.Expense dispute(
            @org.springframework.web.bind.annotation.PathVariable int id,
            @jakarta.validation.Valid @org.springframework.web.bind.annotation.RequestBody ExpenseModels.DisputeRequest req,
            @RequestAttribute(AuthInterceptor.USER_ATTR) AuthenticatedUser user,
            jakarta.servlet.http.HttpServletRequest http) {
        return service.dispute(id, req.reason(), user, http.getRemoteAddr());
    }
}
