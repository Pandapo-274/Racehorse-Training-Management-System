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

    /** Gửi cho mọi tài khoản ACTIVE của vai trò này, trừ người vừa làm việc đó. */
    public void notifyRole(String role, AuthenticatedUser actor, Notice notice) {
        notifications.insertForRole(role, actor == null ? 0 : actor.userId(), notice);
    }

    /**
     * Chủ ngựa và mọi Head Trainer, trừ người vừa làm việc đó.
     *
     * Những việc đổi được con ngựa có tập hay không (khoá tập, mở khoá, đổi
     * tình trạng sức khoẻ) đều báo cho đúng hai nhóm này: Head Trainer là
     * người xếp lịch, chủ ngựa là người có quyền biết ngựa mình ra sao.
     */
    public void notifyHorseWatchers(int ownerId, AuthenticatedUser actor, Notice notice) {
        if (actor == null || ownerId != actor.userId()) {
            notifications.insertForUser(ownerId, notice);
        }
        notifyRole("HEAD_TRAINER", actor, notice);
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
}
