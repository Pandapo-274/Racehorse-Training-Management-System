package racehorse.backend.notification;

import java.util.List;
import java.util.Map;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestAttribute;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import racehorse.backend.auth.AuthInterceptor;
import racehorse.backend.auth.AuthenticatedUser;

/**
 * Chuông thông báo trên header của mọi vai trò.
 *
 * Không gắn @RequireRole: ai đăng nhập cũng có thông báo của mình. userId
 * luôn lấy từ token, nên mỗi người chỉ đọc và đánh dấu được thông báo của
 * chính họ.
 */
@RestController
@RequestMapping("/api/notifications")
public class NotificationController {

    private final NotificationService service;

    public NotificationController(NotificationService service) {
        this.service = service;
    }

    /** Thông báo mới nhất trước. limit mặc định 20, tối đa 100. */
    @GetMapping
    public List<NotificationRow> list(
            @RequestParam(required = false) Integer limit,
            @RequestAttribute(AuthInterceptor.USER_ATTR) AuthenticatedUser user) {
        return service.list(user, limit);
    }

    /** Chuông hỏi endpoint này định kỳ, nên nó chỉ trả một con số. */
    @GetMapping("/unread-count")
    public Map<String, Integer> unreadCount(
            @RequestAttribute(AuthInterceptor.USER_ATTR) AuthenticatedUser user) {
        return Map.of("count", service.unreadCount(user));
    }

    @PutMapping("/{id}/read")
    public ResponseEntity<Void> markRead(
            @PathVariable int id,
            @RequestAttribute(AuthInterceptor.USER_ATTR) AuthenticatedUser user) {
        service.markRead(user, id);
        return ResponseEntity.noContent().build();
    }

    @PutMapping("/read-all")
    public Map<String, Integer> markAllRead(
            @RequestAttribute(AuthInterceptor.USER_ATTR) AuthenticatedUser user) {
        return Map.of("updated", service.markAllRead(user));
    }
}
