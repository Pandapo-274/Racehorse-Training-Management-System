package racehorse.backend.dto;

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
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

/*
 * Body của POST /api/training-plans (UC9, Head Trainer tạo giáo án). Một giáo án gồm 1-12
 * giai đoạn (PhaseRequest). @Valid trước List làm Spring kiểm cả từng PhaseRequest bên trong.
 * groomId: Groom được giao (phải thuộc HLV này, mỗi Groom tối đa 2 giáo án chưa xong - V42).
 * Giáo án mới luôn ở DRAFT; HLV gửi duyệt -> chủ ngựa duyệt -> HLV kích hoạt.
 */
public record TrainingPlanRequest(
        @NotNull(message = "Horse is required") Integer horseId,
        Integer groomId,
        @NotBlank(message = "Plan name is required")
        @Size(max = 150, message = "Plan name must be at most 150 characters") String planName,
        @NotNull(message = "Start date is required") LocalDate startDate,
        @NotNull(message = "End date is required") LocalDate endDate,
        @NotEmpty(message = "A plan needs at least one phase")
        @Size(max = 12, message = "At most 12 phases")
        @Valid List<@NotNull(message = "Phase must not be null") PhaseRequest> phases,
        Integer templateId,
        Integer primaryGroomId,
        Integer backupGroomId,
        String preferredSurface,
        @Pattern(regexp = "LOW|MEDIUM|HIGH", message = "Intensity must be LOW, MEDIUM or HIGH")
        String intensity,
        @Min(value = 100, message = "Max heart rate must be between 100 and 260")
        @Max(value = 260, message = "Max heart rate must be between 100 and 260")
        Integer maxHeartRate,
        @DecimalMin(value = "1.0", message = "Max speed must be between 1 and 90 km/h")
        @DecimalMax(value = "90.0", message = "Max speed must be between 1 and 90 km/h")
        BigDecimal maxSpeed,
        @Min(value = 0, message = "Rest days per week must be between 0 and 6")
        @Max(value = 6, message = "Rest days per week must be between 0 and 6")
        Integer restDaysPerWeek,
        @Min(value = 40, message = "Target HRR must be between 40 and 220 bpm")
        @Max(value = 220, message = "Target HRR must be between 40 and 220 bpm")
        Integer targetHrr,
        Boolean autoSchedule
) {

    public TrainingPlanRequest(Integer horseId, Integer groomId, String planName, LocalDate startDate, LocalDate endDate,
                               List<PhaseRequest> phases) {
        this(horseId, groomId, planName, startDate, endDate, phases,
                null, groomId, null, null, null, null, null, null, null, null);
    }

    public TrainingPlanRequest(Integer horseId, String planName, LocalDate startDate, LocalDate endDate,
                               List<PhaseRequest> phases) {
        this(horseId, null, planName, startDate, endDate, phases,
                null, null, null, null, null, null, null, null, null, null);
    }

    public Integer resolvedPrimaryGroomId() {
        return primaryGroomId != null ? primaryGroomId : groomId;
    }

    /** phase_no không nằm ở đây: service tự đánh số 1, 2, 3 theo thứ tự trong danh sách. */
    // phaseId: null khi tạo mới; có giá trị khi sửa giáo án (giữ giai đoạn cũ thay vì tạo lại,
    // để các buổi tập đã gắn vào giai đoạn đó không bị mất).
    public record PhaseRequest(
            Integer phaseId,
            @NotBlank(message = "Phase name is required")
            @Size(max = 100, message = "Phase name must be at most 100 characters") String phaseName,
            @Positive(message = "Distance must be greater than 0 (meters)") Integer distance,
            @Size(max = 50, message = "Workload must be at most 50 characters") String workload,
            @Pattern(regexp = "TURF|GRASS|DIRT|SYNTHETIC",
                     message = "Surface must be GRASS, TURF, DIRT or SYNTHETIC") String surface,
            @Min(value = 1, message = "At least 1 session per week")
            @Max(value = 365, message = "At most 365 sessions") Integer sessionsPerWeek,
            @Min(value = 100, message = "Heart rate ceiling must be between 100 and 260")
            @Max(value = 260, message = "Heart rate ceiling must be between 100 and 260") Integer heartRateCeiling,
            /* Khoảng nghỉ tối thiểu giữa hai buổi của ngựa trong giai đoạn này. Bỏ trống = 90 phút. */
            @Min(value = 15, message = "Rest between sessions must be between 15 and 720 minutes")
            @Max(value = 720, message = "Rest between sessions must be between 15 and 720 minutes") Integer minGapMinutes,
            /* Tốc độ mục tiêu (km/h): bắt buộc nhập khi tạo/cập nhật giáo án. */
            @NotNull(message = "Target speed is required")
            @DecimalMin(value = "1.0", message = "Target speed must be between 1 and 90 km/h")
            @DecimalMax(value = "90.0", message = "Target speed must be between 1 and 90 km/h") BigDecimal targetVelocity,
            LocalDate startDate,
            LocalDate endDate,
            @Pattern(regexp = "FOUNDATION|BUILD|PEAK",
                     message = "Phase code must be FOUNDATION, BUILD or PEAK") String phaseCode,
            @Min(value = 1, message = "Phase ratio must be between 1% and 98%")
            @Max(value = 98, message = "Phase ratio must be between 1% and 98%") Integer phaseRatio,
            @Min(value = 5, message = "Recovery duration must be between 5 and 120 minutes")
            @Max(value = 120, message = "Recovery duration must be between 5 and 120 minutes") Integer recoveryDurationMinutes,
            @Pattern(regexp = "MORNING|AFTERNOON",
                     message = "Training shift must be MORNING or AFTERNOON") String trainingShift
    ) {
        public PhaseRequest(Integer phaseId, String phaseName, Integer distance, String workload, String surface,
                            Integer sessionsPerWeek, Integer heartRateCeiling, Integer minGapMinutes,
                            BigDecimal targetVelocity, LocalDate startDate, LocalDate endDate,
                            String phaseCode, Integer phaseRatio, Integer recoveryDurationMinutes) {
            this(phaseId, phaseName, distance, workload, surface, sessionsPerWeek, heartRateCeiling,
                    minGapMinutes, targetVelocity, startDate, endDate, phaseCode, phaseRatio,
                    recoveryDurationMinutes, "MORNING");
        }

        public PhaseRequest(Integer phaseId, String phaseName, Integer distance, String workload, String surface,
                            Integer sessionsPerWeek, Integer heartRateCeiling, Integer minGapMinutes,
                            BigDecimal targetVelocity, LocalDate startDate, LocalDate endDate) {
            this(phaseId, phaseName, distance, workload, surface, sessionsPerWeek, heartRateCeiling,
                    minGapMinutes, targetVelocity, startDate, endDate, null, null, null, "MORNING");
        }

        public PhaseRequest(String phaseName, Integer distance, String workload, String surface,
                            Integer sessionsPerWeek, Integer heartRateCeiling, Integer minGapMinutes,
                            BigDecimal targetVelocity, LocalDate startDate, LocalDate endDate) {
            this(null, phaseName, distance, workload, surface, sessionsPerWeek, heartRateCeiling,
                 minGapMinutes, targetVelocity, startDate, endDate, null, null, null, "MORNING");
        }
    }

    public record AutoScheduleOptions(
            java.util.Map<Integer, String> phaseShifts
    ) {}

    /**
     * Cập nhật giáo án: Chỉ cho phép cập nhật groom phụ trách và nội dung các phase.
     * Thông tin chính của plan như horseId, startDate, endDate được giữ nguyên từ plan gốc.
     */
    // Body khi sửa giáo án đang chạy: bản sửa được lưu tạm (pending_update_json, V47) và chờ
    // chủ ngựa duyệt (PUT /{id}/update-review); quá 2 ngày không duyệt thì tự từ chối.
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
    // Body của PUT /api/training-plans/{id}/status.
    public record StatusChange(
            @NotBlank(message = "Status is required")
            @Pattern(regexp = "PENDING_APPROVAL|ACTIVE|PAUSED|COMPLETED",
                     message = "Status must be PENDING_APPROVAL, ACTIVE, PAUSED or COMPLETED") String status
    ) {}

    /** Chủ ngựa duyệt hoặc từ chối giáo án. Từ chối thì bắt buộc ghi lý do. */
    // Body của PUT /api/training-plans/{id}/owner-review: approve=true -> APPROVED,
    // approve=false -> về DRAFT kèm note.
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
