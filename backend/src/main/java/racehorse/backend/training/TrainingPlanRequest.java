package racehorse.backend.training;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;

public record TrainingPlanRequest(
        @NotNull(message = "Horse is required") Integer horseId,
        @NotNull(message = "Assigned groom is required") Integer groomId,
        @NotBlank(message = "Plan name is required")
        @Size(max = 150, message = "Plan name must be at most 150 characters") String planName,
        @NotNull(message = "Start date is required") LocalDate startDate,
        @NotNull(message = "End date is required") LocalDate endDate,
        @NotEmpty(message = "A plan needs at least one phase")
        @Size(max = 12, message = "At most 12 phases")
        @Valid List<@NotNull(message = "Phase must not be null") PhaseRequest> phases
) {

    public TrainingPlanRequest(Integer horseId, String planName, LocalDate startDate, LocalDate endDate,
                               List<PhaseRequest> phases) {
        this(horseId, null, planName, startDate, endDate, phases);
    }

    /** phase_no không nằm ở đây: service tự đánh số 1, 2, 3 theo thứ tự trong danh sách. */
    public record PhaseRequest(
            Integer phaseId,
            @NotBlank(message = "Phase name is required")
            @Size(max = 100, message = "Phase name must be at most 100 characters") String phaseName,
            @Positive(message = "Distance must be greater than 0 (meters)") Integer distance,
            @Size(max = 50, message = "Workload must be at most 50 characters") String workload,
            @Pattern(regexp = "TURF|DIRT|SYNTHETIC",
                     message = "Surface must be TURF, DIRT or SYNTHETIC") String surface,
            @Min(value = 1, message = "At least 1 session per week")
            @Max(value = 14, message = "At most 14 sessions per week") Integer sessionsPerWeek,
            @Min(value = 100, message = "Heart rate ceiling must be between 100 and 260")
            @Max(value = 260, message = "Heart rate ceiling must be between 100 and 260") Integer heartRateCeiling,
            /* Khoảng nghỉ tối thiểu giữa hai buổi của ngựa trong giai đoạn này. Bỏ trống = 90 phút. */
            @Min(value = 15, message = "Rest between sessions must be between 15 and 720 minutes")
            @Max(value = 720, message = "Rest between sessions must be between 15 and 720 minutes") Integer minGapMinutes,
            /* Tốc độ mục tiêu (m/s): điểm Speed so với mục tiêu này thay vì 18 m/s. */
            @DecimalMin(value = "1.0", message = "Target speed must be between 1 and 25 m/s")
            @DecimalMax(value = "25.0", message = "Target speed must be between 1 and 25 m/s") BigDecimal targetVelocity,
            @NotNull(message = "Phase start date is required") LocalDate startDate,
            @NotNull(message = "Phase end date is required") LocalDate endDate
    ) {
        public PhaseRequest(String phaseName, Integer distance, String workload, String surface,
                            Integer sessionsPerWeek, Integer heartRateCeiling, Integer minGapMinutes,
                            BigDecimal targetVelocity, LocalDate startDate, LocalDate endDate) {
            this(null, phaseName, distance, workload, surface, sessionsPerWeek, heartRateCeiling,
                 minGapMinutes, targetVelocity, startDate, endDate);
        }
    }

    /**
     * Cập nhật giáo án: Chỉ cho phép cập nhật groom phụ trách và nội dung các phase.
     * Thông tin chính của plan như horseId, startDate, endDate được giữ nguyên từ plan gốc.
     */
    public record Update(
            @NotNull(message = "Assigned groom is required") Integer groomId,
            @Size(max = 150, message = "Plan name must be at most 150 characters") String planName,
            @NotEmpty(message = "A plan needs at least one phase")
            @Size(max = 12, message = "At most 12 phases")
            @Valid List<@NotNull(message = "Phase must not be null") PhaseRequest> phases
    ) {}

    /**
     * Head Trainer đổi trạng thái. Không có APPROVED: chỉ chủ ngựa duyệt
     * được (OwnerReview), Head Trainer không tự đánh dấu là đã duyệt.
     */
    public record StatusChange(
            @NotBlank(message = "Status is required")
            @Pattern(regexp = "PENDING_APPROVAL|ACTIVE|PAUSED|COMPLETED",
                     message = "Status must be PENDING_APPROVAL, ACTIVE, PAUSED or COMPLETED") String status
    ) {}

    /** Chủ ngựa duyệt hoặc từ chối giáo án. Từ chối thì bắt buộc ghi lý do. */
    public record OwnerReview(
            @NotNull(message = "Decision is required") Boolean approve,
            @Size(max = 500, message = "Note must be at most 500 characters") String note
    ) {}

    /** Chủ ngựa gửi phản hồi/nhận xét (optional) cho Head Trainer về giáo án. */
    public record OwnerFeedback(
            @NotBlank(message = "Feedback note cannot be blank")
            @Size(max = 500, message = "Feedback must be at most 500 characters") String note
    ) {}
}