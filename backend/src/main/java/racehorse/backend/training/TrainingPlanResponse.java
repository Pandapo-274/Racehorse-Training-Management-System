package racehorse.backend.training;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

public record TrainingPlanResponse(
        int planId, int horseId, String horseName, String planName,
        LocalDate startDate, LocalDate endDate, String status,
        int createdBy, String createdByName, LocalDateTime createdAt,
        List<Phase> phases
) {
    /** Record bất biến nên muốn gắn thêm danh sách giai đoạn thì phải tạo bản mới. */
    public TrainingPlanResponse withPhases(List<Phase> newPhases) {
        return new TrainingPlanResponse(planId, horseId, horseName, planName, startDate, endDate,
                status, createdBy, createdByName, createdAt, newPhases);
    }

    public record Phase(int phaseId, int phaseNo, String phaseName, Integer distance,
                        String workload, String surface, Integer sessionsPerWeek,
                        Integer heartRateCeiling, LocalDate startDate, LocalDate endDate) {}

    /** Dòng gọn cho màn danh sách, không kèm chi tiết giai đoạn. */
    public record Summary(int planId, int horseId, String horseName, String planName,
                          LocalDate startDate, LocalDate endDate, String status, int phaseCount) {}
}