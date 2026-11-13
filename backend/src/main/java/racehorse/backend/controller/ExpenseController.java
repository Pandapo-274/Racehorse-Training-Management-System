package racehorse.backend.controller;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import java.time.LocalDate;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestAttribute;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import racehorse.backend.entity.ExpenseModels.Expense;
import racehorse.backend.entity.ExpenseModels.ExpenseList;
import racehorse.backend.entity.ExpenseModels.ExpenseRequest;
import racehorse.backend.security.AuthInterceptor;
import racehorse.backend.security.AuthenticatedUser;
import racehorse.backend.security.RequireRole;
import racehorse.backend.service.ExpenseService;

/**
 * Chi phí nuôi ngựa. Chỉ Club Manager ghi và xem chi tiết; chủ ngựa xem bản
 * tổng hợp của ngựa mình ở GET /api/owner/dashboard.
 */
@RestController
@RequestMapping("/api/expenses")
public class ExpenseController {

    private final ExpenseService service;

    public ExpenseController(ExpenseService service) {
        this.service = service;
    }

    @GetMapping
    @RequireRole({"CLUB_MANAGER", "VETERINARIAN"})
    public ExpenseList list(
            @RequestParam(required = false) Integer horseId,
            @RequestParam(required = false) String category,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
            @RequestParam(required = false) Integer limit) {
        return service.list(horseId, category, from, to, limit);
    }

    @PostMapping
    @RequireRole({"CLUB_MANAGER"})
    public ResponseEntity<Expense> create(
            @Valid @RequestBody ExpenseRequest request,
            @RequestAttribute(AuthInterceptor.USER_ATTR) AuthenticatedUser user,
            HttpServletRequest http) {
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(service.create(request, user, http.getRemoteAddr()));
    }

    /**
     * Voids the cost rather than erasing it - see ExpenseService.cancel.
     * Kept as DELETE so the route the frontend already calls stays the same;
     * the reason travels as a query parameter because DELETE bodies are not
     * reliably passed through by browsers and proxies.
     */
    @DeleteMapping("/{id}")
    @RequireRole({"CLUB_MANAGER"})
    public ResponseEntity<Void> delete(
            @PathVariable int id,
            @RequestParam(required = false) String reason,
            @RequestAttribute(AuthInterceptor.USER_ATTR) AuthenticatedUser user,
            HttpServletRequest http) {
        service.cancel(id, reason, user, http.getRemoteAddr());
        return ResponseEntity.noContent().build();
    }
}
