package racehorse.backend.incident;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import racehorse.backend.auth.ApiException;

/**
 * Chốt chặn "ngựa còn báo cáo sự cố chưa khám thì không được tập tiếp".
 *
 * Tách thành một component riêng (thay vì viết thẳng trong TrainingSessionService) để các chỗ
 * xếp lịch, đổi lịch, bấm Start cùng gọi MỘT hàm. Báo cáo chỉ đóng khi bác sĩ ghi bản khám có
 * kèm reportId (MedicalRecordService.log -> markReportExamined).
 */
@Component
public class IncidentGuard {

    private final IncidentRepository incidents;

    public IncidentGuard(IncidentRepository incidents) {
        this.incidents = incidents;
    }

    public void assertNoOpenReport(int horseId) {
        incidents.findOpenReport(horseId).ifPresent(open -> {
            throw new ApiException(HttpStatus.CONFLICT,
                    "Horse has an open incident report (#" + open.reportId() + ", " + open.urgency()
                            + ", " + open.status() + "). A veterinarian must examine it before training can resume");
        });
    }
}
