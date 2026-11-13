package racehorse.backend.service;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import racehorse.backend.dto.HorseResponse;
import racehorse.backend.entity.ExpenseModels.DisputeRequest;
import racehorse.backend.entity.ExpenseModels.Expense;
import racehorse.backend.entity.ExpenseModels.ExpenseList;
import racehorse.backend.entity.ExpenseModels.ExpenseRequest;
import racehorse.backend.entity.ExpenseModels;
import racehorse.backend.exception.ApiException;
import racehorse.backend.repository.ExpenseRepository;
import racehorse.backend.repository.HorseRepository;
import racehorse.backend.repository.UserRepository;
import racehorse.backend.security.AuthenticatedUser;

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
        return list(horseId, rawCategory, from, to, limit, null);
    }

    /**
     * #9: the owner sees each cost line of their own horses, not only the
     * totals on the dashboard. A horse of another owner is 403, not an empty
     * list, so a wrong id is not mistaken for "no costs".
     */
    public ExpenseList listForOwner(Integer horseId, String rawCategory, LocalDate from, LocalDate to,
                                    Integer limit, AuthenticatedUser owner) {
        if (horseId != null) {
            HorseResponse h = horses.findById(horseId)
                    .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "Horse #" + horseId + " not found"));
            if (h.ownerId() != owner.userId()) {
                throw new ApiException(HttpStatus.FORBIDDEN, "This horse is not yours");
            }
        }
        return list(horseId, rawCategory, from, to, limit, owner.userId());
    }

    private ExpenseList list(Integer horseId, String rawCategory, LocalDate from, LocalDate to, Integer limit,
                             Integer ownerId) {
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
        List<Expense> items = expenses.find(horseId, category, from, to, n, ownerId);

        // Đủ bốn hạng mục, theo thứ tự cố định, kể cả hạng mục bằng 0.
        Map<String, BigDecimal> found = expenses.totals(horseId, category, from, to, ownerId);
        Map<String, BigDecimal> totals = new LinkedHashMap<>();
        BigDecimal total = BigDecimal.ZERO;
        for (String c : ExpenseModels.CATEGORIES) {
            BigDecimal amount = found.getOrDefault(c, BigDecimal.ZERO);
            totals.put(c, amount);
            total = total.add(amount);
        }

        int count = expenses.count(horseId, category, from, to, ownerId);
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
        /* The schema has no "arrived at the club" date (HORSE.created_at is when
           the record was typed in, and backdated costs are legitimate), so the
           earliest hard limit is the day the horse was born. */
        if (horse.dateOfBirth() != null && raw.expenseDate().isBefore(horse.dateOfBirth())) {
            errors.put("expenseDate", horse.horseName() + " was born on " + horse.dateOfBirth()
                    + ", so it has no costs before that day");
        }
        if (!errors.isEmpty()) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "The cost could not be saved", errors);
        }

        String note = raw.note() == null || raw.note().isBlank() ? null : raw.note().trim();
        ExpenseRequest clean = new ExpenseRequest(raw.horseId(), raw.category(), raw.amount(),
                raw.expenseDate(), note);

        int id = expenses.insert(clean, me.userId());
        Expense saved = expenses.findById(id)
                .orElseThrow(() -> new ApiException(HttpStatus.INTERNAL_SERVER_ERROR,
                        "The cost was saved but could not be read back"));
        users.writeAudit(me.userId(), "EXPENSE_CREATE", "EXPENSE_LOG", id, "SUCCESS", ip, describe(saved));
        return saved;
    }

    /**
     * Void a cost recorded by mistake. A reason is required.
     *
     * This used to be a hard DELETE, and the audit entry held only the id - once
     * the row was gone nobody could tell how much it was or which horse it was
     * charged to. Now the row stays (V19) with who voided it and why, so the
     * audit id still leads to the full record.
     *
     * There is deliberately no "edit" route. A ledger entry is not overwritten:
     * it is voided and entered again, so both the wrong figure and the
     * correction stay visible.
     */
    @Transactional
    public void cancel(int expenseId, String reason, AuthenticatedUser me, String ip) {
        String why = reason == null ? "" : reason.trim();
        if (why.isEmpty()) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "A reason is required to void a cost",
                    Map.of("reason", "Say why this cost is being voided"));
        }
        if (why.length() > 255) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "Reason is too long",
                    Map.of("reason", "At most 255 characters"));
        }
        Expense target = expenses.findById(expenseId)
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "Cost #" + expenseId + " does not exist"));
        if (expenses.cancel(expenseId, me.userId(), why) == 0) {
            throw new ApiException(HttpStatus.CONFLICT, "Cost #" + expenseId + " has already been voided");
        }
        /* The audit line alone now says what was voided (review #9): the
           Audit log page shows it without opening the database. */
        users.writeAudit(me.userId(), "EXPENSE_VOID", "EXPENSE_LOG", expenseId, "SUCCESS", ip,
                describe(target) + ". Reason: " + why);
    }

    @Transactional
    public Expense dispute(int expenseId, String reason, AuthenticatedUser owner, String ip) {
        String why = reason == null ? "" : reason.trim();
        if (why.isEmpty()) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "A reason is required to dispute a cost",
                    Map.of("reason", "Say why this cost is being disputed"));
        }
        Expense target = expenses.findById(expenseId)
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "Cost #" + expenseId + " does not exist"));
        HorseResponse horse = horses.findById(target.horseId())
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "Horse not found"));
        if ("HORSE_OWNER".equals(owner.role()) && horse.ownerId() != owner.userId()) {
            throw new ApiException(HttpStatus.FORBIDDEN, "This horse is not yours");
        }
        if (expenses.markDisputed(expenseId, why) == 0) {
            throw new ApiException(HttpStatus.CONFLICT, "Cost cannot be disputed (already voided or not in billed state)");
        }
        users.writeAudit(owner.userId(), "EXPENSE_DISPUTE", "EXPENSE_LOG", expenseId, "SUCCESS", ip,
                describe(target) + ". Dispute reason: " + why);
        return expenses.findById(expenseId).orElseThrow();
    }

    /** "TM-0481 Symboli Rudolf, FEEDING, 2500000 on 2026-11-02" */
    private static String describe(Expense e) {
        return (e.registrationCode() == null ? "" : e.registrationCode() + " ") + e.horseName()
                + ", " + e.category() + ", " + e.amount().stripTrailingZeros().toPlainString()
                + " on " + e.expenseDate();
    }
}
