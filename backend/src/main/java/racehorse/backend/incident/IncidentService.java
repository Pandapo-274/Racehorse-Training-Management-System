package racehorse.backend.incident;

import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import racehorse.backend.auth.ApiException;
import racehorse.backend.auth.AuthenticatedUser;
import racehorse.backend.auth.UserRecord;
import racehorse.backend.auth.UserRepository;
import racehorse.backend.notification.Notice;
import racehorse.backend.notification.NotificationService;

@Service
public class IncidentService {

    private static final Set<String> STATUS_FILTERS = Set.of("OPEN", "PENDING", "RECEIVED", "EXAMINED", "ALL");

    private final IncidentRepository incidents;
    private final UserRepository users;
    private final NotificationService notifications;

    public IncidentService(IncidentRepository incidents, UserRepository users, NotificationService notifications) {
        this.incidents = incidents;
        this.users = users;
        this.notifications = notifications;
    }

    /**
     * Dừng một buổi đang chạy vì ngựa bất thường: đổi trạng thái buổi tập, mở báo cáo sự cố và báo người liên quan.
     *
     * @Transactional vì chạm hai bảng (TRAINING_SESSION, INCIDENT_REPORT) cộng thông báo: dừng được buổi
     * mà không mở được báo cáo là trạng thái tệ nhất - ngựa ngưng tập nhưng không bác sĩ nào biết để khám.
     */
    @Transactional
    public StopSessionResponse stopSession(AuthenticatedUser user, int sessionId, StopSessionRequest req, String ip) {
        IncidentRepository.SessionForStop s = incidents.findSessionForStop(sessionId)
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "Training session not found"));

        // Groom chỉ được dừng buổi mình phụ trách; Head Trainer dừng được mọi buổi.
        if ("GROOM".equals(user.role()) && s.assignedTo() != user.userId()) {
            throw new ApiException(HttpStatus.FORBIDDEN, "This session is not assigned to you");
        }
        if (!"RUNNING".equals(s.status())) {
            throw new ApiException(HttpStatus.CONFLICT,
                    "Only a RUNNING session can be stopped (currently " + s.status() + ")");
        }

        String symptoms = req.symptoms().trim();
        String description = (req.description() == null || req.description().isBlank()) ? null : req.description().trim();

        // Đổi trạng thái trước, có điều kiện: nếu người khác vừa dừng/kết thúc buổi này thì nhận 0 dòng và huỷ cả giao dịch.
        if (incidents.markStopped(sessionId) == 0) {
            throw new ApiException(HttpStatus.CONFLICT, "This session has just been stopped or finished by someone else");
        }
        int reportId = incidents.insertReport(s.horseId(), user.userId(), sessionId, symptoms, description, req.urgency());
        users.writeAudit(user.userId(), "SESSION_STOP_INCIDENT", "INCIDENT_REPORT", reportId, "SUCCESS", ip);

        String shortWhat = s.horseName() + ": " + symptoms + " (" + req.urgency() + ").";
        // Bác sĩ thú y: cần đi khám. Người ra tay không cần chuông cho chính mình (notifyRole đã loại trừ).
        notifications.notifyRole("VETERINARIAN", user, new Notice(
                "INCIDENT_REPORTED", "Horse needs examination", Text.clip(shortWhat, 500),
                Notice.HORSE, s.horseId()));
        // Chủ ngựa và Head Trainer: ngựa vừa bị dừng giữa buổi và sẽ không tập tiếp cho tới khi bác sĩ khám.
        notifications.notifyHorseWatchers(s.ownerId(), user, new Notice(
                "TRAINING_SESSION_STOPPED", "Training session stopped",
                Text.clip(s.horseName() + " was stopped during training: " + symptoms + ".", 500),
                Notice.TRAINING_SESSION, sessionId));
        // Head Trainer dừng hộ thì Groom phụ trách phải biết để ngưng việc.
        if (s.assignedTo() != user.userId()) {
            notifications.notifyUser(s.assignedTo(), new Notice(
                    "TRAINING_SESSION_STOPPED", "Training session stopped",
                    Text.clip("Your session with " + s.horseName() + " was stopped: " + symptoms + ".", 500),
                    Notice.TRAINING_SESSION, sessionId));
        }

        IncidentReportResponse report = incidents.findById(reportId)
                .orElseThrow(() -> new ApiException(HttpStatus.INTERNAL_SERVER_ERROR,
                        "The report was created but could not be read back"));
        return new StopSessionResponse(sessionId, "STOPPED_EARLY", report);
    }

    /**
     * Hàng chờ khám. status: OPEN (mặc định = PENDING + RECEIVED), PENDING, RECEIVED, EXAMINED hoặc ALL.
     * Groom chỉ thấy báo cáo do mình lập, chủ ngựa chỉ thấy ngựa của mình, còn lại thấy tất.
     */
    public List<IncidentReportResponse> list(String status, Integer horseId, int limit, AuthenticatedUser user) {
        String f = (status == null || status.isBlank()) ? "OPEN" : status.trim().toUpperCase(Locale.ROOT);
        if (!STATUS_FILTERS.contains(f)) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "Invalid status filter",
                    Map.of("status", "Must be one of OPEN, PENDING, RECEIVED, EXAMINED, ALL"));
        }
        List<String> statuses = switch (f) {
            case "OPEN" -> List.of("PENDING", "RECEIVED");
            case "ALL" -> List.of();
            default -> List.of(f);
        };
        Integer reporterId = "GROOM".equals(user.role()) ? user.userId() : null;
        Integer ownerId = "HORSE_OWNER".equals(user.role()) ? user.userId() : null;
        boolean urgentFirst = !"EXAMINED".equals(f) && !"ALL".equals(f);
        return incidents.search(statuses, horseId, reporterId, ownerId, urgentFirst,
                Math.max(1, Math.min(limit, 200)));
    }

    public IncidentReportResponse get(int reportId, AuthenticatedUser user) {
        IncidentReportResponse r = incidents.findById(reportId)
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "Incident report not found"));
        if ("GROOM".equals(user.role()) && r.reporterId() != user.userId()) {
            throw new ApiException(HttpStatus.FORBIDDEN, "This report was filed by someone else");
        }
        if ("HORSE_OWNER".equals(user.role()) && r.ownerId() != user.userId()) {
            throw new ApiException(HttpStatus.FORBIDDEN, "This report belongs to another owner's horse");
        }
        return r;
    }

    /** Bác sĩ nhận ca: PENDING -> RECEIVED. Chưa phải đã khám: báo cáo chỉ đóng khi có bản khám kèm reportId. */
    @Transactional
    public IncidentReportResponse receive(AuthenticatedUser vet, int reportId, String ip) {
        IncidentReportResponse r = incidents.findById(reportId)
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "Incident report not found"));
        if (incidents.markReceived(reportId, vet.userId()) == 0) {
            throw new ApiException(HttpStatus.CONFLICT,
                    "Only a PENDING report can be received (currently " + r.status() + ")");
        }
        users.writeAudit(vet.userId(), "INCIDENT_RECEIVE", "INCIDENT_REPORT", reportId, "SUCCESS", ip);

        String vetName = users.findById(vet.userId()).map(UserRecord::fullName).orElse("A veterinarian");
        // Người đã báo (thường là Groom) muốn biết có người nhận ca chưa.
        if (r.reporterId() != vet.userId()) {
            notifications.notifyUser(r.reporterId(), new Notice(
                    "INCIDENT_RECEIVED", "Veterinarian assigned",
                    Text.clip(vetName + " is handling the report for " + r.horseName() + ".", 500),
                    Notice.HORSE, r.horseId()));
        }
        return incidents.findById(reportId)
                .orElseThrow(() -> new ApiException(HttpStatus.INTERNAL_SERVER_ERROR,
                        "The report was updated but could not be read back"));
    }
}
