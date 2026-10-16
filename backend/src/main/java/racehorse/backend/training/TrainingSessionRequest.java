package racehorse.backend.training;

import java.time.LocalDateTime;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;

/** horse_id KHÔNG nằm ở đây: service lấy từ phase, không cho client nhập tay. */
public record TrainingSessionRequest(
        @NotNull(message = "Vui lòng chọn giai đoạn") Integer phaseId,
        @NotNull(message = "Vui lòng chọn người phụ trách") Integer assignedTo,
        @NotNull(message = "Thời gian buổi tập là bắt buộc") LocalDateTime sessionDate,
        @Positive(message = "Cự ly dự kiến phải lớn hơn 0 (mét)") Integer plannedDistance
) {}