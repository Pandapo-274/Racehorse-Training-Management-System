package racehorse.backend.training;

import java.time.LocalDate;
import java.util.List;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;

public record TrainingPlanRequest(
        @NotNull(message = "Vui lòng chọn ngựa") Integer horseId,
        @NotBlank(message = "Tên giáo án là bắt buộc")
        @Size(max = 150, message = "Tên giáo án tối đa 150 ký tự") String planName,
        @NotNull(message = "Ngày bắt đầu là bắt buộc") LocalDate startDate,
        @NotNull(message = "Ngày kết thúc là bắt buộc") LocalDate endDate,
        @NotEmpty(message = "Giáo án cần ít nhất một giai đoạn")
        @Size(max = 12, message = "Tối đa 12 giai đoạn")
        @Valid List<@NotNull(message = "Giai đoạn không được rỗng") PhaseRequest> phases
) {

    /** phase_no không nằm ở đây: service tự đánh số 1, 2, 3 theo thứ tự trong danh sách. */
    public record PhaseRequest(
            @NotBlank(message = "Tên giai đoạn là bắt buộc")
            @Size(max = 100, message = "Tên giai đoạn tối đa 100 ký tự") String phaseName,
            @Positive(message = "Cự ly phải lớn hơn 0 (mét)") Integer distance,
            @Size(max = 50, message = "Cường độ tối đa 50 ký tự") String workload,
            @Pattern(regexp = "TURF|DIRT|SYNTHETIC",
                     message = "Mặt đường phải là TURF, DIRT hoặc SYNTHETIC") String surface,
            @Min(value = 1, message = "Tối thiểu 1 buổi mỗi tuần")
            @Max(value = 14, message = "Tối đa 14 buổi mỗi tuần") Integer sessionsPerWeek,
            @Min(value = 100, message = "Ngưỡng nhịp tim từ 100 đến 260")
            @Max(value = 260, message = "Ngưỡng nhịp tim từ 100 đến 260") Integer heartRateCeiling,
            @NotNull(message = "Ngày bắt đầu giai đoạn là bắt buộc") LocalDate startDate,
            @NotNull(message = "Ngày kết thúc giai đoạn là bắt buộc") LocalDate endDate
    ) {}

    public record StatusChange(
            @NotBlank(message = "Trạng thái là bắt buộc")
            @Pattern(regexp = "DRAFT|ACTIVE|PAUSED|COMPLETED",
                     message = "Trạng thái phải là DRAFT, ACTIVE, PAUSED hoặc COMPLETED") String status
    ) {}
}