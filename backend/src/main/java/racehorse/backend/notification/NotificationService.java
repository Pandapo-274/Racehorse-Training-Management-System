package racehorse.backend.notification;

import java.util.List;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

import racehorse.backend.auth.ApiException;
import racehorse.backend.auth.AuthenticatedUser;

/**
 * Thông báo trong ứng dụng.
 *
 * Các service nghiệp vụ (duyệt ngựa, giao buổi tập, khoá tập...) gọi
 * notifyUser / notifyRole ngay sau khi ghi audit. Không có @Transactional ở
 * đây: lời gọi chạy trong giao dịch của service gọi nó, nên nếu việc chính bị
 * huỷ thì thông báo cũng bị huỷ theo - không ai nhận chuông về một việc chưa
 * từng xảy ra.
 */
@Service
public class NotificationService {

    private static final int DEFAULT_LIMIT = 20;
    private static final int MAX_LIMIT = 100;

    private final NotificationRepository notifications;

    public NotificationService(NotificationRepository notifications) {
        this.notifications = notifications;
    }

    /* ------------------------------------------------------------------ *
     * Gửi - dùng từ các service khác
     * ------------------------------------------------------------------ */

    public void notifyUser(int userId, Notice notice) {
        notifications.insertForUser(userId, notice);
    }

    /**
     * Gửi cho mọi tài khoản ACTIVE của vai trò này, trừ người vừa làm việc đó.
     *
     * @param actor null nếu việc do hệ thống làm (tác vụ định kỳ), khi đó không loại ai.
     * @return số người đã nhận. Các chỗ gọi cũ bỏ qua giá trị này; tác vụ nhắc lịch
     *         cần nó để không đánh dấu "đã nhắc" khi chẳng có ai nhận.
     */
    public int notifyRole(String role, AuthenticatedUser actor, Notice notice) {
        return notifications.insertForRole(role, actor == null ? 0 : actor.userId(), notice);
    }

    /**
     * Chỉ gửi thông báo cho Head Trainer đang trực tiếp quản lý ngựa này (trừ khi chính họ là actor).
     * Nếu ngựa chưa được gán Head Trainer thì không gửi tràn lan sang các trainer khác.
     */
    public int notifyHorseTrainer(int horseId, AuthenticatedUser actor, Notice notice) {
        Integer trainerId = notifications.findHorseTrainerId(horseId).orElse(null);
        if (trainerId == null || (actor != null && trainerId == actor.userId())) {
            return 0;
        }
        notifications.insertForUser(trainerId, notice);
        return 1;
    }

    /**
     * Chủ ngựa và Head Trainer quản lý ngựa đó (nếu xác định được từ horseId hoặc target HORSE),
     * trừ người vừa làm việc đó.
     */
    public void notifyHorseWatchers(int horseId, int ownerId, AuthenticatedUser actor, Notice notice) {
        if (actor == null || ownerId != actor.userId()) {
            notifications.insertForUser(ownerId, notice);
        }
        notifyHorseTrainer(horseId, actor, notice);
    }

    public void notifyHorseWatchers(int ownerId, AuthenticatedUser actor, Notice notice) {
        if (actor == null || ownerId != actor.userId()) {
            notifications.insertForUser(ownerId, notice);
        }
        if (Notice.HORSE.equals(notice.targetEntity()) && notice.targetId() != null) {
            notifyHorseTrainer(notice.targetId(), actor, notice);
        } else {
            notifyRole("HEAD_TRAINER", actor, notice);
        }
    }

    /* ------------------------------------------------------------------ *
     * Đọc - dùng từ NotificationController
     * ------------------------------------------------------------------ */

    public List<NotificationRow> list(AuthenticatedUser me, Integer limit) {
        int n = limit == null ? DEFAULT_LIMIT : Math.max(1, Math.min(limit, MAX_LIMIT));
        return notifications.findByUser(me.userId(), n);
    }

    public int unreadCount(AuthenticatedUser me) {
        return notifications.countUnread(me.userId());
    }

    public void markRead(AuthenticatedUser me, int notificationId) {
        // 404 chứ không phải 403 cho thông báo của người khác: không xác nhận
        // cho người gọi biết id đó có tồn tại hay không.
        if (notifications.markRead(notificationId, me.userId()) == 0) {
            throw new ApiException(HttpStatus.NOT_FOUND,
                    "Notification #" + notificationId + " does not exist");
        }
    }

    public int markAllRead(AuthenticatedUser me) {
        return notifications.markAllRead(me.userId());
    }

    public boolean hasNotificationSentToday(int userId, String type, String entity, int targetId, String title) {
        return notifications.hasNotificationSent(userId, type, entity, targetId, title);
    }

    public boolean hasOverdueNotificationRecently(int userId, String type, String entity, int targetId) {
        return notifications.hasOverdueNotificationRecently(userId, type, entity, targetId);
    }
}
