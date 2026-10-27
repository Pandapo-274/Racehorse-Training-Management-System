package racehorse.backend.care;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;

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

    public CareService(CareRepository care, HorseRepository horses, TrainingSessionRepository sessions,
                       UserRepository users, NotificationService notifications) {
        this.care = care;
        this.horses = horses;
        this.sessions = sessions;
        this.users = users;
        this.notifications = notifications;
    }

    /* ---------------- Khẩu phần ---------------- */

    public List<CareModels.FeedingItem> feeding(Integer horseId) {
        return care.findFeeding(horseId);
    }

    /** Thay cả khẩu phần trong một giao dịch: xoá cũ, chèn mới, người duyệt là người gọi. */
    @Transactional
    public List<CareModels.FeedingItem> replaceRation(int horseId, CareModels.RationRequest req,
                                                      AuthenticatedUser user, String ip) {
        requireStabledHorse(horseId);
        care.deleteRation(horseId);
        for (CareModels.RationItem item : req.items()) {
            care.insertRationItem(horseId, item, user.userId());
        }
        users.writeAudit(user.userId(), "FEEDING_PLAN_UPDATE", "HORSE", horseId, "SUCCESS", ip);
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
        if (!created.isEmpty()) {
            String what = created.stream().map(t -> TASK_TEXT.get(t.taskType())).reduce((a, b) -> a + ", " + b).orElse("");
            notifications.notifyUser(r.assignedTo(), new Notice(
                    "DAILY_TASK_ASSIGNED", "New care tasks",
                    horse.horseName() + " on " + r.taskDate().format(DAY) + ": " + what + ".",
                    Notice.HORSE, horse.horseId()));
        }
        return new CareModels.AssignResult(created, skipped);
    }

    /** Groom đánh dấu xong việc của mình. Không đánh dấu trước được việc của ngày mai. */
    @Transactional
    public CareModels.Task complete(int taskId, AuthenticatedUser user, String ip) {
        CareModels.Task t = requireTask(taskId, user);
        if (t.taskDate().isAfter(LocalDate.now())) {
            throw new ApiException(HttpStatus.CONFLICT, "This task is for " + t.taskDate() + ", not today");
        }
        if (care.markDone(taskId) == 0) {
            throw new ApiException(HttpStatus.CONFLICT, "This task is already done");
        }
        users.writeAudit(user.userId(), "DAILY_TASK_DONE", "DAILY_TASK", taskId, "SUCCESS", ip);
        return requireTask(taskId, user);
    }

    /** Bỏ đánh dấu khi bấm nhầm. Chỉ trong ngày của việc đó: sổ của hôm qua là sổ đã chốt. */
    @Transactional
    public CareModels.Task undo(int taskId, AuthenticatedUser user, String ip) {
        CareModels.Task t = requireTask(taskId, user);
        if (!t.taskDate().equals(LocalDate.now())) {
            throw new ApiException(HttpStatus.CONFLICT, "Only today's tasks can be reopened");
        }
        if (care.markUndone(taskId) == 0) {
            throw new ApiException(HttpStatus.CONFLICT, "This task is not marked as done");
        }
        users.writeAudit(user.userId(), "DAILY_TASK_REOPEN", "DAILY_TASK", taskId, "SUCCESS", ip);
        return requireTask(taskId, user);
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
