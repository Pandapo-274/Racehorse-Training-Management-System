package racehorse.backend.incident;

import org.springframework.stereotype.Component;
import racehorse.backend.auth.AuthenticatedUser;
import racehorse.backend.auth.UserRepository;
import racehorse.backend.notification.Notice;
import racehorse.backend.notification.NotificationService;

/**
 * Dừng mọi buổi đang chạy của một con ngựa vừa bị khoá tập, và báo cho Groom đang phụ trách.
 *
 * Được TrainingLockService gọi ngay sau khi chèn lệnh khoá, cùng một giao dịch: lệnh khoá mà buổi
 * đang chạy vẫn chạy tiếp thì cái chốt an toàn chưa chốt được gì - ngoài sân Groom vẫn đang dắt ngựa.
 * (Hệ thống hiện coi mọi lệnh khoá đều chặn mọi buổi tập, nên không phân biệt mức FULL / HEAVY_WORK_ONLY.)
 */
@Component
public class SessionStopper {

    private final IncidentRepository incidents;
    private final UserRepository users;
    private final NotificationService notifications;

    public SessionStopper(IncidentRepository incidents, UserRepository users, NotificationService notifications) {
        this.incidents = incidents;
        this.users = users;
        this.notifications = notifications;
    }

    /** @return số buổi đã bị dừng. */
    public int stopRunningSessions(int horseId, String horseName, String lockLevel, String reason,
                                   AuthenticatedUser issuer, String ip) {
        int stopped = 0;
        for (IncidentRepository.RunningSession s : incidents.findRunningSessions(horseId)) {
            // 0 dòng = buổi đó vừa kết thúc ngay lúc này, không cần dừng và không cần báo.
            if (incidents.markStopped(s.sessionId()) == 0) continue;
            stopped++;
            users.writeAudit(issuer.userId(), "SESSION_STOPPED_BY_LOCK", "TRAINING_SESSION",
                    s.sessionId(), "SUCCESS", ip);
            notifications.notifyUser(s.assignedTo(), new Notice(
                    "TRAINING_SESSION_STOPPED_BY_LOCK",
                    "Training session stopped",
                    Text.clip(horseName + " is under a training lock (" + lockLevel + "): " + reason
                            + ". Stop work with this horse now.", 500),
                    Notice.TRAINING_SESSION, s.sessionId()));
        }
        return stopped;
    }
}
