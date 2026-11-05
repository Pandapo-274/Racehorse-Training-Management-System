package racehorse.backend.care;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import org.springframework.beans.factory.annotation.Value;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import racehorse.backend.auth.ApiException;
import racehorse.backend.auth.AuthenticatedUser;
import racehorse.backend.auth.UserRepository;
import racehorse.backend.horse.HorsePolicy;
import racehorse.backend.horse.HorseRepository;
import racehorse.backend.horse.HorseResponse;
import racehorse.backend.notification.Notice;
import racehorse.backend.notification.NotificationService;
import racehorse.backend.supply.SupplyModels;
import racehorse.backend.supply.SupplyService;
import racehorse.backend.training.TrainingSessionRepository;

/**
 * Flow 4 - khẩu phần ăn và việc chăm sóc hằng ngày.
 *
 *  - Khẩu phần do Head Trainer hoặc Vet DUYỆT (FEEDING_SCHEDULE.approved_by), Groom chỉ xem.
 *  - Việc trong ngày do Head Trainer / Club Manager giao cho từng Groom; Groom
 *    đánh dấu xong việc của mình.
 * Chỉ áp cho ngựa của học viện đang ở chuồng: hồ sơ PEDIGREE và ngựa đã rút không có chuồng.
 */
@Service
public class CareService {

    private static final DateTimeFormatter DAY = DateTimeFormatter.ofPattern("dd MMM", Locale.ENGLISH);
    private static final Map<String, String> TASK_TEXT = Map.of(
            "FEEDING", "feeding", "CLEANING", "stall cleaning", "BATHING", "bathing", "ICE_BATH", "ice bath");

    private final CareRepository care;
    private final HorseRepository horses;
    private final TrainingSessionRepository sessions;
    private final UserRepository users;
    private final NotificationService notifications;
    private final SupplyService supplies;
    /** A task may still be ticked (or reopened) this many days after its day (#14). */
    private final int lateWindowDays;
    /** Above this many open tasks in one day, assigning more returns a warning (#15). */
    private final int maxTasksPerGroom;

    public CareService(CareRepository care, HorseRepository horses, TrainingSessionRepository sessions,
                       UserRepository users, NotificationService notifications, SupplyService supplies,
                       @Value("${app.task.late-window-days:1}") int lateWindowDays,
                       @Value("${app.task.max-per-groom-per-day:12}") int maxTasksPerGroom) {
        this.care = care;
        this.horses = horses;
        this.sessions = sessions;
        this.users = users;
        this.notifications = notifications;
        this.supplies = supplies;
        this.lateWindowDays = lateWindowDays;
        this.maxTasksPerGroom = maxTasksPerGroom;
    }

    /* ---------------- Khẩu phần ---------------- */

    public List<CareModels.FeedingItem> feeding(Integer horseId) {
        return care.findFeeding(horseId, null);
    }

    public List<CareModels.FeedingItem> feeding(Integer horseId, AuthenticatedUser user) {
        Integer ownerId = "HORSE_OWNER".equals(user.role()) ? user.userId() : null;
        return care.findFeeding(horseId, ownerId);
    }

    /**
     * Thay cả khẩu phần trong một giao dịch: xoá cũ, chèn mới, người duyệt là người gọi.
     *
     * Every line must name a FEED item from the store (V21): finishing a
     * FEEDING task takes the ration from that item, in that item's unit. The
     * lines are checked BEFORE the old ration is deleted, so a bad line never
     * leaves the horse with no ration.
     */
    @Transactional
    public List<CareModels.FeedingItem> replaceRation(int horseId, CareModels.RationRequest req,
                                                      AuthenticatedUser user, String ip) {
        requireStabledHorse(horseId);
        Map<String, String> errors = new LinkedHashMap<>();
        List<CareModels.RationItem> lines = new ArrayList<>();
        for (int i = 0; i < req.items().size(); i++) {
            CareModels.RationItem it = req.items().get(i);
            String field = "items[" + i + "].supplyId";
            SupplyModels.Supply stock = it.supplyId() == null ? null : supplies.find(it.supplyId()).orElse(null);
            if (stock == null) {
                errors.put(field, "Choose a feed from the stock list");
            } else if (!"FEED".equals(stock.category())) {
                errors.put(field, stock.supplyName() + " is not a feed");
            } else {
                lines.add(it.withStock(stock.supplyId(), stock.supplyName(), stock.unit()));
            }
        }
        if (!errors.isEmpty()) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "Invalid ration", errors);
        }
        care.deleteRation(horseId);
        for (CareModels.RationItem item : lines) {
            care.insertRationItem(horseId, item, user.userId());
        }
        String detail = lines.stream()
                .map(l -> l.mealTime() + " " + l.feedType() + " " + l.quantity().stripTrailingZeros().toPlainString()
                        + " " + l.unit())
                .reduce((a, b) -> a + "; " + b).orElse("");
        users.writeAudit(user.userId(), "FEEDING_PLAN_UPDATE", "HORSE", horseId, "SUCCESS", ip, detail);
        return care.findFeeding(horseId);
    }

    /* ---------------- Việc trong ngày ---------------- */

    /** Groom chỉ thấy việc của mình; Head Trainer / Manager thấy cả chuồng. */
    public List<CareModels.Task> tasks(LocalDate date, Integer horseId, AuthenticatedUser user) {
        Integer assignedTo = "GROOM".equals(user.role()) ? user.userId() : null;
        return care.findTasks(date == null ? LocalDate.now() : date, assignedTo, horseId);
    }

    @Transactional
    public CareModels.AssignResult assign(CareModels.AssignRequest r, AuthenticatedUser user, String ip) {
        HorseResponse horse = requireStabledHorse(r.horseId());
        if (!sessions.isActiveGroom(r.assignedTo())) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "Assignee must be an active Groom",
                    Map.of("assignedTo", "Choose an active Groom"));
        }
        if (r.taskDate().isBefore(LocalDate.now())) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "Cannot assign tasks in the past",
                    Map.of("taskDate", "Choose today or a later day"));
        }
        String note = r.note() == null || r.note().isBlank() ? null : r.note().trim();

        List<CareModels.Task> created = new ArrayList<>();
        List<String> skipped = new ArrayList<>();
        // LinkedHashSet: bỏ loại việc bị gửi trùng mà vẫn giữ thứ tự người dùng chọn.
        for (String type : new LinkedHashSet<>(r.taskTypes())) {
            // Mỗi con ngựa mỗi ngày chỉ một việc mỗi loại: hai Groom cùng cho một con ăn là thừa.
            if (care.taskExists(r.horseId(), type, r.taskDate())) {
                skipped.add(type);
                continue;
            }
            int id = care.insertTask(r.horseId(), type, r.assignedTo(), r.taskDate(), note);
            users.writeAudit(user.userId(), "DAILY_TASK_ASSIGN", "DAILY_TASK", id, "SUCCESS", ip);
            care.findTask(id).ifPresent(created::add);
        }
        List<String> warnings = new ArrayList<>();
        if (!created.isEmpty()) {
            int open = care.openTasksOf(r.assignedTo(), r.taskDate());
            if (open > maxTasksPerGroom) {
                warnings.add(created.get(0).assignedToName() + " now has " + open + " open tasks on "
                        + r.taskDate().format(DAY) + " (more than " + maxTasksPerGroom + ")");
            }
            String what = created.stream().map(t -> TASK_TEXT.get(t.taskType())).reduce((a, b) -> a + ", " + b).orElse("");
            notifications.notifyUser(r.assignedTo(), new Notice(
                    "DAILY_TASK_ASSIGNED", "New care tasks",
                    horse.horseName() + " on " + r.taskDate().format(DAY) + ": " + what + ".",
                    Notice.HORSE, horse.horseId()));
        }
        return new CareModels.AssignResult(created, skipped, warnings);
    }

    /**
     * Groom đánh dấu xong việc của mình. Không đánh dấu trước được việc của ngày mai.
     *
     * #14: a task can be ticked on its day or up to lateWindowDays later (it
     * then shows DONE_LATE); after that it stays MISSED. Reopening uses the same
     * window, so the two rules no longer contradict each other.
     *
     * #10: finishing a FEEDING task takes the horse's whole day ration from the
     * store - one task per day means one deduction per day (per-meal tasks are
     * #13). Ration lines not yet linked to a stock item are skipped and named in
     * the audit.
     */
    @Transactional
    public CareModels.Task complete(int taskId, AuthenticatedUser user, String ip) {
        CareModels.Task t = requireTask(taskId, user);
        LocalDate today = LocalDate.now();
        if (t.taskDate().isAfter(today)) {
            throw new ApiException(HttpStatus.CONFLICT, "This task is for " + t.taskDate() + ", not today");
        }
        if (t.taskDate().isBefore(today.minusDays(lateWindowDays)) && !t.completed()) {
            throw new ApiException(HttpStatus.CONFLICT, "This task was for " + t.taskDate()
                    + " and can no longer be ticked. It stays as missed");
        }
        // The guard in markDone makes a second click (or a second groom) a no-op: stock is taken once.
        if (care.markDone(taskId, user.userId()) == 0) {
            throw new ApiException(HttpStatus.CONFLICT, "This task is already done");
        }
        String detail = t.horseName() + " " + t.taskType() + " " + t.taskDate()
                + (t.taskDate().isBefore(today) ? " (late)" : "");
        if ("FEEDING".equals(t.taskType())) {
            detail += consumeRation(t, user);
        }
        users.writeAudit(user.userId(), "DAILY_TASK_DONE", "DAILY_TASK", taskId, "SUCCESS", ip, detail);
        return requireTask(taskId, user);
    }

    /** Takes the day's ration (all meals), grouped per stock item. Returns text for the audit. */
    private String consumeRation(CareModels.Task t, AuthenticatedUser user) {
        Map<Integer, BigDecimal> perSupply = new LinkedHashMap<>();
        List<String> unlinked = new ArrayList<>();
        for (CareModels.FeedingItem f : care.findFeeding(t.horseId())) {
            if (f.supplyId() == null) {
                unlinked.add(f.feedType());
            } else {
                perSupply.merge(f.supplyId(), f.quantity(), BigDecimal::add);
            }
        }
        StringBuilder used = new StringBuilder();
        perSupply.forEach((supplyId, qty) -> {
            BigDecimal taken = supplies.consume(supplyId, qty, "DAILY_TASK", t.taskId(),
                    "Feeding " + t.horseName(), user);
            used.append(used.length() == 0 ? ". Stock: " : ", ")
                    .append("#").append(supplyId).append(" -").append(taken.stripTrailingZeros().toPlainString());
            if (taken.compareTo(qty) < 0) {
                used.append(" (short ").append(qty.subtract(taken).stripTrailingZeros().toPlainString()).append(")");
            }
        });
        if (!unlinked.isEmpty()) {
            used.append(". Not linked to stock: ").append(String.join(", ", unlinked));
        }
        return used.toString();
    }

    /**
     * Bỏ đánh dấu khi bấm nhầm. Same window as complete (#14). A reopened
     * FEEDING task gives back exactly what it took - read from the ledger, not
     * recomputed from the ration, which may have changed since.
     */
    @Transactional
    public CareModels.Task undo(int taskId, AuthenticatedUser user, String ip) {
        CareModels.Task t = requireTask(taskId, user);
        LocalDate today = LocalDate.now();
        if (t.taskDate().isBefore(today.minusDays(lateWindowDays))) {
            throw new ApiException(HttpStatus.CONFLICT, "Only tasks from the last "
                    + (lateWindowDays + 1) + " day(s) can be reopened");
        }
        if (care.markUndone(taskId, today) == 0) {
            throw new ApiException(HttpStatus.CONFLICT, "This task is not marked as done");
        }
        String detail = t.horseName() + " " + t.taskType() + " " + t.taskDate();
        if ("FEEDING".equals(t.taskType())) {
            Map<Integer, BigDecimal> taken = supplies.netConsumedBy("DAILY_TASK", taskId);
            taken.forEach((supplyId, net) -> supplies.giveBack(supplyId, net, "DAILY_TASK", taskId,
                    "Task reopened: " + t.horseName(), user));
            if (!taken.isEmpty()) {
                detail += ". Stock given back to " + taken.size() + " item(s)";
            }
        }
        users.writeAudit(user.userId(), "DAILY_TASK_REOPEN", "DAILY_TASK", taskId, "SUCCESS", ip, detail);
        return requireTask(taskId, user);
    }

    /** #15: hand an open task to another groom. Both grooms are told. */
    @Transactional
    public CareModels.AssignResult reassign(int taskId, int newGroom, AuthenticatedUser user, String ip) {
        CareModels.Task t = requireTask(taskId, user);
        if (t.completed()) {
            throw new ApiException(HttpStatus.CONFLICT, "A completed task cannot be reassigned");
        }
        if (t.taskDate().isBefore(LocalDate.now())) {
            throw new ApiException(HttpStatus.CONFLICT, "Only today's or later tasks can be reassigned");
        }
        if (t.assignedTo() == newGroom) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "The task is already assigned to this groom",
                    Map.of("assignedTo", "Choose another groom"));
        }
        if (!sessions.isActiveGroom(newGroom)) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "Assignee must be an active Groom",
                    Map.of("assignedTo", "Choose an active Groom"));
        }
        if (care.reassign(taskId, newGroom) == 0) {
            throw new ApiException(HttpStatus.CONFLICT, "This task has just been completed");
        }
        CareModels.Task after = requireTask(taskId, user);
        users.writeAudit(user.userId(), "DAILY_TASK_REASSIGN", "DAILY_TASK", taskId, "SUCCESS", ip,
                t.horseName() + " " + t.taskType() + " " + t.taskDate() + ": "
                        + t.assignedToName() + " -> " + after.assignedToName());
        String what = TASK_TEXT.get(t.taskType()) + " for " + t.horseName() + " on " + t.taskDate().format(DAY);
        notifications.notifyUser(newGroom, new Notice("DAILY_TASK_ASSIGNED", "New care tasks",
                what + " (handed over from " + t.assignedToName() + ").", Notice.HORSE, t.horseId()));
        notifications.notifyUser(t.assignedTo(), new Notice("DAILY_TASK_REASSIGNED", "Task handed over",
                what + " is now done by " + after.assignedToName() + ".", Notice.HORSE, t.horseId()));
        List<String> warnings = new ArrayList<>();
        int open = care.openTasksOf(newGroom, t.taskDate());
        if (open > maxTasksPerGroom) {
            warnings.add(after.assignedToName() + " now has " + open + " open tasks on "
                    + t.taskDate().format(DAY) + " (more than " + maxTasksPerGroom + ")");
        }
        return new CareModels.AssignResult(List.of(after), List.of(), warnings);
    }

    /** #14: tasks never done in a period (Manager / Head Trainer report). */
    public List<CareModels.Task> missed(LocalDate from, LocalDate to) {
        LocalDate today = LocalDate.now();
        LocalDate f = from == null ? today.minusDays(7) : from;
        LocalDate tt = to == null ? today.minusDays(1) : to;
        if (f.isAfter(tt)) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "The start date must be on or before the end date");
        }
        return care.findMissed(f, tt, today);
    }

    /** Called by MissedTaskJob each morning; returns how many tasks were newly marked missed. */
    @Transactional
    public int stampMissed() {
        int n = care.stampMissed(LocalDate.now());
        if (n > 0) {
            notifications.notifyRole("HEAD_TRAINER", null, new Notice("DAILY_TASK_MISSED", "Missed care tasks",
                    n + " care task(s) from earlier days were never ticked. See Stable care > Missed.",
                    null, null));
        }
        return n;
    }

    @Transactional
    public void delete(int taskId, AuthenticatedUser user, String ip) {
        requireTask(taskId, user);
        if (care.deleteOpenTask(taskId) == 0) {
            throw new ApiException(HttpStatus.CONFLICT, "A completed task cannot be deleted");
        }
        users.writeAudit(user.userId(), "DAILY_TASK_DELETE", "DAILY_TASK", taskId, "SUCCESS", ip);
    }

    /* ---------------- helper ---------------- */

    private HorseResponse requireStabledHorse(int horseId) {
        HorseResponse h = horses.findById(horseId)
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "Horse #" + horseId + " not found"));
        if (HorsePolicy.PEDIGREE.equals(HorsePolicy.normalizeType(h.recordType())) || h.isWithdrawn()) {
            throw new ApiException(HttpStatus.CONFLICT,
                    h.horseName() + " is not stabled at the club, so it has no ration or daily care");
        }
        return h;
    }

    private CareModels.Task requireTask(int taskId, AuthenticatedUser user) {
        CareModels.Task t = care.findTask(taskId)
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "Task #" + taskId + " not found"));
        if ("GROOM".equals(user.role()) && t.assignedTo() != user.userId()) {
            throw new ApiException(HttpStatus.FORBIDDEN, "This task is assigned to someone else");
        }
        return t;
    }

}
