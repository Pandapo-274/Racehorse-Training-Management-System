package racehorse.backend.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;

/*
 * Body của PUT /api/alerts/{alertId}/resolve (Head Trainer xử lý cảnh báo):
 *   CONTINUE         - cho tập tiếp
 *   REDUCE_INTENSITY - giảm cường độ
 *   STOP_SESSION     - dừng buổi tập (mở báo cáo sự cố, báo Groom)
 *   VET_CALLED       - bị từ chối 400 ở endpoint này: phải dùng POST /api/alerts/{id}/vet-call
 */
public record ResolveAlertRequest(
        @NotBlank(message = "Action is required")
        @Pattern(regexp = "CONTINUE|REDUCE_INTENSITY|STOP_SESSION|VET_CALLED",
                message = "Action must be CONTINUE, REDUCE_INTENSITY, STOP_SESSION or VET_CALLED")
        String actionTaken
) {}
