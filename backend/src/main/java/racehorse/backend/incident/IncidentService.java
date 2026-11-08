package racehorse.backend.incident;

import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import racehorse.backend.auth.ApiException;
import racehorse.backend.auth.AuthenticatedUser;
import racehorse.backend.auth.UserRecord;
import racehorse.backend.auth.UserRepository;
import racehorse.backend.notification.Notice;
import racehorse.backend.notification.NotificationService;
import racehorse.backend.race.RaceRiskNotifier;
import racehorse.backend.vetcall.VetCallRepository;

@Service
public class IncidentService {

    private static final Set<String> STATUS_FILTERS =
            Set.of("OPEN", "PENDING", "RECEIVED", "EXAMINED", "DISMISSED", "ALL");

    private final IncidentRepository incidents;
    private final UserRepository users;
    private final NotificationService notifications;
    private final RaceRiskNotifier raceRisk;
    private final VetCallRepository vetCalls;

    public IncidentService(IncidentRepository incidents, UserRepository users, NotificationService notifications,
                           RaceRiskNotifier raceRisk) {
        this(incidents, users, notifications, raceRisk, null);
    }

    @Autowired
    public IncidentService(IncidentRepository incidents, UserRepository users, NotificationService notifications,
                           RaceRiskNotifier raceRisk, VetCallRepository vetCalls) {
        this.incidents = incidents;
        this.users = users;
        this.notifications = notifications;
        this.raceRisk = raceRisk;
        this.vetCalls = vetCalls;
    }

    /**
     * Dừng một buổi đang chạy vì ngựa bất thường: đổi trạng thái buổi tập, mở báo cáo sự cố và báo người liên quan.
     *
     * V37: Nếu Groom chủ động dừng sớm buổi tập (kể cả khi chấn thương và gọi Vet), bắt buộc Groom phải
     * báo cáo cả 2 chỉ số Heart Rate và Speed ít nhất 1 lần trong buổi tập đó.
     */
    @Transactional
    public StopSessionResponse stopSession(AuthenticatedUser user, int sessionId, StopSessionRequest req, String ip) {
        if (user != null && !"GROOM".equals(user.role()) && !"HEAD_TRAINER".equals(user.role())) {
            throw new ApiException(HttpStatus.FORBIDDEN, "Only Groom or Head Trainer can stop a training session");
        }
        IncidentRepository.SessionForStop s = incidents.findSessionForStop(sessionId)
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "Training session not found"));

        if ("GROOM".equals(user.role()) && s.assignedTo() != user.userId()) {
            throw new ApiException(HttpStatus.FORBIDDEN, "This session is not assigned to you");
        }
        if (!"RUNNING".equals(s.status())) {
            throw new ApiException(HttpStatus.CONFLICT,
                    "Only a RUNNING session can be stopped (currently " + s.status() + ")");
        }

        // V37: Groom chỉ được kết thúc sớm khi đã báo cáo cả 2 chỉ số heart rate và speed ít nhất 1 lần
        if ("GROOM".equals(user.role()) && (s.peakHeartRate() == null || s.peakVelocity() == null)) {
            throw new ApiException(HttpStatus.CONFLICT,
                    "You must report both heart rate and speed at least once during the session before ending it early");
        }

        String symptoms = req.symptoms().trim();
        String description = (req.description() == null || req.description().isBlank()) ? null : req.description().trim();

        String earlyReason = Text.clip("Horse injury / Vet called: " + symptoms, 255);
        if (incidents.markStopped(sessionId, earlyReason) == 0) {
            throw new ApiException(HttpStatus.CONFLICT, "This session has just been stopped or finished by someone else");
        }
        int reportId = openStopReport(user, s, symptoms, description, req.urgency(), ip);

        IncidentReportResponse report = incidents.findById(reportId)
                .orElseThrow(() -> new ApiException(HttpStatus.INTERNAL_SERVER_ERROR,
                        "The report was created but could not be read back"));
        return new StopSessionResponse(sessionId, "STOPPED_EARLY", report);
    }

    @Transactional
    public Integer stopFromAlert(AuthenticatedUser trainer, int sessionId, String symptoms, String urgency,
                                 String ip) {
        IncidentRepository.SessionForStop s = incidents.findSessionForStop(sessionId).orElse(null);
        String reason = Text.clip("Threshold alert: " + symptoms, 255);
        if (s == null || !"RUNNING".equals(s.status()) || incidents.markStopped(sessionId, reason) == 0) {
            return null;
        }
        return openStopReport(trainer, s, Text.clip(symptoms, 255), null, urgency, ip);
    }

    private int openStopReport(AuthenticatedUser user, IncidentRepository.SessionForStop s, String symptoms,
                               String description, String urgency, String ip) {
        int sessionId = s.sessionId();
        int reportId = incidents.insertReport(s.horseId(), user.userId(), sessionId, symptoms, description, urgency);
        users.writeAudit(user == null ? null : user.userId(), "SESSION_STOP_INCIDENT", "INCIDENT_REPORT", reportId, "SUCCESS", ip);

        String shortWhat = s.horseName() + ": " + symptoms + " (" + urgency + ").";
        notifications.notifyRole("VETERINARIAN", user, new Notice(
                "INCIDENT_REPORTED", "Horse needs examination", Text.clip(shortWhat, 500),
                Notice.HORSE, s.horseId()));
        /* The stop is also a vet call, so it lands in the veterinarian's "Vet calls" queue. */
        if (vetCalls != null) {
            int callId = vetCalls.insert(null, sessionId, s.horseId(), user.userId(),
                    Text.clip(symptoms, 500), description == null ? null : Text.clip(description, 1000),
                    urgency, true, reportId);
            notifications.notifyRole("VETERINARIAN", user, new Notice(
                    "VET_CALL_REQUESTED", "Veterinarian needed at training",
                    Text.clip(s.horseName() + " (" + urgency + "): " + symptoms, 500),
                    Notice.VET_CALL, callId));
        }
        notifications.notifyHorseWatchers(s.ownerId(), user, new Notice(
                "TRAINING_SESSION_STOPPED", "Training session stopped",
                Text.clip(s.horseName() + " was stopped during training: " + symptoms + ".", 500),
                Notice.TRAINING_SESSION, sessionId));
        if (s.assignedTo() != user.userId()) {
            notifications.notifyUser(s.assignedTo(), new Notice(
                    "TRAINING_SESSION_STOPPED", "Training session stopped",
                    Text.clip("Your session with " + s.horseName() + " was stopped: " + symptoms
                            + ". Stop work with this horse now.", 500),
                    Notice.TRAINING_SESSION, sessionId));
        }

        raceRisk.warn(s.horseId(), s.horseName(), s.ownerId(), user,
                "was stopped during training and is waiting for a veterinarian");
        return reportId;
    }

    public List<IncidentReportResponse> list(String status, Integer horseId, int limit, AuthenticatedUser user) {
        String f = (status == null || status.isBlank()) ? "OPEN" : status.trim().toUpperCase(Locale.ROOT);
        if (!STATUS_FILTERS.contains(f)) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "Invalid status filter",
                    Map.of("status", "Must be one of OPEN, PENDING, RECEIVED, EXAMINED, DISMISSED, ALL"));
        }
        List<String> statuses = switch (f) {
            case "OPEN" -> List.of("PENDING", "RECEIVED");
            case "ALL" -> List.of();
            default -> List.of(f);
        };
        Integer reporterId = "GROOM".equals(user.role()) ? user.userId() : null;
        Integer ownerId = "HORSE_OWNER".equals(user.role()) ? user.userId() : null;
        boolean urgentFirst = !"EXAMINED".equals(f) && !"DISMISSED".equals(f) && !"ALL".equals(f);
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

    @Transactional
    public IncidentReportResponse dismiss(AuthenticatedUser trainer, int reportId, String reason, String ip) {
        IncidentReportResponse r = incidents.findById(reportId)
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "Incident report not found"));
        if (r.sessionId() == null) {
            throw new ApiException(HttpStatus.CONFLICT,
                    "Only a report from a stopped training session can be dismissed by the head trainer");
        }
        if (!"PENDING".equals(r.status())) {
            throw new ApiException(HttpStatus.CONFLICT, "RECEIVED".equals(r.status())
                    ? "A veterinarian has already taken this case; only the vet can close it"
                    : "This report is already closed (" + r.status() + ")");
        }
        String why = reason.trim();
        if (incidents.markDismissed(reportId, trainer.userId(), why) == 0) {
            throw new ApiException(HttpStatus.CONFLICT, "A veterinarian has just taken this case");
        }
        users.writeAudit(trainer.userId(), "INCIDENT_DISMISS", "INCIDENT_REPORT", reportId, "SUCCESS", ip,
                Text.clip(why, 200));

        Notice notice = new Notice("INCIDENT_DISMISSED", "Incident dismissed as a false alarm",
                Text.clip(r.horseName() + ": the head trainer dismissed the report (" + r.symptoms() + "). Reason: "
                        + why, 500),
                Notice.HORSE, r.horseId());
        notifications.notifyRole("VETERINARIAN", trainer, notice);
        notifications.notifyHorseWatchers(r.ownerId(), trainer, notice);
        if (r.reporterId() != trainer.userId()) {
            notifications.notifyUser(r.reporterId(), notice);
        }
        return incidents.findById(reportId)
                .orElseThrow(() -> new ApiException(HttpStatus.INTERNAL_SERVER_ERROR,
                        "The report was updated but could not be read back"));
    }

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
