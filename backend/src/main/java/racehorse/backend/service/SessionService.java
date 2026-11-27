package racehorse.backend.service;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import racehorse.backend.dto.EvaluateSessionRequest;
import racehorse.backend.dto.SessionResponse;
import racehorse.backend.entity.Notice;
import racehorse.backend.exception.ApiException;
import racehorse.backend.repository.HorseRepository;
import racehorse.backend.repository.SessionRepository;
import racehorse.backend.repository.UserRepository;
import racehorse.backend.security.AuthenticatedUser;

/*
 * Nhận xét buổi tập (UC12) và hạn nhận xét 48 giờ (V37).
 *
 * review_status của một buổi: NONE (chưa kết thúc) -> PENDING (đã kết thúc, chờ HLV) ->
 *   REVIEWED   khi HLV gửi đánh giá (evaluate);
 *   ARCHIVED_AUTO khi quá 48 giờ mà HLV chưa đánh giá: hệ thống tự viết "báo cáo sơ bộ" từ số liệu
 *              Groom đã gửi, báo chủ ngựa (ghi rõ KHÔNG phải nhận xét chính thức) và báo HLV.
 *              HLV vẫn đánh giá lại được sau đó (lúc đó review_status thành REVIEWED).
 * processOverdueReviews được gọi mỗi lần đọc danh sách / chi tiết và mỗi phút bởi
 * MissedTaskJob.runPeriodicTrainingChecks.
 */
@Service
public class SessionService {

    private static final Set<String> STATUSES =
            Set.of("SCHEDULED", "RUNNING", "COMPLETED", "STOPPED_EARLY", "CANCELLED");

    private static final Set<String> EVALUATABLE = Set.of("COMPLETED", "STOPPED_EARLY");

    /** V37: Hạn tối đa 2 ngày (48 giờ) kể từ lúc buổi tập kết thúc để Head Trainer gửi review. */
    public static final int REVIEW_DEADLINE_HOURS = 48;

    private final SessionRepository sessions;
    private final UserRepository users;
    private final NotificationService notifications;
    private final TrainingSessionService trainingSessionService;
    private final HorseRepository horseRepository;

    /** Constructor cho unit test sẵn có. */
    // Ba constructor (overload) để các unit test cũ vẫn dựng được service với ít phụ thuộc hơn;
    // các phụ thuộc thiếu là null và code kiểm "!= null" trước khi dùng.
    public SessionService(SessionRepository sessions, UserRepository users) {
        this(sessions, users, null, null, null);
    }

    public SessionService(SessionRepository sessions, UserRepository users,
                          NotificationService notifications,
                          TrainingSessionService trainingSessionService) {
        this(sessions, users, notifications, trainingSessionService, null);
    }

    @Autowired
    public SessionService(SessionRepository sessions, UserRepository users,
                          NotificationService notifications,
                          TrainingSessionService trainingSessionService,
                          HorseRepository horseRepository) {
        this.sessions = sessions;
        this.users = users;
        this.notifications = notifications;
        this.trainingSessionService = trainingSessionService;
        this.horseRepository = horseRepository;
    }

    public List<SessionResponse> list(String status, Integer horseId, int limit, AuthenticatedUser user) {
        // Dọn buổi tập quá giờ và nhận xét quá hạn trước khi trả danh sách.
        if (trainingSessionService != null) {
            trainingSessionService.processOverdueSessions();
        }
        processOverdueReviews();

        String st = (status == null || status.isBlank()) ? null : status.trim().toUpperCase();
        if (st != null && !STATUSES.contains(st)) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "Invalid status filter",
                    Map.of("status", "Must be one of " + STATUSES));
        }
        Integer ownerId = "HORSE_OWNER".equals(user.role()) ? user.userId() : null;
        List<SessionResponse> results = sessions.search(st, horseId, Math.max(1, Math.min(limit, 200)), ownerId);
        if ("HEAD_TRAINER".equals(user.role()) && horseRepository != null) {
            Set<Integer> mine = horseRepository.findHorseIdsByTrainer(user.userId());
            return results.stream().filter(s -> mine.contains(s.horseId())).toList();
        }
        return results;
    }

    public SessionResponse get(int sessionId, AuthenticatedUser user) {
        if (trainingSessionService != null) {
            trainingSessionService.processOverdueSessions();
        }
        processOverdueReviews();

        SessionResponse s = sessions.findById(sessionId)
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "Training session not found"));
        if ("HORSE_OWNER".equals(user.role())) {
            int ownerId = sessions.findOwnerOfSession(sessionId)
                    .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "Horse of this session not found"));
            if (ownerId != user.userId()) {
                throw new ApiException(HttpStatus.FORBIDDEN, "This session belongs to another owner's horse");
            }
        } else if ("HEAD_TRAINER".equals(user.role()) && horseRepository != null) {
            Set<Integer> mine = horseRepository.findHorseIdsByTrainer(user.userId());
            if (!mine.contains(s.horseId())) {
                throw new ApiException(HttpStatus.FORBIDDEN, "This session belongs to a horse not assigned to you");
            }
        }
        return s;
    }

    private SessionResponse getOrThrow(int sessionId) {
        return sessions.findById(sessionId)
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "Training session not found"));
    }

    /**
     * Head Trainer gửi đánh giá chính thức (hoặc đánh giá lại từ khu lưu trữ ARCHIVED_AUTO).
     * Khi đánh giá lại từ khu lưu trữ, gửi thông báo review chính thức tới chủ ngựa.
     */
    @Transactional
    public SessionResponse evaluate(AuthenticatedUser me, int sessionId, EvaluateSessionRequest req, String ip) {
        if (me != null && !"HEAD_TRAINER".equals(me.role())) {
            throw new ApiException(HttpStatus.FORBIDDEN, "Only Head Trainer can evaluate training sessions");
        }
        SessionResponse current = getOrThrow(sessionId);
        if (me != null && "HEAD_TRAINER".equals(me.role()) && horseRepository != null) {
            Set<Integer> mine = horseRepository.findHorseIdsByTrainer(me.userId());
            if (!mine.contains(current.horseId())) {
                throw new ApiException(HttpStatus.FORBIDDEN, "This session belongs to a horse not assigned to you");
            }
        }

        // Chỉ chấm được buổi đã kết thúc (COMPLETED / STOPPED_EARLY).
        if (!EVALUATABLE.contains(current.status())) {
            String message = switch (current.status()) {
                case "CANCELLED" -> "This session was cancelled, it cannot be evaluated";
                case "RUNNING" -> "Session is still running; finish or stop it before reviewing";
                default -> "Session has not started yet, it cannot be evaluated";
            };
            throw new ApiException(HttpStatus.CONFLICT, message);
        }
        // Kiểm logic số liệu: đỉnh >= trung bình, nhịp tim hồi phục <= đỉnh, link video phải là http(s).
        if (req.peakHeartRate() != null && req.avgHeartRate() != null && req.peakHeartRate() < req.avgHeartRate()) {
            throw fieldError("peakHeartRate", "Peak heart rate cannot be lower than average heart rate");
        }
        if (req.recoveryHeartRate() != null && req.peakHeartRate() != null && req.recoveryHeartRate() > req.peakHeartRate()) {
            throw fieldError("recoveryHeartRate", "Recovery heart rate cannot be higher than peak heart rate");
        }
        if (Boolean.TRUE.equals(req.trialRun()) && req.videoUrl() != null && !req.videoUrl().isBlank()) {
            String url = req.videoUrl().trim();
            if (!url.startsWith("http://") && !url.startsWith("https://")) {
                throw fieldError("videoUrl", "Video URL must start with http:// or https://");
            }
        }

        if ("STOPPED_EARLY".equals(current.status()) && "COMPLETED".equals(req.status())) {
            throw fieldError("status", "This session was stopped early; it cannot be marked as completed");
        }
        String finalStatus = "STOPPED_EARLY".equals(current.status()) ? "STOPPED_EARLY" : "COMPLETED";
        String autoEval = computeAutoEvaluation(current, req);
        EvaluateSessionRequest effectiveReq = new EvaluateSessionRequest(
                req.actualDistance(), req.durationMinutes(), req.avgHeartRate(),
                req.peakHeartRate(), req.recoveryHeartRate(), req.peakVelocity(),
                autoEval, req.remarks(), finalStatus, req.trialRun(), null);

        boolean wasArchivedAuto = "ARCHIVED_AUTO".equals(current.reviewStatus());

        // saveEvaluation có điều kiện "AND status = <trạng thái vừa đọc>": nếu buổi tập vừa đổi trạng
        // thái (vd. đang bị dừng) trong lúc HLV điền form thì trả 0 -> 409.
        if (sessions.saveEvaluation(sessionId, effectiveReq, current.status(), finalStatus, me == null ? 0 : me.userId()) == 0) {
            throw new ApiException(HttpStatus.CONFLICT,
                    "This session changed while you were evaluating it. Reload and try again");
        }
        users.writeAudit(me == null ? null : me.userId(),
                wasArchivedAuto ? "EVALUATE_ARCHIVED_SESSION" : "EVALUATE_SESSION",
                "TRAINING_SESSION", sessionId, "SUCCESS", ip);

        SessionResponse updated = getOrThrow(sessionId);
        if (notifications != null) {
            sessions.findOwnerOfSession(sessionId).ifPresent(ownerId -> {
                String trialPrefix = Boolean.TRUE.equals(req.trialRun()) ? "[Trial Run] " : "";
                String title = trialPrefix + (wasArchivedAuto
                        ? "Official Head Trainer review (updated from archive)"
                        : "Official Head Trainer session review");
                String body = (Boolean.TRUE.equals(req.trialRun())
                        ? "This session was marked as a Trial Run. "
                        : "")
                        + "Head Trainer completed the official review for " + updated.horseName()
                        + "'s training session (Rating: " + updated.evaluation()
                        + ", Peak HR: " + updated.peakHeartRate() + " bpm).";
                notifications.notifyUser(ownerId, new Notice(
                        "TRAINING_SESSION_REVIEWED",
                        title,
                        body,
                        Notice.TRAINING_SESSION, sessionId));
            });
        }
        return updated;
    }

    /**
     * Tự động chấm điểm Overall rating dựa trên % đạt được so với tiêu chí của Plan:
     * <80% -> POOR, >=80% -> FAIR, >=90% -> GOOD, ==100% -> EXCELLENT.
     */
    public static String computeAutoEvaluation(SessionResponse current, EvaluateSessionRequest req) {
        double sum = 0.0;
        int count = 0;

        if (current.plannedDistance() != null && current.plannedDistance() > 0 && req.actualDistance() != null) {
            sum += Math.min(100.0, (req.actualDistance() * 100.0) / current.plannedDistance());
            count++;
        }
        if (current.plannedDuration() != null && current.plannedDuration() > 0 && req.durationMinutes() != null) {
            sum += Math.min(100.0, (req.durationMinutes() * 100.0) / current.plannedDuration());
            count++;
        }
        if (current.heartRateCeiling() != null && current.heartRateCeiling() > 0 && req.peakHeartRate() != null) {
            double hrScore = req.peakHeartRate() <= current.heartRateCeiling()
                    ? 100.0
                    : Math.max(0.0, 100.0 - ((req.peakHeartRate() - current.heartRateCeiling()) * 100.0 / current.heartRateCeiling()));
            sum += hrScore;
            count++;
        }
        if (current.targetVelocity() != null && current.targetVelocity().doubleValue() > 0 && req.peakVelocity() != null) {
            double target = current.targetVelocity().doubleValue();
            double actual = req.peakVelocity().doubleValue();
            double speedScore;
            if (actual > target * 1.10) {
                speedScore = Math.max(0.0, 100.0 - ((actual - target) * 100.0 / target));
            } else if (actual < target * 0.85) {
                speedScore = Math.max(0.0, (actual * 100.0) / target);
            } else {
                speedScore = 100.0;
            }
            sum += speedScore;
            count++;
        }
        Integer recHrr = req.recoveryHeartRate() != null ? req.recoveryHeartRate() : current.actualHrr();
        if (current.targetHrr() != null && current.targetHrr() > 0 && recHrr != null) {
            double hrrScore = recHrr <= current.targetHrr()
                    ? 100.0
                    : Math.max(0.0, 100.0 - ((recHrr - current.targetHrr()) * 100.0 / current.targetHrr()));
            sum += hrrScore;
            count++;
        }

        if (count == 0) {
            return req.evaluation();
        }
        long pct = Math.round(sum / count);
        if (pct >= 100) return "EXCELLENT";
        if (pct >= 90) return "GOOD";
        if (pct >= 80) return "FAIR";
        return "POOR";
    }

    /**
     * Head Trainer gửi dữ liệu buổi tập hồi phục sang cho Bác sĩ thú y để chấm điểm phục hồi (Rating).
     */
    @Transactional
    public SessionResponse submitToVet(AuthenticatedUser me, int sessionId, String ip) {
        if (me != null && !"HEAD_TRAINER".equals(me.role())) {
            throw new ApiException(HttpStatus.FORBIDDEN, "Only Head Trainer can submit session data to vet");
        }
        SessionResponse current = getOrThrow(sessionId);
        if (me != null && "HEAD_TRAINER".equals(me.role()) && horseRepository != null) {
            Set<Integer> mine = horseRepository.findHorseIdsByTrainer(me.userId());
            if (!mine.contains(current.horseId())) {
                throw new ApiException(HttpStatus.FORBIDDEN, "This session belongs to a horse not assigned to you");
            }
        }
        // Không có cột riêng: đánh dấu bằng cách gắn chữ "[SUBMITTED_TO_VET]" vào remarks
        // (frontend lọc chữ này khi hiển thị nhận xét).
        sessions.markSubmittedToVet(sessionId);

        users.writeAudit(me == null ? null : me.userId(),
                "RECOVERY_SESSION_SUBMIT_TO_VET", "TRAINING_SESSION", sessionId, "SUCCESS", ip);

        String distStr = current.actualDistance() != null ? current.actualDistance() + "m" : "N/A";
        String hrStr = current.avgHeartRate() != null ? current.avgHeartRate() + " bpm" : (current.peakHeartRate() != null ? current.peakHeartRate() + " bpm" : "N/A");
        String recoveryHrStr = current.recoveryHeartRate() != null ? current.recoveryHeartRate() + " bpm" : "N/A";

        if (notifications != null) {
            notifications.notifyRole("VETERINARIAN", me, new Notice(
                    "RECOVERY_SESSION_SUBMITTED",
                    "Recovery session vitals submitted",
                    current.horseName() + " has completed a recovery session #" + sessionId + " (Distance: " + distStr + ", Avg HR: " + hrStr + ", Recovery HR: " + recoveryHrStr + "). Data is ready for Vet rating.",
                    Notice.HORSE, current.horseId()));
        }
        return getOrThrow(sessionId);
    }

    /**
     * V37: Kiểm tra các buổi tập đã kết thúc quá 2 ngày (48 tiếng) mà Head Trainer chưa gửi review:
     *  1. Tự động tổng hợp bản báo cáo sơ bộ về chỉ số của ngựa do Groom báo cáo kèm cảnh báo
     *     đây là review tự động, không phải review từ Head Trainer.
     *  2. Gửi thông báo báo cáo sơ bộ tới Horse Owner.
     *  3. Chuyển session review vào khu lưu trữ (ARCHIVED_AUTO) chờ Head Trainer xem xét lại.
     */
    @Transactional
    public int processOverdueReviews() {
        // Lưu ý múi giờ: cutoff tính bằng LocalDateTime.now() (giờ máy chủ, vd. UTC+7) trong khi
        // ended_at được ghi bằng SYSUTCDATETIME() (UTC). Trên máy giờ Việt Nam, buổi tập sẽ bị lưu
        // trữ tự động sớm hơn khoảng 7 giờ so với mốc 48 giờ danh nghĩa.
        LocalDateTime cutoff = LocalDateTime.now().minusHours(REVIEW_DEADLINE_HOURS);
        List<SessionResponse> overdue = sessions.findOverduePendingReviews(cutoff);
        if (overdue == null || overdue.isEmpty()) {
            return 0;
        }
        int processed = 0;
        for (SessionResponse s : overdue) {
            // Thiếu số liệu thì điền giá trị dự phòng (cự ly dự kiến hoặc 1000 m, thời lượng dự kiến
            // hoặc 60 phút, đánh giá FAIR) chỉ để báo cáo sơ bộ có đủ trường.
            int actualDist = s.actualDistance() != null ? s.actualDistance()
                    : (s.plannedDistance() != null && s.plannedDistance() > 0 ? s.plannedDistance() : 1000);
            int dur = s.durationMinutes() != null ? s.durationMinutes()
                    : (s.plannedDuration() != null && s.plannedDuration() > 0 ? s.plannedDuration() : 60);
            Integer avgHr = s.avgHeartRate() != null ? s.avgHeartRate() : s.peakHeartRate();
            String prelimEval = s.evaluation() != null ? s.evaluation() : "FAIR";

            String hrText = s.peakHeartRate() != null ? (s.peakHeartRate() + " bpm") : "not recorded";
            String speedText = s.peakVelocity() != null ? (s.peakVelocity() + " km/h") : "not recorded";
            String stopInfo = s.earlyStopReason() != null ? (" Early stop: " + s.earlyStopReason() + ".") : "";
            String groomName = s.assignedToName() != null ? s.assignedToName() : ("#" + s.assignedTo());
            String autoRemarks = "[AUTOMATED PRELIMINARY REPORT - NOT AN OFFICIAL REVIEW FROM HEAD TRAINER] "
                    + "Review deadline (48h) expired. Preliminary metrics reported by Groom (" + groomName
                    + "): Peak HR = " + hrText + ", Peak Speed = " + speedText
                    + ", Distance = " + actualDist + "m, Duration = " + dur + " mins." + stopInfo
                    + " Stored in Archive awaiting Head Trainer re-examination.";
            if (autoRemarks.length() > 500) {
                autoRemarks = autoRemarks.substring(0, 500);
            }

            if (sessions.markAutoArchived(s.sessionId(), actualDist, dur, avgHr, prelimEval, autoRemarks) == 1) {
                users.writeAudit(null, "SESSION_REVIEW_AUTO_ARCHIVED", "TRAINING_SESSION",
                        s.sessionId(), "SUCCESS", null);
                if (notifications != null) {
                    sessions.findOwnerOfSession(s.sessionId()).ifPresent(ownerId -> notifications.notifyUser(ownerId,
                            new Notice(
                                    "SESSION_AUTO_PRELIMINARY_REPORT",
                                    "Automated preliminary report (Not Head Trainer review)",
                                    "WARNING: This is an automated preliminary report, NOT an official review from the Head Trainer (48h deadline expired). "
                                            + s.horseName() + " metrics from Groom: Peak HR " + hrText
                                            + ", Peak Speed " + speedText + ". Moved to Archive for Head Trainer review.",
                                    Notice.TRAINING_SESSION, s.sessionId())));
                    notifications.notifyHorseTrainer(s.horseId(), null, new Notice(
                            "SESSION_REVIEW_ARCHIVED",
                            "Overdue session review moved to Archive",
                            "Session #" + s.sessionId() + " for " + s.horseName()
                                    + " passed the 48-hour review deadline. An automated preliminary report was sent to the owner and the session is now in the Archive awaiting your review.",
                            Notice.TRAINING_SESSION, s.sessionId()));
                }
                processed++;
            }
        }
        return processed;
    }

    private static ApiException fieldError(String field, String msg) {
        return new ApiException(HttpStatus.BAD_REQUEST, msg, Map.of(field, msg));
    }
}
