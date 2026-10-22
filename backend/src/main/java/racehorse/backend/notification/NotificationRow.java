package racehorse.backend.notification;

import java.time.LocalDateTime;

/** Một thông báo như chuông trên frontend nhìn thấy. read = đã mở chưa. */
public record NotificationRow(
        int notificationId,
        String type,
        String title,
        String message,
        String targetEntity,
        Integer targetId,
        LocalDateTime createdAt,
        LocalDateTime readAt,
        boolean read
) {}
