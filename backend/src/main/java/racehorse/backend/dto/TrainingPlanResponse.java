package racehorse.backend.dto;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

// Chi tiết một giáo án (GET /api/training-plans/{id}) kèm danh sách giai đoạn và bản sửa
// đang chờ duyệt (nếu có). Các constructor phụ phục vụ các câu SQL không đọc đủ cột.
public record TrainingPlanResponse(
        int planId, int horseId, String horseName, Integer groomId, String groomName,
        String planName, LocalDate startDate, LocalDate endDate, String status,
        int createdBy, String createdByName, LocalDateTime createdAt,
        String ownerNote,                 // lý do chủ ngựa từ chối (V34), null nếu chưa có
        LocalDateTime ownerReviewedAt,    // lúc chủ ngựa duyệt/từ chối gần nhất
        List<Phase> phases,
        PendingUpdate pendingUpdate,      // V47: bản cập nhật đang chờ chủ ngựa duyệt (null nếu không có)
        Integer templateId,
        Integer primaryGroomId, String primaryGroomName,
        Integer backupGroomId, String backupGroomName,
        String preferredSurface, String intensity,
        Integer maxHeartRate, BigDecimal maxSpeed,
        Integer restDaysPerWeek, Integer targetHrr
) {
    public TrainingPlanResponse(
            int planId, int horseId, String horseName, Integer groomId, String groomName,
            String planName, LocalDate startDate, LocalDate endDate, String status,
            int createdBy, String createdByName, LocalDateTime createdAt,
            String ownerNote, LocalDateTime ownerReviewedAt, List<Phase> phases,
            PendingUpdate pendingUpdate) {
        this(planId, horseId, horseName, groomId, groomName, planName, startDate, endDate,
                status, createdBy, createdByName, createdAt, ownerNote, ownerReviewedAt, phases, pendingUpdate,
                null, groomId, groomName, null, null, null, null, null, null, null, null);
    }

    public TrainingPlanResponse(
            int planId, int horseId, String horseName, Integer groomId, String groomName,
            String planName, LocalDate startDate, LocalDate endDate, String status,
            int createdBy, String createdByName, LocalDateTime createdAt,
            String ownerNote, LocalDateTime ownerReviewedAt, List<Phase> phases) {
        this(planId, horseId, horseName, groomId, groomName, planName, startDate, endDate,
                status, createdBy, createdByName, createdAt, ownerNote, ownerReviewedAt, phases, null,
                null, groomId, groomName, null, null, null, null, null, null, null, null);
    }

    public TrainingPlanResponse(
            int planId, int horseId, String horseName, String planName,
            LocalDate startDate, LocalDate endDate, String status,
            int createdBy, String createdByName, LocalDateTime createdAt,
            String ownerNote, LocalDateTime ownerReviewedAt, List<Phase> phases) {
        this(planId, horseId, horseName, null, null, planName, startDate, endDate,
                status, createdBy, createdByName, createdAt, ownerNote, ownerReviewedAt, phases, null,
                null, null, null, null, null, null, null, null, null, null, null);
    }

    /** Record bất biến nên muốn gắn thêm danh sách giai đoạn thì phải tạo bản mới. */
    // Kiểu "wither": trả về bản sao với một trường thay đổi, vì record không có setter.
    public TrainingPlanResponse withPhases(List<Phase> newPhases) {
        return new TrainingPlanResponse(planId, horseId, horseName, groomId, groomName, planName, startDate, endDate,
                status, createdBy, createdByName, createdAt, ownerNote, ownerReviewedAt, newPhases, pendingUpdate,
                templateId, primaryGroomId, primaryGroomName, backupGroomId, backupGroomName,
                preferredSurface, intensity, maxHeartRate, maxSpeed, restDaysPerWeek, targetHrr);
    }

    public TrainingPlanResponse withPendingUpdate(PendingUpdate pending) {
        return new TrainingPlanResponse(planId, horseId, horseName, groomId, groomName, planName, startDate, endDate,
                status, createdBy, createdByName, createdAt, ownerNote, ownerReviewedAt, phases, pending,
                templateId, primaryGroomId, primaryGroomName, backupGroomId, backupGroomName,
                preferredSurface, intensity, maxHeartRate, maxSpeed, restDaysPerWeek, targetHrr);
    }

    public record Phase(int phaseId, int phaseNo, String phaseName, Integer distance,
                        String workload, String surface, Integer sessionsPerWeek,
                        Integer heartRateCeiling, LocalDate startDate, LocalDate endDate,
                        Integer minGapMinutes, BigDecimal targetVelocity,
                        String phaseCode, Integer phaseRatio, Integer recoveryDurationMinutes) {
        public Phase(int phaseId, int phaseNo, String phaseName, Integer distance,
                     String workload, String surface, Integer sessionsPerWeek,
                     Integer heartRateCeiling, LocalDate startDate, LocalDate endDate,
                     Integer minGapMinutes, BigDecimal targetVelocity) {
            this(phaseId, phaseNo, phaseName, distance, workload, surface, sessionsPerWeek,
                    heartRateCeiling, startDate, endDate, minGapMinutes, targetVelocity,
                    null, null, 15);
        }
    }

    public record TemplatePhaseDto(int phaseNo, String phaseCode, String phaseName, int phaseRatio,
                                   int recoveryDurationMinutes, int distance, int heartRateCeiling,
                                   BigDecimal targetVelocity, String surface, String workload, int sessionsPerWeek) {}

    public record TemplateDto(int templateId, String templateCode, String templateName,
                              String breed, String runningStyle, String intensity,
                              int maxHeartRate, BigDecimal maxSpeed, int restDaysPerWeek,
                              int targetHrr, int baseDistanceM, List<TemplatePhaseDto> phases) {}

    public record TemplateRecommendation(Integer horseId, String horseName, String breed,
                                         String runningStyle, String distanceType, String lockedSurface,
                                         TemplateDto recommendedTemplate, List<TemplateDto> allTemplates) {}

    public record AutoScheduleResult(int planId, int createdTrainingSessions, int createdRestSlots,
                                     int conflictCount, List<TrainingSessionResponse> sessions) {}

    // Bản sửa đang chờ chủ ngựa duyệt, đọc ra từ cột pending_update_json (JSON -> record).
    public record PendingUpdate(String status, LocalDateTime requestedAt, Integer groomId,
                                String groomName, String planName,
                                List<TrainingPlanRequest.PhaseRequest> phases) {}

    /** Dòng gọn cho màn danh sách, không kèm chi tiết giai đoạn. */
    public record Summary(int planId, int horseId, String horseName, Integer groomId, String groomName,
                          String planName, LocalDate startDate, LocalDate endDate, String status, int phaseCount,
                          String ownerNote, boolean hasPendingUpdate) {
        public Summary(int planId, int horseId, String horseName, Integer groomId, String groomName,
                       String planName, LocalDate startDate, LocalDate endDate, String status, int phaseCount,
                       String ownerNote) {
            this(planId, horseId, horseName, groomId, groomName, planName, startDate, endDate, status,
                    phaseCount, ownerNote, false);
        }

        public Summary(int planId, int horseId, String horseName, String planName,
                       LocalDate startDate, LocalDate endDate, String status, int phaseCount,
                       String ownerNote) {
            this(planId, horseId, horseName, null, null, planName, startDate, endDate, status,
                    phaseCount, ownerNote, false);
        }
    }
}
