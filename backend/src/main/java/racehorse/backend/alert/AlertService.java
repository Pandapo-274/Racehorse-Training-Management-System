package racehorse.backend.alert;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import racehorse.backend.auth.ApiException;
import racehorse.backend.auth.AuthenticatedUser;
import racehorse.backend.auth.UserRepository;
import racehorse.backend.notification.Notice;
import racehorse.backend.notification.NotificationService;

@Service
public class AlertService {

    private static final String HEART_RATE = "HEART_RATE";
    /** Cùng một ca tập, cùng loại cảnh báo: trong 60s chỉ báo lại khi MỨC ĐỘ TĂNG, tránh cảm biến gửi mỗi giây là ngập alert. */
    private static final int DEDUPE_SECONDS = 60;

    private final AlertRepository alerts;
    private final UserRepository users;
    private final NotificationService notifications;

    public AlertService(AlertRepository alerts, UserRepository users,
                        NotificationService notifications) {
        this.alerts = alerts;
        this.users = users;
        this.notifications = notifications;
    }

    /**
     * Nhận một lần đo, so với ngưỡng nhịp tim của giai đoạn giáo án (PLAN_PHASE.heart_rate_ceiling).
     * Vượt ngưỡng thì tạo THRESHOLD_ALERT để Head Trainer thấy.
     * Chưa có ngưỡng vận tốc trong schema nên vận tốc hiện chỉ được ghi nhận đỉnh, chưa sinh cảnh báo.
     */
    @Transactional
    public VitalsResult recordVitals(AuthenticatedUser me, int sessionId, VitalsRequest req) {
        SessionContext ctx = alerts.findSessionContext(sessionId)
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "Training session not found"));

        // Groom chỉ được gửi số đo cho ca tập mình phụ trách; Head Trainer thì gửi được mọi ca.
        if ("GROOM".equals(me.role()) && ctx.assignedTo() != me.userId()) {
            throw new ApiException(HttpStatus.FORBIDDEN, "You are not assigned to this session");
        }
        if (!"RUNNING".equals(ctx.status())) {
            throw new ApiException(HttpStatus.CONFLICT, "Session is not running");
        }

        int hr = req.heartRate();
        alerts.updatePeakHeartRate(sessionId, hr);
        if (req.velocity() != null) {
            alerts.updatePeakVelocity(sessionId, req.velocity());
        }

        Integer ceiling = ctx.heartRateCeiling();
        if (ceiling == null || hr <= ceiling) {
            return new VitalsResult(hr, ceiling, false, null, null);
        }

        String severity = severityFor(hr, ceiling);
        Optional<String> open = alerts.latestOpenSeverity(sessionId, HEART_RATE, DEDUPE_SECONDS);
        if (open.isPresent() && rank(open.get()) >= rank(severity)) {
            return new VitalsResult(hr, ceiling, false, severity, null);
        }
        int alertId = alerts.insertAlert(sessionId, HEART_RATE,
                BigDecimal.valueOf(ceiling), BigDecimal.valueOf(hr), severity);

        /* Chỉ mức HIGH mới lên chuông. LOW / MEDIUM đã hiện trên màn Live
           Session của Head Trainer; đưa cả chúng vào chuông thì một buổi tập
           nặng sinh hàng chục thông báo và cái quan trọng bị chìm. Bác sĩ
           cũng nhận, vì vượt ngưỡng 10% là lúc có thể cần khám ngay. */
        if ("HIGH".equals(severity)) {
            Notice notice = new Notice(
                    "THRESHOLD_ALERT_HIGH",
                    "High heart-rate alert",
                    "Session #" + sessionId + ": " + hr + " bpm, ceiling is " + ceiling + " bpm.",
                    Notice.THRESHOLD_ALERT, alertId);
            notifications.notifyRole("HEAD_TRAINER", me, notice);
            notifications.notifyRole("VETERINARIAN", me, notice);
        }
        return new VitalsResult(hr, ceiling, true, severity, alertId);
    }

    public List<AlertResponse> list(Integer afterId, boolean openOnly, int limit) {
        return alerts.search(afterId, openOnly, Math.max(1, Math.min(limit, 200)));
    }

    /** Head Trainer ghi lại đã xử lý thế nào. STOP_SESSION thì ca tập bị dừng luôn. */
    @Transactional
    public AlertResponse resolve(AuthenticatedUser me, int alertId, ResolveAlertRequest req, String ip) {
        AlertResponse alert = alerts.findById(alertId)
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "Alert not found"));
        if (!alerts.resolve(alertId, req.actionTaken(), me.userId())) {
            throw new ApiException(HttpStatus.CONFLICT, "Alert has already been resolved");
        }
        if ("STOP_SESSION".equals(req.actionTaken())) {
            alerts.stopSession(alert.sessionId());
        }
        users.writeAudit(me.userId(), "RESOLVE_ALERT:" + req.actionTaken(), "THRESHOLD_ALERT", alertId, "SUCCESS", ip);
        return alerts.findById(alertId).orElseThrow();
    }

    /** Vượt >=10% ngưỡng: HIGH; >=5%: MEDIUM; còn lại: LOW. */
    private static String severityFor(int value, int ceiling) {
        double over = (double) (value - ceiling) / ceiling;
        if (over >= 0.10) return "HIGH";
        if (over >= 0.05) return "MEDIUM";
        return "LOW";
    }

    private static int rank(String severity) {
        return switch (severity) {
            case "HIGH" -> 3;
            case "MEDIUM" -> 2;
            default -> 1;
        };
    }
}
