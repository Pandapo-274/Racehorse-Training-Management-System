package racehorse.backend.preventive;

import java.util.List;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestAttribute;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import jakarta.servlet.http.HttpServletRequest;
import racehorse.backend.auth.AuthInterceptor;
import racehorse.backend.auth.AuthenticatedUser;
import racehorse.backend.auth.RequireRole;

/**
 * UC17 - Track Routine Preventive Care (Preventive Care Reminders).
 *
 * Chỉ đọc. Ghi một lần tiêm hay tẩy giun vẫn đi qua UC14
 * (POST /api/medical-records với record_type = VACCINATION / DEWORMING /
 * FARRIER và nextDueDate).
 *
 * Bảng toàn đàn giới hạn cho nhân sự trong chuồng; chủ ngựa không xem được
 * lịch của cả đàn mà chỉ xem được ngựa của mình qua đường /horse/{id}, nơi
 * có phép kiểm quyền sở hữu.
 */
@RestController
@RequestMapping("/api/preventive-care")
public class PreventiveCareController {

    private final PreventiveCareService service;
    private final PreventiveReminderService reminders;

    public PreventiveCareController(PreventiveCareService service,
                                    PreventiveReminderService reminders) {
        this.service = service;
        this.reminders = reminders;
    }

    /**
     * Màn nhắc lịch toàn đàn.
     *
     * @param dueWithinDays số ngày tới tính là sắp đến hạn (mặc định 14)
     * @param includeOk     true để xem cả những ô không cần làm gì
     */
    @GetMapping
    @RequireRole({"VETERINARIAN", "HEAD_TRAINER", "GROOM", "CLUB_MANAGER"})
    public PreventiveCareBoard board(
            @RequestParam(required = false) Integer dueWithinDays,
            @RequestParam(defaultValue = "false") boolean includeOk) {
        return service.board(dueWithinDays, includeOk);
    }

    /**
     * Chạy tác vụ nhắc lịch ngay bây giờ, thay vì chờ 07:00 sáng mai.
     *
     * Dùng khi demo, và để bù khi máy tắt đúng giờ chạy. Cùng một service với tác vụ
     * định kỳ nên idempotent: bấm hai lần liên tiếp thì lần hai trả skipped, không gửi
     * trùng thông báo. Bác sĩ và quản lý được bấm; không phải việc của groom hay chủ ngựa.
     */
    @PostMapping("/reminders/run")
    @RequireRole({"VETERINARIAN", "CLUB_MANAGER"})
    public PreventiveReminderResult runReminders(
            @RequestAttribute(AuthInterceptor.USER_ATTR) AuthenticatedUser user,
            HttpServletRequest http) {
        return reminders.run(user.userId(), http.getRemoteAddr());
    }

    /** Lịch của một con ngựa, cả ba loại, kể cả loại đang ổn. */
    @GetMapping("/horse/{horseId}")
    public List<PreventiveCareItem> forHorse(
            @PathVariable int horseId,
            @RequestParam(required = false) Integer dueWithinDays,
            @RequestAttribute(AuthInterceptor.USER_ATTR) AuthenticatedUser user) {
        return service.forHorse(horseId, dueWithinDays, user);
    }
}
