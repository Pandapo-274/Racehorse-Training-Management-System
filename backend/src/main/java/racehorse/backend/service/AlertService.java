package racehorse.backend.service;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import racehorse.backend.dto.AlertResponse;
import racehorse.backend.dto.ResolveAlertRequest;
import racehorse.backend.dto.SessionContext;
import racehorse.backend.dto.VitalsRequest;
import racehorse.backend.dto.VitalsResult;
import racehorse.backend.entity.Notice;
import racehorse.backend.exception.ApiException;
import racehorse.backend.repository.AlertRepository;
import racehorse.backend.repository.UserRepository;
import racehorse.backend.security.AuthenticatedUser;

/*
 * Sinh và xử lý cảnh báo vượt ngưỡng (THRESHOLD_ALERT).
 *
 * Luồng một lần đo (recordVitals):
 *   1. Kiểm Groom đúng người được giao, buổi đang RUNNING, có ít nhất một chỉ số.
 *   2. Cập nhật peak/avg nhịp tim và peak vận tốc của buổi tập.
 *   3. Nhịp tim > heart_rate_ceiling của giai đoạn  -> cảnh báo HEART_RATE.
 *      Vận tốc > velocity_ceiling của buổi (V47) hoặc app.alert.velocity-max (18 m/s) -> cảnh báo VELOCITY.
 *   4. Mức độ theo % vượt ngưỡng: >= 10% HIGH, >= 5% MEDIUM, còn lại LOW.
 *   5. Chống ngập cảnh báo: trong 60 giây, cùng buổi + cùng loại chỉ tạo cảnh báo mới khi mức độ TĂNG.
 *   6. Chỉ mức HIGH mới gửi chuông (HLV phụ trách + mọi bác sĩ).
 */
@Service
public class AlertService {

    private static final String HEART_RATE = "HEART_RATE";
    private static final String VELOCITY = "VELOCITY";
    /** Cùng một ca tập, cùng loại cảnh báo: trong 60s chỉ báo lại khi MỨC ĐỘ TĂNG, tránh cảm biến gửi mỗi giây là ngập alert. */
    private static final int DEDUPE_SECONDS = 60;

    private final AlertRepository alerts;
    private final UserRepository users;
    private final NotificationService notifications;
    private final IncidentService incidentService;
    /**
     * Ngưỡng vận tốc (km/h). Mặc định 65.0 km/h: vượt mức này trong buổi
     * tập là ngựa đang chạy gần tốc độ đua, nguy cơ chấn thương cao.
     */
    private final BigDecimal velocityMax;

    public AlertService(AlertRepository alerts, UserRepository users,
                        NotificationService notifications, IncidentService incidentService,
                        @Value("${app.alert.velocity-max:65.0}") BigDecimal velocityMax) {
        this.alerts = alerts;
        this.users = users;
        this.notifications = notifications;
        this.incidentService = incidentService;
        this.velocityMax = velocityMax;
    }

    /**
     * Nhận một lần đo, so với ngưỡng nhịp tim của giai đoạn giáo án (PLAN_PHASE.heart_rate_ceiling).
     * Vượt ngưỡng thì tạo THRESHOLD_ALERT để Head Trainer thấy.
     * Vận tốc so với ngưỡng chung velocityMax; vượt thì sinh cảnh báo VELOCITY theo cùng luật
     * mức độ và chống trùng. Một lần đo vượt cả hai thì kết quả trả về là cảnh báo nhịp tim.
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

        if (req.heartRate() == null && req.velocity() == null) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "Please provide at least heart rate or speed");
        }

        Integer hr = req.heartRate();
        if (hr != null) {
            alerts.updatePeakHeartRate(sessionId, hr);
        }
        if (req.velocity() != null) {
            alerts.updatePeakVelocity(sessionId, req.velocity());
        }

        // Ví dụ: ceiling 200 bpm, đo được 222 bpm -> vượt 11% -> HIGH.
        Integer ceiling = ctx.heartRateCeiling();
        VitalsResult heart = null;
        if (hr != null) {
            heart = (ceiling == null || hr <= ceiling)
                    ? new VitalsResult(hr, ceiling, false, null, null)
                    : raise(me, ctx.horseId(), sessionId, hr, ceiling, HEART_RATE, BigDecimal.valueOf(ceiling), BigDecimal.valueOf(hr),
                            "High heart-rate alert", hr + " bpm, ceiling is " + ceiling + " bpm");
        }
        BigDecimal speedTarget = ctx.targetVelocity() != null
                ? ctx.targetVelocity()
                : (ctx.velocityCeiling() != null ? ctx.velocityCeiling() : velocityMax);
        if (req.velocity() != null && speedTarget != null && speedTarget.doubleValue() > 0) {
            String speedSeverity = speedSeverityFor(req.velocity().doubleValue(), speedTarget.doubleValue());
            if (speedSeverity != null) {
                int effectiveHr = hr != null ? hr : (ctx.heartRateCeiling() != null ? ctx.heartRateCeiling() : 120);
                VitalsResult speed = raiseSpeed(me, ctx.horseId(), sessionId, effectiveHr, ceiling,
                        speedTarget, req.velocity(), speedSeverity);
                if (speed.sessionStoppedEarly() || heart == null || !heart.alertRaised()) {
                    return speed;
                }
            }
        }
        return heart != null ? heart : new VitalsResult(null, ceiling, false, null, null);
    }

    private VitalsResult raiseSpeed(AuthenticatedUser me, int horseId, int sessionId, int hr, Integer ceiling,
                                    BigDecimal targetSpeed, BigDecimal actualSpeed, String severity) {
        String msg = actualSpeed.toPlainString() + " km/h exceeds target speed "
                + targetSpeed.toPlainString() + " km/h (" + severity + ")";
        if (!"HIGH".equals(severity)) {
            Optional<String> open = alerts.latestOpenSeverity(sessionId, VELOCITY, DEDUPE_SECONDS);
            if (open.isPresent() && rank(open.get()) >= rank(severity)) {
                return new VitalsResult(hr, ceiling, false, severity, null, VELOCITY, msg, false);
            }
        }
        int alertId = alerts.insertAlert(sessionId, VELOCITY, targetSpeed, actualSpeed, severity);

        if ("HIGH".equals(severity)) {
            int highCount = alerts.countHighAlertsByMetric(sessionId, VELOCITY);
            if (highCount >= 6) {
                String reason = "Auto-stopped: horse exceeded allowed speed limit (>=30% over target speed) 6 times";
                boolean stopped = alerts.autoStopSessionOnSpeedLimit(sessionId, reason) > 0;
                Notice stopNotice = new Notice(
                        "SESSION_AUTO_STOPPED_SPEED",
                        "Session auto-stopped: speed limit exceeded",
                        "Session #" + sessionId + ": horse exceeded target speed ("
                                + targetSpeed.toPlainString() + " km/h) by >=30% 6 times (latest: "
                                + actualSpeed.toPlainString() + " km/h). Session was automatically stopped early"
                                + " — please open Session Review to evaluate.",
                        Notice.TRAINING_SESSION, sessionId);
                if (horseId > 0) {
                    notifications.notifyHorseTrainer(horseId, me, stopNotice);
                } else {
                    notifications.notifyRole("HEAD_TRAINER", me, stopNotice);
                }
                notifications.notifyRole("VETERINARIAN", me, stopNotice);
                return new VitalsResult(hr, ceiling, true, severity, alertId, VELOCITY,
                        "Session auto-stopped early after 6th high speed alert (" + actualSpeed.toPlainString()
                                + " km/h vs target " + targetSpeed.toPlainString() + " km/h).",
                        stopped);
            }
            Notice notice = new Notice("THRESHOLD_ALERT_HIGH",
                    "High speed alert (" + highCount + "/5)",
                    "Session #" + sessionId + ": " + actualSpeed.toPlainString() + " km/h (target "
                            + targetSpeed.toPlainString() + " km/h, >=30% over).",
                    Notice.THRESHOLD_ALERT, alertId);
            if (horseId > 0) {
                notifications.notifyHorseTrainer(horseId, me, notice);
            } else {
                notifications.notifyRole("HEAD_TRAINER", me, notice);
            }
            notifications.notifyRole("VETERINARIAN", me, notice);
            return new VitalsResult(hr, ceiling, true, severity, alertId, VELOCITY,
                    msg + " (High alert " + highCount + "/5)", false);
        }

        String noticeType = "MEDIUM".equals(severity) ? "THRESHOLD_ALERT_MEDIUM" : "THRESHOLD_ALERT_LOW";
        String title = "MEDIUM".equals(severity) ? "Medium speed alert (>=20% over target)" : "Low speed alert (>=10% over target)";
        Notice notice = new Notice(noticeType, title,
                "Session #" + sessionId + ": " + actualSpeed.toPlainString() + " km/h (target "
                        + targetSpeed.toPlainString() + " km/h).",
                Notice.THRESHOLD_ALERT, alertId);
        if (horseId > 0) {
            notifications.notifyHorseTrainer(horseId, me, notice);
        } else {
            notifications.notifyRole("HEAD_TRAINER", me, notice);
        }
        return new VitalsResult(hr, ceiling, true, severity, alertId, VELOCITY, msg, false);
    }

    /**
     * Tạo một cảnh báo vượt ngưỡng (nhịp tim hoặc vận tốc), cùng một luật cho cả hai:
     * mức độ theo % vượt, trong DEDUPE_SECONDS chỉ báo lại khi mức độ tăng.
     */
    private VitalsResult raise(AuthenticatedUser me, int horseId, int sessionId, int hr, Integer ceiling, String metric,
                               BigDecimal limit, BigDecimal actual, String title, String detail) {
        String severity = severityFor(actual.doubleValue(), limit.doubleValue());
        // open = mức độ của cảnh báo chưa xử lý gần nhất cùng loại trong 60 giây qua (nếu có).
        Optional<String> open = alerts.latestOpenSeverity(sessionId, metric, DEDUPE_SECONDS);
        String msg = "VELOCITY".equals(metric)
                ? (actual.toPlainString() + " km/h is over the " + limit.toPlainString() + " km/h limit")
                : (actual.intValue() + " bpm is over the " + limit.intValue() + " bpm ceiling");
        if (open.isPresent() && rank(open.get()) >= rank(severity)) {
            return new VitalsResult(hr, ceiling, false, severity, null, metric, msg);
        }
        int alertId = alerts.insertAlert(sessionId, metric, limit, actual, severity);

        /* Chỉ mức HIGH mới lên chuông. LOW / MEDIUM đã hiện trên màn Live
           Session của Head Trainer; đưa cả chúng vào chuông thì một buổi tập
           nặng sinh hàng chục thông báo và cái quan trọng bị chìm. Bác sĩ
           cũng nhận, vì vượt ngưỡng 10% là lúc có thể cần khám ngay. */
        if ("HIGH".equals(severity)) {
            Notice notice = new Notice("THRESHOLD_ALERT_HIGH", title,
                    "Session #" + sessionId + ": " + detail + ".", Notice.THRESHOLD_ALERT, alertId);
            if (horseId > 0) {
                notifications.notifyHorseTrainer(horseId, me, notice);
            } else {
                notifications.notifyRole("HEAD_TRAINER", me, notice);
            }
            notifications.notifyRole("VETERINARIAN", me, notice);
        }
        return new VitalsResult(hr, ceiling, true, severity, alertId, metric, msg);
    }

    public List<AlertResponse> list(Integer afterId, boolean openOnly, int limit) {
        return list(null, afterId, openOnly, limit);
    }

    /** A Head Trainer only sees alerts of horses assigned to them. */
    public List<AlertResponse> list(AuthenticatedUser me, Integer afterId, boolean openOnly, int limit) {
        Integer trainerId = (me != null && "HEAD_TRAINER".equals(me.role())) ? me.userId() : null;
        return alerts.search(afterId, openOnly, Math.max(1, Math.min(limit, 200)), trainerId);
    }

    /** Head Trainer ghi lại đã xử lý thế nào. STOP_SESSION thì ca tập bị dừng luôn. */
    @Transactional
    public AlertResponse resolve(AuthenticatedUser me, int alertId, ResolveAlertRequest req, String ip) {
        // STOP_SESSION: dừng buổi tập và mở báo cáo sự cố (IncidentService.stopFromAlert).
        // REDUCE_INTENSITY: báo Groom đang dắt ngựa. CONTINUE: chỉ ghi lại, không báo ai.
        /* Gọi bác sĩ phải kèm lý do để bác sĩ trả lời được: đi qua
           POST /api/alerts/{id}/vet-call (VetCallService), không qua đây. */
        if ("VET_CALLED".equals(req.actionTaken())) {
            throw new ApiException(HttpStatus.BAD_REQUEST,
                    "Use the vet call form (POST /api/alerts/" + alertId + "/vet-call) to call a veterinarian");
        }
        AlertResponse alert = alerts.findById(alertId)
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "Alert not found"));
        if (!alerts.resolve(alertId, req.actionTaken(), me.userId())) {
            throw new ApiException(HttpStatus.CONFLICT, "Alert has already been resolved");
        }
        if ("STOP_SESSION".equals(req.actionTaken())) {
            /* Dừng như Groom bấm Stop: mở báo cáo sự cố để bác sĩ khám, và báo
               Groom, chủ ngựa, bác sĩ. IncidentService lo cả phần thông báo. */
            String what = "Stopped by the head trainer after a " + metricLabel(alert.metricType()) + " alert: "
                    + alertDetail(alert);
            incidentService.stopFromAlert(me, alert.sessionId(), what, alert.severity(), ip);
        }
        /* Giảm cường độ là một chỉ thị cho người đang dắt ngựa ngoài sân. Chỉ ghi
           action_taken thì Groom không hề biết và ngựa vẫn chạy như cũ, nên báo thẳng
           cho Groom của buổi đó - khi buổi còn đang chạy. CONTINUE không đổi gì với
           Groom nên không báo. */
        if ("REDUCE_INTENSITY".equals(req.actionTaken())) {
            alerts.findSessionContext(alert.sessionId())
                    .filter(ctx -> "RUNNING".equals(ctx.status()))
                    .ifPresent(ctx -> notifications.notifyUser(ctx.assignedTo(), new Notice(
                            "TRAINING_INTENSITY_REDUCED", "Reduce intensity now",
                            alert.horseName() + ": the head trainer asks you to reduce intensity after a "
                                    + metricLabel(alert.metricType()) + " alert (" + alertDetail(alert) + ").",
                            Notice.TRAINING_SESSION, alert.sessionId())));
        }
        users.writeAudit(me.userId(), "RESOLVE_ALERT:" + req.actionTaken(), "THRESHOLD_ALERT", alertId, "SUCCESS", ip);
        return alerts.findById(alertId).orElseThrow();
    }

    /** Nhịp tim: vượt >=10% ngưỡng: HIGH; >=5%: MEDIUM; còn lại: LOW. */
    private static String severityFor(double value, double ceiling) {
        double over = (double) (value - ceiling) / ceiling;
        if (over >= 0.10) return "HIGH";
        if (over >= 0.05) return "MEDIUM";
        return "LOW";
    }

    /** Vận tốc so với target speed: >=30% HIGH, >=20% MEDIUM, >=10% LOW, dưới 10% an toàn (null). */
    private static String speedSeverityFor(double value, double target) {
        if (target <= 0) return null;
        double over = (value - target) / target;
        if (over >= 0.30) return "HIGH";
        if (over >= 0.20) return "MEDIUM";
        if (over >= 0.10) return "LOW";
        return null;
    }

    private static String metricLabel(String metric) {
        return VELOCITY.equals(metric) ? "speed" : "heart-rate";
    }

    /** "232 bpm (ceiling 200 bpm)" hoặc "70.2 km/h (limit 65.0 km/h)". */
    private static String alertDetail(AlertResponse a) {
        if (a.actualValue() == null || a.thresholdValue() == null) return "value over the limit";
        return VELOCITY.equals(a.metricType())
                ? a.actualValue().toPlainString() + " km/h (limit " + a.thresholdValue().toPlainString() + " km/h)"
                : a.actualValue().intValue() + " bpm (ceiling " + a.thresholdValue().intValue() + " bpm)";
    }

    private static int rank(String severity) {
        return switch (severity) {
            case "HIGH" -> 3;
            case "MEDIUM" -> 2;
            default -> 1;
        };
    }
}
