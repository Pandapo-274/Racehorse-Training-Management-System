package racehorse.backend.dto;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/**
 * Body cho PUT /api/training-sessions/{id}/complete-recovery:
 * Groom nhập chỉ số nhịp tim hồi phục thực tế sau giai đoạn recovery (bpm).
 */
public record CompleteRecoveryRequest(
        @NotNull(message = "Actual recovery heart rate is required")
        @Min(value = 20, message = "Recovery heart rate must be between 20 and 300 bpm")
        @Max(value = 300, message = "Recovery heart rate must be between 20 and 300 bpm")
        Integer actualHrr,
        @Size(max = 500, message = "Remarks must be at most 500 characters")
        String remarks
) {}
