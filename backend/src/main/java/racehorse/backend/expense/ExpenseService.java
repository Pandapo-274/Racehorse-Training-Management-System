package racehorse.backend.expense;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import racehorse.backend.auth.ApiException;
import racehorse.backend.auth.AuthenticatedUser;
import racehorse.backend.auth.UserRepository;
import racehorse.backend.expense.ExpenseModels.Expense;
import racehorse.backend.expense.ExpenseModels.ExpenseList;
import racehorse.backend.expense.ExpenseModels.ExpenseRequest;
import racehorse.backend.horse.HorsePolicy;
import racehorse.backend.horse.HorseRepository;
import racehorse.backend.horse.HorseResponse;

/** Club Manager ghi và xem chi phí nuôi ngựa (EXPENSE_LOG). */
@Service
public class ExpenseService {

    private static final int DEFAULT_LIMIT = 200;
    private static final int MAX_LIMIT = 500;

    private final ExpenseRepository expenses;
    private final HorseRepository horses;
    private final UserRepository users;

    public ExpenseService(ExpenseRepository expenses, HorseRepository horses, UserRepository users) {
        this.expenses = expenses;
        this.horses = horses;
        this.users = users;
    }

    public ExpenseList list(Integer horseId, String rawCategory, LocalDate from, LocalDate to, Integer limit) {
        String category = (rawCategory == null || rawCategory.isBlank())
                ? null : rawCategory.trim().toUpperCase(Locale.ROOT);
        Map<String, String> errors = new LinkedHashMap<>();
        if (category != null && !ExpenseModels.CATEGORIES.contains(category)) {
            errors.put("category", "Category must be one of " + ExpenseModels.CATEGORIES);
        }
        if (from != null && to != null && from.isAfter(to)) {
            errors.put("from", "The start date must be on or before the end date");
        }
        if (!errors.isEmpty()) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "Invalid filter", errors);
        }

        int n = limit == null ? DEFAULT_LIMIT : Math.max(1, Math.min(limit, MAX_LIMIT));
        List<Expense> items = expenses.find(horseId, category, from, to, n);

        // Đủ bốn hạng mục, theo thứ tự cố định, kể cả hạng mục bằng 0.
        Map<String, BigDecimal> found = expenses.totals(horseId, category, from, to);
        Map<String, BigDecimal> totals = new LinkedHashMap<>();
        BigDecimal total = BigDecimal.ZERO;
        for (String c : ExpenseModels.CATEGORIES) {
            BigDecimal amount = found.getOrDefault(c, BigDecimal.ZERO);
            totals.put(c, amount);
            total = total.add(amount);
        }

        int count = expenses.count(horseId, category, from, to);
        return new ExpenseList(items, totals, total, count, count > items.size());
    }

    @Transactional
    public Expense create(ExpenseRequest raw, AuthenticatedUser me, String ip) {
        HorseResponse horse = horses.findById(raw.horseId())
                .orElseThrow(() -> new ApiException(HttpStatus.BAD_REQUEST, "Invalid data",
                        Map.of("horseId", "Horse #" + raw.horseId() + " does not exist")));

        Map<String, String> errors = new LinkedHashMap<>();
        /* Báo cáo chi phí của chủ ngựa chỉ tính ngựa ACADEMY; hồ sơ PEDIGREE
           không ở học viện nên không phát sinh chi phí. */
        if (HorsePolicy.PEDIGREE.equals(HorsePolicy.normalizeType(horse.recordType()))) {
            errors.put("horseId", "A pedigree record is not kept at the club, so it has no costs");
        }
        /* Ngựa đã rút vẫn nhận được hoá đơn cuối, nhưng không phải chi phí của
           những ngày nó đã rời học viện. */
        if (horse.withdrawnAt() != null && raw.expenseDate().isAfter(horse.withdrawnAt().toLocalDate())) {
            errors.put("expenseDate", horse.horseName() + " left the club on "
                    + horse.withdrawnAt().toLocalDate() + ", so it has no costs after that day");
        }
        if (!errors.isEmpty()) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "The cost could not be saved", errors);
        }

        String note = raw.note() == null || raw.note().isBlank() ? null : raw.note().trim();
        ExpenseRequest clean = new ExpenseRequest(raw.horseId(), raw.category(), raw.amount(),
                raw.expenseDate(), note);

        int id = expenses.insert(clean, me.userId());
        users.writeAudit(me.userId(), "EXPENSE_CREATE", "EXPENSE_LOG", id, "SUCCESS", ip);
        return expenses.findById(id)
                .orElseThrow(() -> new ApiException(HttpStatus.INTERNAL_SERVER_ERROR,
                        "The cost was saved but could not be read back"));
    }

    /** Xoá một khoản ghi nhầm. Audit giữ lại dấu vết ai xoá. */
    @Transactional
    public void delete(int expenseId, AuthenticatedUser me, String ip) {
        if (expenses.delete(expenseId) == 0) {
            throw new ApiException(HttpStatus.NOT_FOUND, "Cost #" + expenseId + " does not exist");
        }
        users.writeAudit(me.userId(), "EXPENSE_DELETE", "EXPENSE_LOG", expenseId, "SUCCESS", ip);
    }
}
