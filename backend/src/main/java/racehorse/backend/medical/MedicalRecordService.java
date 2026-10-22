package racehorse.backend.medical;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import racehorse.backend.auth.ApiException;
import racehorse.backend.auth.AuthenticatedUser;
import racehorse.backend.auth.UserRepository;
import racehorse.backend.horse.HorseRepository;
import racehorse.backend.horse.HorseResponse;
import racehorse.backend.notification.Notice;
import racehorse.backend.notification.NotificationService;

@Service
public class MedicalRecordService {

    private static final String DEFAULT_TYPE = "EXAM";

    private final MedicalRecordRepository records;
    private final HorseRepository horses;
    private final UserRepository users;
    private final NotificationService notifications;

    public MedicalRecordService(MedicalRecordRepository records, HorseRepository horses,
                                UserRepository users, NotificationService notifications) {
        this.records = records;
        this.horses = horses;
        this.users = users;
        this.notifications = notifications;
    }

    /**
     * UC14 - ghi một lần khám.
     *
     * @Transactional vì một lần khám chạm vào ba bảng: MEDICAL_RECORD,
     * HORSE.status và INCIDENT_REPORT.status. Ghi được bản ghi khám mà không
     * đổi được trạng thái con ngựa là trạng thái tệ nhất - hồ sơ nói ngựa bị
     * thương còn sổ đăng ký vẫn nói nó sẵn sàng tập, và không ai biết cái nào
     * đúng.
     */
    @Transactional
    public MedicalRecordRow log(MedicalExamRequest raw, AuthenticatedUser vet, String ip) {
        HorseResponse horse = horses.findById(raw.horseId())
                .orElseThrow(() -> fieldError("horseId", "Horse #" + raw.horseId() + " does not exist"));

        String recordType = (raw.recordType() == null || raw.recordType().isBlank())
                ? DEFAULT_TYPE : raw.recordType();

        validate(raw, horse, recordType);

        int recordId = records.insert(raw, vet.userId(), recordType);

        /* Thứ tự có chủ đích: báo cáo trước, trạng thái ngựa sau. Nếu báo cáo
           bị người khác khám mất thì toàn bộ giao dịch phải bị huỷ, kể cả việc
           đổi trạng thái - đổi rồi mới phát hiện xung đột thì trạng thái đã kịp
           sai trong cùng một giao dịch. */
        if (raw.reportId() != null && records.markReportExamined(raw.reportId()) == 0) {
            throw fieldError("reportId",
                    "Another veterinarian has already examined this report");
        }

        if (raw.healthStatus() != null) {
            records.updateHorseStatus(horse.horseId(), raw.healthStatus());
        }

        users.writeAudit(vet.userId(), "MEDICAL_EXAM", "MEDICAL_RECORD", recordId, "SUCCESS", ip);

        /* Chỉ báo khi tình trạng thật sự đổi. Một lần khám định kỳ giữ nguyên
           tình trạng thì không phải tin gì mới, và chuông kêu cho mọi lần khám
           sẽ dạy người ta bỏ qua chuông. */
        if (raw.healthStatus() != null && !raw.healthStatus().equals(horse.status())) {
            notifications.notifyHorseWatchers(horse.ownerId(), vet, new Notice(
                    "HEALTH_STATUS_CHANGED",
                    "Health status changed",
                    horse.horseName() + ": " + horse.status() + " to " + raw.healthStatus() + ".",
                    Notice.HORSE, horse.horseId()));
        }

        return records.findById(recordId)
                .orElseThrow(() -> new ApiException(HttpStatus.INTERNAL_SERVER_ERROR,
                        "The record was written but could not be read back"));
    }

    public List<MedicalRecordRow> listForHorse(int horseId, AuthenticatedUser user) {
        HorseResponse horse = horses.findById(horseId)
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND,
                        "Horse #" + horseId + " does not exist"));
        checkCanView(horse, user);
        return records.findByHorse(horseId);
    }

    public MedicalRecordRow get(int recordId, AuthenticatedUser user) {
        MedicalRecordRow row = records.findById(recordId)
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND,
                        "Medical record #" + recordId + " does not exist"));
        horses.findById(row.horseId()).ifPresent(h -> checkCanView(h, user));
        return row;
    }

    /* ------------------------------------------------------------------ */

    /**
     * Chủ ngựa chỉ xem được hồ sơ bệnh án ngựa của mình; các vai trò khác xem
     * tất. Cùng quy tắc HorseService đang dùng - chép lại ở đây thay vì gọi
     * sang vì nó là private bên đó, và hai chỗ cùng sửa khi quy tắc đổi thì
     * vẫn hơn là mở rộng phạm vi một phương thức chỉ để dùng chung.
     */
    private void checkCanView(HorseResponse horse, AuthenticatedUser user) {
        if ("HORSE_OWNER".equals(user.role()) && horse.ownerId() != user.userId()) {
            throw new ApiException(HttpStatus.FORBIDDEN, "You may not view this horse's records");
        }
    }

    private void validate(MedicalExamRequest r, HorseResponse horse, String recordType) {
        Map<String, String> errors = new HashMap<>();

        /* Báo cáo sự cố phải thuộc đúng con ngựa đang khám. Không kiểm thì một
           lần khám có thể được gắn vào báo cáo của con khác, và hàng chờ khám
           của con đó bị đóng oan. */
        if (r.reportId() != null) {
            records.findReportHead(r.reportId()).ifPresentOrElse(head -> {
                if (head.horseId() != horse.horseId()) {
                    errors.put("reportId", "That incident report belongs to a different horse");
                } else if ("EXAMINED".equals(head.status())) {
                    errors.put("reportId", "That incident report has already been examined");
                }
            }, () -> errors.put("reportId", "Incident report #" + r.reportId() + " does not exist"));
        }

        /* Xác nhận có chấn thương thì tình trạng phải là INJURED. Để hai thứ
           lệch nhau là hồ sơ tự mâu thuẫn: khẳng định có chấn thương nhưng vẫn
           xếp con ngựa vào diện chỉ cần theo dõi. */
        if (r.injuryConfirmed() && !"INJURED".equals(r.healthStatus())) {
            errors.put("healthStatus",
                    "A confirmed injury must set the health status to INJURED");
        }

        /* Số ngày nghỉ chỉ có nghĩa khi đã xác nhận chấn thương. */
        if (r.expectedRestDays() != null && !r.injuryConfirmed()) {
            errors.put("expectedRestDays",
                    "Rest days apply to a confirmed injury only");
        }

        /* Ba loại chăm sóc định kỳ sinh ra để nhắc lần sau, nên thiếu ngày hẹn
           là bản ghi không làm được việc của nó: màn nhắc lịch sẽ bỏ sót. */
        if (!DEFAULT_TYPE.equals(recordType) && r.nextDueDate() == null) {
            errors.put("nextDueDate",
                    "A " + recordType.toLowerCase() + " record needs the date it is next due");
        }

        if (!errors.isEmpty()) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "The examination could not be saved", errors);
        }
    }

    private static ApiException fieldError(String field, String message) {
        return new ApiException(HttpStatus.BAD_REQUEST, message, Map.of(field, message));
    }
}
