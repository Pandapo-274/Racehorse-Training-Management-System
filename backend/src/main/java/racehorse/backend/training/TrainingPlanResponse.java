package racehorse.backend.training;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

public record TrainingPlanResponse(
        int planId, int horseId, String horseName, Integer groomId, String groomName,
        String planName, LocalDate startDate, LocalDate endDate, String status,
        int createdBy, String createdByName, LocalDateTime createdAt,
        String ownerNote,                 // lý do chủ ngựa từ chối (V34), null nếu chưa có
        LocalDateTime ownerReviewedAt,    // lúc chủ ngựa duyệt/từ chối gần nhất
        List<Phase> phases
) {
    public TrainingPlanResponse(
            int planId, int horseId, String horseName, String planName,
            LocalDate startDate, LocalDate endDate, String status,
            int createdBy, String createdByName, LocalDateTime createdAt,
            String ownerNote, LocalDateTime ownerReviewedAt, List<Phase> phases) {
        this(planId, horseId, horseName, null, null, planName, startDate, endDate,
                status, createdBy, createdByName, createdAt, ownerNote, ownerReviewedAt, phases);
    }

    /** Record bất biến nên muốn gắn thêm danh sách giai đoạn thì phải tạo bản mới. */
    public TrainingPlanResponse withPhases(List<Phase> newPhases) {
        return new TrainingPlanResponse(planId, horseId, horseName, groomId, groomName, planName, startDate, endDate,
                status, createdBy, createdByName, createdAt, ownerNote, ownerReviewedAt, newPhases);
    }

    public record Phase(int phaseId, int phaseNo, String phaseName, Integer distance,
                        String workload, String surface, Integer sessionsPerWeek,
                        Integer heartRateCeiling, LocalDate startDate, LocalDate endDate,
                        Integer minGapMinutes, BigDecimal targetVelocity) {}

    /** Dòng gọn cho màn danh sách, không kèm chi tiết giai đoạn. */
    public record Summary(int planId, int horseId, String horseName, Integer groomId, String groomName,
                          String planName, LocalDate startDate, LocalDate endDate, String status, int phaseCount,
                          String ownerNote) {
        public Summary(int planId, int horseId, String horseName, String planName,
                       LocalDate startDate, LocalDate endDate, String status, int phaseCount,
                       String ownerNote) {
            this(planId, horseId, horseName, null, null, planName, startDate, endDate, status,
                    phaseCount, ownerNote);
        }
    }
}