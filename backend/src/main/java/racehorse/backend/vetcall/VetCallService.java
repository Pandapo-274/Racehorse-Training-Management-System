package racehorse.backend.vetcall;

import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import racehorse.backend.alert.AlertRepository;
import racehorse.backend.alert.AlertResponse;
import racehorse.backend.alert.SessionContext;
import racehorse.backend.auth.ApiException;
import racehorse.backend.auth.AuthenticatedUser;
import racehorse.backend.auth.UserRepository;
import racehorse.backend.horse.HorseRepository;
import racehorse.backend.horse.HorseResponse;
import racehorse.backend.incident.IncidentRepository;
import racehorse.backend.notification.Notice;
import racehorse.backend.notification.NotificationService;

/**
 * HLV gọi bác sĩ từ một cảnh báo buổi tập, bác sĩ trả lời từ xa.
 *
 * Gọi (Head Trainer): cảnh báo được đánh dấu VET_CALLED (cùng câu UPDATE có điều kiện
 * như các cách xử lý khác, nên hai người bấm cùng lúc chỉ một người gọi được), ghi
 * lý do và dấu hiệu, tuỳ chọn dừng buổi tập ngay, báo chuông mọi bác sĩ.
 *
 * Trả lời (Veterinarian), một lần cho mỗi ca:
 *   CONTINUE_LIGHT  buổi tập tiếp tục ở cường độ nhẹ, Groom nhận hướng dẫn.
 *   TREAT_ON_SITE   dừng buổi tập, chữa tại chuồng; bắt buộc ghi thuốc dùng ngay.
 *   EMERGENCY_STOP  dừng mọi buổi đang chạy của ngựa và mở báo cáo sự cố do chính
 *                   bác sĩ này nhận: ngựa vào hàng chờ khám, IncidentGuard chặn tập
 *                   và đăng ký giải cho tới khi khám xong, đúng luồng Flow 3 sẵn có.
 * Kết quả báo cho HLV đã gọi và Groom đang dắt ngựa.
 */
@Service
public class VetCallService {

    private static final Set<String> STATUSES = Set.of("PENDING", "ANSWERED");

    private final VetCallRepository calls;
    private final AlertRepository alerts;
    private final IncidentRepository incidents;
    private final HorseRepository horses;
    private final UserRepository users;
    private final NotificationService notifications;

    public VetCallService(VetCallRepository calls, AlertRepository alerts, IncidentRepository incidents,
                          HorseRepository horses, UserRepository users, NotificationService notifications) {
        this.calls = calls;
        this.alerts = alerts;
        this.incidents = incidents;
        this.horses = horses;
        this.users = users;
        this.notifications = notifications;
    }

    @Transactional
    public VetCallResponse call(AuthenticatedUser me, int alertId, VetCallRequest req, String ip) {
        AlertResponse alert = alerts.findById(alertId)
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "Alert not found"));
        if (!alerts.resolve(alertId, "VET_CALLED", me.userId())) {
            throw new ApiException(HttpStatus.CONFLICT, "Alert has already been resolved");
        }
        SessionContext session = alerts.findSessionContext(alert.sessionId())
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "Training session not found"));

        boolean stopped = Boolean.TRUE.equals(req.stopSession())
                && incidents.markStopped(alert.sessionId()) == 1;
        String reason = req.reason().trim();
        String observations = blankToNull(req.observations());
        int callId = calls.insert(alertId, alert.sessionId(), alert.horseId(), me.userId(), reason,
                observations, req.urgency(), stopped);

        users.writeAudit(me.userId(), "RESOLVE_ALERT:VET_CALLED", "THRESHOLD_ALERT", alertId, "SUCCESS", ip,
                "Vet call #" + callId + (stopped ? ", session stopped" : ""));
        if (stopped) {
            users.writeAudit(me.userId(), "SESSION_STOPPED_FOR_VET", "TRAINING_SESSION", alert.sessionId(),
                    "SUCCESS", ip);
            notifications.notifyUser(session.assignedTo(), new Notice("TRAINING_SESSION_STOPPED",
                    "Training session stopped",
                    clip(alert.horseName() + ": the head trainer stopped the session and called a veterinarian. "
                            + "Keep the horse still and wait for instructions.", 500),
                    Notice.TRAINING_SESSION, alert.sessionId()));
        }
        notifications.notifyRole("VETERINARIAN", me, new Notice("VET_CALL_REQUESTED",
                "Veterinarian needed at training",
                clip(alert.horseName() + " (" + req.urgency() + "): " + reason, 500),
                Notice.VET_CALL, callId));

        return calls.findById(callId).orElseThrow();
    }

    public List<VetCallResponse> list(String status, int limit) {
        String s = status == null || status.isBlank() || "ALL".equalsIgnoreCase(status)
                ? null : status.trim().toUpperCase();
        if (s != null && !STATUSES.contains(s)) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "Status must be PENDING, ANSWERED or ALL");
        }
        return calls.search(s, Math.max(1, Math.min(limit, 100)));
    }

    public VetCallResponse get(int callId) {
        return calls.findById(callId)
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "Vet call not found"));
    }

    @Transactional
    public VetCallResponse answer(AuthenticatedUser vet, int callId, VetCallAnswer req, String ip) {
        VetCallResponse call = get(callId);
        String decision = req.decision();
        String instructions = req.instructions().trim();
        String medication = blankToNull(req.medication());
        if ("TREAT_ON_SITE".equals(decision) && medication == null) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "Invalid answer",
                    Map.of("medication", "Say which medication to give now when treating at the stable"));
        }
        if (calls.answer(callId, vet.userId(), decision, instructions, medication) == 0) {
            VetCallResponse now = get(callId);
            throw new ApiException(HttpStatus.CONFLICT, "This call was already answered"
                    + (now.answeredByName() == null ? "" : " by " + now.answeredByName()));
        }

        // Tiếp tục nhẹ thì buổi tập giữ nguyên; hai quyết định còn lại đều dừng ngựa.
        if (!"CONTINUE_LIGHT".equals(decision)) {
            stopHorse(vet, call, decision, ip);
        }
        String detail = null;
        if ("EMERGENCY_STOP".equals(decision)) {
            detail = openExamination(vet, call, instructions, ip);
        }
        users.writeAudit(vet.userId(), "VET_CALL_ANSWER:" + decision, "VET_CALL", callId, "SUCCESS", ip, detail);

        String summary = decisionLabel(decision) + ". " + instructions
                + (medication == null ? "" : " Medication: " + medication + ".");
        notifications.notifyUser(call.calledBy(), new Notice("VET_CALL_ANSWERED",
                "Veterinarian answered: " + call.horseName(), clip(summary, 500), Notice.VET_CALL, callId));
        alerts.findSessionContext(call.sessionId()).filter(s -> s.assignedTo() != call.calledBy())
                .ifPresent(s -> notifications.notifyUser(s.assignedTo(),
                new Notice("VET_CALL_ANSWERED", "Vet instructions: " + call.horseName(), clip(summary, 500),
                        Notice.TRAINING_SESSION, call.sessionId())));

        return get(callId);
    }

    /** Dừng mọi buổi đang chạy của ngựa (thường chỉ có buổi của cảnh báo). */
    private void stopHorse(AuthenticatedUser vet, VetCallResponse call, String decision, String ip) {
        for (IncidentRepository.RunningSession s : incidents.findRunningSessions(call.horseId())) {
            if (incidents.markStopped(s.sessionId()) == 0) continue; // vừa kết thúc, không cần dừng
            users.writeAudit(vet.userId(), "SESSION_STOPPED_BY_VET", "TRAINING_SESSION", s.sessionId(),
                    "SUCCESS", ip, "Vet call #" + call.callId() + ": " + decision);
        }
    }

    /**
     * Dừng khẩn cấp: mở báo cáo sự cố để ngựa vào hàng chờ khám, do chính bác sĩ này
     * nhận luôn. Ngựa đã có báo cáo chưa khám (vd Groom vừa báo) thì dùng lại báo cáo đó.
     * Báo cáo không gắn session_id: UX_INC_session cho mỗi buổi tập tối đa một báo cáo,
     * và buổi này có thể đã có báo cáo cũ.
     */
    private String openExamination(AuthenticatedUser vet, VetCallResponse call, String instructions, String ip) {
        var open = incidents.findOpenReport(call.horseId());
        int reportId;
        if (open.isPresent()) {
            reportId = open.get().reportId();
            incidents.markReceived(reportId, vet.userId()); // 0 dòng nếu đã có người nhận: giữ nguyên
        } else {
            String description = clip("From vet call #" + call.callId() + " (session #" + call.sessionId() + ")."
                    + (call.observations() == null ? "" : " Observed: " + call.observations() + ".")
                    + " Vet: " + instructions, 2000);
            reportId = incidents.insertStandaloneReport(call.horseId(), call.calledBy(),
                    clip(call.reason(), 255), description, call.urgency(), null);
            incidents.markReceived(reportId, vet.userId());
            users.writeAudit(vet.userId(), "INCIDENT_REPORT_CREATE", "INCIDENT_REPORT", reportId, "SUCCESS", ip,
                    "From vet call #" + call.callId());
        }
        calls.linkReport(call.callId(), reportId);

        HorseResponse horse = horses.findById(call.horseId()).orElseThrow();
        notifications.notifyHorseWatchers(horse.ownerId(), vet, new Notice("VET_EMERGENCY_STOP",
                "Emergency stop: " + horse.horseName(),
                clip(horse.horseName() + " was stopped at training and is being taken for a veterinary exam. "
                        + "Training is paused until the exam is done.", 500),
                Notice.HORSE, horse.horseId()));
        return "Incident report #" + reportId;
    }

    static String decisionLabel(String decision) {
        return switch (decision) {
            case "CONTINUE_LIGHT" -> "Continue at light intensity";
            case "TREAT_ON_SITE" -> "Stop and treat at the stable";
            default -> "Emergency stop: bring the horse in for an exam";
        };
    }

    private static String blankToNull(String s) {
        return s == null || s.isBlank() ? null : s.trim();
    }

    private static String clip(String s, int max) {
        if (s == null || s.length() <= max) return s;
        return s.substring(0, max - 3) + "...";
    }
}
