package racehorse.backend.entity;

import java.time.LocalDateTime;

/** Một thông báo như chuông trên frontend nhìn thấy. read = đã mở chưa. */
// createdAt/readAt là LocalDateTime UTC không kèm múi giờ; frontend (timeAgo trong
// notificationApi.js) thêm "Z" trước khi parse để hiện đúng giờ Việt Nam.
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
