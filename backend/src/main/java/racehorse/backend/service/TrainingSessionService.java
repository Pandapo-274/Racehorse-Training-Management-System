package racehorse.backend.service;

import java.io.IOException;
import java.io.InputStream;
import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.time.DayOfWeek;
import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.time.temporal.TemporalAdjusters;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

import racehorse.backend.dto.CompleteRecoveryRequest;
import racehorse.backend.dto.FinishSessionRequest;
import racehorse.backend.dto.HorseResponse;
import racehorse.backend.dto.TrainingPlanResponse;
import racehorse.backend.dto.TrainingSessionRequest;
import racehorse.backend.dto.TrainingSessionResponse;
import racehorse.backend.dto.TrainingSessionScheduleRequest;
import racehorse.backend.entity.Notice;
import racehorse.backend.exception.ApiException;
import racehorse.backend.repository.HorseRepository;
import racehorse.backend.repository.HorseVitalsRepository;
import racehorse.backend.repository.TrainingPlanRepository;
import racehorse.backend.repository.TrainingSessionRepository;
import racehorse.backend.repository.UserRepository;
import racehorse.backend.security.AuthenticatedUser;

/*
 * Nghiệp vụ buổi tập (UC10, Flow 2). Vòng đời một buổi:
 *
 *   SCHEDULED --start (đúng ngày, từ giờ đã hẹn)--> RUNNING --finish đúng/quá giờ--> COMPLETED
 *       |                                              |--finish sớm (kèm lý do)----> STOPPED_EARLY
 *       |                                              |--stop vì ngựa bất thường---> STOPPED_EARLY (+ báo cáo sự cố)
 *       +--cancel / quá khung giờ mà chưa start / giáo án kết thúc / ngựa bị rút--> CANCELLED
 *
 * Sau khi kết thúc: review_status = PENDING, HLV có 48 giờ để nhận xét (SessionService).
 *
 * Luật xếp lịch (create / reschedule):
 *   - Giáo án phải ACTIVE; ngày buổi tập nằm trong khoảng ngày của giai đoạn, không ở quá khứ.
 *   - Ngựa phải tập được (checkHorseCanTrain): không phải PEDIGREE, chưa rút, không bị khoá FULL,
 *     khoá HEAVY_WORK_ONLY thì cự ly <= app.lock.light-work-max-distance, sức khoẻ ELIGIBLE/MONITOR,
 *     không còn báo cáo sự cố mở (IncidentGuard).
 *   - Groom phải ACTIVE và thuộc đội của HLV.
 *   - Thời lượng 15..720 phút, chia hết cho 5, không kéo sang ngày hôm sau.
 *   - checkSlot: mỗi ngựa tối đa 1 buổi/ngày, không chồng giờ, cách buổi khác ít nhất min_gap_minutes
 *     của giai đoạn (mặc định 90 phút); Groom không bị trùng giờ và không đang chạy buổi khác;
 *     không vượt sessions_per_week của giai đoạn.
 */
@Service
public class TrainingSessionService {

    private static final DateTimeFormatter WHEN =
            DateTimeFormatter.ofPattern("dd MMM yyyy HH:mm", Locale.ENGLISH);

    private static final DateTimeFormatter HOUR = DateTimeFormatter.ofPattern("HH:mm", Locale.ENGLISH);
    private static final DateTimeFormatter DAY = DateTimeFormatter.ofPattern("dd MMM yyyy", Locale.ENGLISH);

    private static final long DEFAULT_GAP_MINUTES = 90;
    public static final int DEFAULT_DURATION_MINUTES = 60;
    public static final int MIN_DURATION_MINUTES = 15;
    public static final int MAX_DURATION_MINUTES = 720; // 12 tiếng

    /** Danh mục lý do kết thúc sớm chuẩn cho Groom chọn. */
    // Mã lý do -> chữ hiển thị. Frontend gửi mã (vd. "HORSE_FATIGUED"), DB lưu chữ hiển thị.
    private static final Map<String, String> EARLY_STOP_REASONS = Map.of(
            "TARGET_REACHED", "Target fitness reached",
            "HORSE_FATIGUED", "Horse fatigued",
            "WEATHER_CONDITION", "Weather or track condition",
            "EQUIPMENT_ISSUE", "Equipment issue",
            "INJURY", "Horse injury or abnormality",
            // Flow 1: the horse's Head Trainer was suspended and nobody took over.
            "NO_HEAD_TRAINER", "No Head Trainer to supervise the session"
    );

    /** Buổi còn chiếm chỗ trên lịch. */
    private static final Set<String> OPEN = Set.of("SCHEDULED", "RUNNING");

    private static final long MAX_VIDEO_BYTES = 50L * 1024 * 1024;
    private static final Set<String> ALLOWED_VIDEO_EXTENSIONS = Set.of("mp4", "webm", "mov", "m4v", "avi", "mkv");

    private final TrainingSessionRepository repository;
    private final TrainingPlanRepository planRepository;
    private final HorseRepository horseRepository;
    private final HorseVitalsRepository vitalsRepository;
    private final UserRepository userRepository;
    private final NotificationService notifications;
    private final IncidentGuard incidentGuard;
    private final int lightWorkMaxDistance;
    private final Path videoDir;

    public TrainingSessionService(TrainingSessionRepository repository, TrainingPlanRepository planRepository,
            HorseRepository horseRepository, HorseVitalsRepository vitalsRepository,
            UserRepository userRepository, NotificationService notifications, IncidentGuard incidentGuard,
            int lightWorkMaxDistance) {
        this(repository, planRepository, horseRepository, vitalsRepository, userRepository, notifications,
                incidentGuard, lightWorkMaxDistance, "uploads");
    }

    @Autowired
    public TrainingSessionService(TrainingSessionRepository repository, TrainingPlanRepository planRepository,
            HorseRepository horseRepository, HorseVitalsRepository vitalsRepository,
            UserRepository userRepository, NotificationService notifications, IncidentGuard incidentGuard,
            // @Value("${...:800}"): đọc app.lock.light-work-max-distance (application.properties đặt 800),
            // thiếu dòng cấu hình thì cũng dùng 800. Nhãn trên giao diện (vetApi.js, VetExaminationPage.js) ghi 800 m.
            @Value("${app.lock.light-work-max-distance:800}") int lightWorkMaxDistance,
            @Value("${app.upload.dir:uploads}") String uploadDir) {
        this.repository = repository;
        this.planRepository = planRepository;
        this.horseRepository = horseRepository;
        this.vitalsRepository = vitalsRepository;
        this.userRepository = userRepository;
        this.notifications = notifications;
        this.incidentGuard = incidentGuard;
        this.lightWorkMaxDistance = lightWorkMaxDistance;
        this.videoDir = Paths.get(uploadDir, "session-videos").toAbsolutePath().normalize();
    }

    /** Danh sách Groom đang hoạt động để Head Trainer phân công. */
    // Bản không lọc theo HLV (giữ cho code cũ).
    public List<TrainingSessionRepository.GroomOption> listActiveGrooms() {
        return repository.findActiveGrooms();
    }

    /** V37: Head Trainer chỉ thấy các Groom được Club Manager phân quyền cho mình (hoặc phụ trách giáo án). */
    public List<TrainingSessionRepository.GroomOption> listActiveGrooms(AuthenticatedUser user) {
        // Góp ý hội đồng: HLV chưa có đội thì danh sách rỗng, không hiện Groom của người khác.
        if (user != null && "HEAD_TRAINER".equals(user.role())) {
            return repository.findActiveGroomsByTrainer(user.userId());
        }
        return repository.findActiveGrooms();
    }

    /** Giao buổi tập: horse_id lấy từ phase, không nhận từ client. */
    @Transactional
    public TrainingSessionResponse create(TrainingSessionRequest r, AuthenticatedUser user, String ip) {
        if (user != null && !"HEAD_TRAINER".equals(user.role())) {
            throw new ApiException(HttpStatus.FORBIDDEN, "Only Head Trainer can schedule training sessions");
        }
        // PhaseContext: giai đoạn + trạng thái giáo án + ngựa + cự ly + vận tốc mục tiêu + khoảng ngày.
        TrainingSessionRepository.PhaseContext ctx = repository.findPhaseContext(r.phaseId())
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND,
                        "Phase #" + r.phaseId() + " not found"));

        if (!"ACTIVE".equals(ctx.planStatus())) {
            throw new ApiException(HttpStatus.CONFLICT,
                    "Training plan is not active (current status: " + ctx.planStatus() + ")");
        }
        Integer planned = r.plannedDistance() != null ? r.plannedDistance() : ctx.distance();
        HorseResponse horse = checkHorseCanTrain(ctx.horseId(), planned);
        checkTrainerManagesHorse(horse, user);

        Map<String, String> errors = new LinkedHashMap<>();
        if (planned != null && (planned <= 0 || planned > 5000)) {
            errors.put("plannedDistance", "Planned distance cannot exceed 5,000 meters");
        }
        if (r.plannedDuration() == null) {
            errors.put("plannedDuration", "Planned duration is required");
        } else if (r.plannedDuration() % 5 != 0) {
            errors.put("plannedDuration", "Duration must be an integer divisible by 5, between 15 and 720 minutes (max 12 hours)");
        }
        int duration = r.plannedDuration() != null ? r.plannedDuration() : DEFAULT_DURATION_MINUTES;

        BigDecimal velocityCeiling = r.velocityCeiling() != null ? r.velocityCeiling() : ctx.targetVelocity();
        if (velocityCeiling != null && (velocityCeiling.compareTo(BigDecimal.ONE) < 0
                || velocityCeiling.compareTo(new BigDecimal("100.0")) > 0)) {
            errors.put("velocityCeiling", "Max allowed speed must be between 1 and 100 km/h");
        } else if (r.velocityCeiling() != null) {
            String over = HorseStandards.speedProblem(horse.breed(), horse.dateOfBirth(), r.velocityCeiling(), LocalDate.now());
            if (over != null) {
                errors.put("velocityCeiling", over);
            }
        }

        validateGroomAssignee(r.assignedTo(), user, errors);
        validateSessionTimeAndDuration(r.sessionDate(), duration, ctx, true, errors);

        if (!errors.isEmpty()) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "Invalid data", errors);
        }
        checkSlot(ctx, r.assignedTo(), r.sessionDate(), duration, 0);

        // Hai phiên bản insert (có / không có remarks). Thời lượng và ngưỡng vận tốc ghi bằng câu
        // UPDATE riêng ngay sau đó (cột planned_duration có DEFAULT 60 trong DB).
        int id = (r.remarks() != null && !r.remarks().isBlank())
                ? repository.insert(ctx.phaseId(), ctx.horseId(), r.assignedTo(), r.sessionDate(), planned, r.remarks())
                : repository.insert(ctx.phaseId(), ctx.horseId(), r.assignedTo(), r.sessionDate(), planned);
        if (duration != DEFAULT_DURATION_MINUTES) {
            repository.updatePlannedDuration(id, duration);
        }
        if (velocityCeiling != null) {
            repository.updateVelocityCeiling(id, velocityCeiling);
        }
        userRepository.writeAudit(user == null ? null : user.userId(), "TRAINING_SESSION_ASSIGN", "TRAINING_SESSION", id, "SUCCESS", ip);

        TrainingSessionResponse created = getOrThrow(id);
        notifications.notifyUser(created.assignedTo(), new Notice(
                "TRAINING_SESSION_ASSIGNED",
                "New training session assigned",
                created.horseName() + " on " + created.sessionDate().format(WHEN)
                        + " (" + duration + " mins).",
                Notice.TRAINING_SESSION, id));
        return created;
    }

    /**
     * SCHEDULED -> RUNNING.
     */
    @Transactional
    public TrainingSessionResponse start(int sessionId, AuthenticatedUser user, String ip) {
        // Thứ tự kiểm khi bấm Start: vai trò -> đúng Groom -> đang SCHEDULED -> đúng NGÀY -> đã tới
        // GIỜ hẹn -> chưa quá khung giờ (quá thì tự huỷ) -> giáo án ACTIVE -> ngựa tập được -> ngựa
        // và Groom không đang có buổi RUNNING khác -> UPDATE có điều kiện status = 'SCHEDULED'.
        if (user != null && !"GROOM".equals(user.role()) && !"HEAD_TRAINER".equals(user.role())) {
            throw new ApiException(HttpStatus.FORBIDDEN, "Only Groom or Head Trainer can start a training session");
        }
        TrainingSessionResponse s = getOrThrow(sessionId);
        checkCanAct(s, user);
        if (!"SCHEDULED".equals(s.status())) {
            throw new ApiException(HttpStatus.CONFLICT,
                    "Only a SCHEDULED session can be started (currently " + s.status() + ")");
        }
        LocalDate planned = s.sessionDate().toLocalDate();
        if (!planned.equals(LocalDate.now())) {
            throw new ApiException(HttpStatus.CONFLICT,
                    "This session is planned for " + planned.format(DAY)
                            + ". It can only be started on that day; ask the head trainer to reschedule it");
        }
        if (s.sessionDate().isAfter(LocalDateTime.now())) {
            throw new ApiException(HttpStatus.CONFLICT,
                    "This session is scheduled for " + s.sessionDate().format(HOUR)
                            + ". It can only be started at or after the scheduled time");
        }
        int plannedDuration = s.plannedDuration() != null ? s.plannedDuration() : DEFAULT_DURATION_MINUTES;
        if (!LocalDateTime.now().isBefore(s.sessionDate().plusMinutes(plannedDuration))) {
            repository.cancel(sessionId);
            throw new ApiException(HttpStatus.CONFLICT,
                    "This session's scheduled time window (" + s.sessionDate().format(HOUR) + " - "
                            + s.sessionDate().plusMinutes(plannedDuration).format(HOUR)
                            + ") has already passed and was automatically closed");
        }

        TrainingSessionRepository.PhaseContext ctx = repository.findPhaseContext(s.phaseId())
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND,
                        "Training phase of this session not found"));
        if (!"ACTIVE".equals(ctx.planStatus())) {
            throw new ApiException(HttpStatus.CONFLICT,
                    "Training plan is not active (current status: " + ctx.planStatus() + ")");
        }
        checkHorseCanTrain(s.horseId(), s.plannedDistance());
        if (repository.hasRunningSession(s.horseId())) {
            throw new ApiException(HttpStatus.CONFLICT,
                    s.horseName() + " already has a session running. Finish or stop it first");
        }
        // V37: 1 Groom chỉ được thực hiện 1 session tại 1 thời điểm
        if (repository.hasRunningSessionByGroom(s.assignedTo(), sessionId)) {
            throw new ApiException(HttpStatus.CONFLICT,
                    s.assignedToName() + " is already executing another running session. Finish or stop it first");
        }
        if (repository.markRunning(sessionId) == 0) {
            throw new ApiException(HttpStatus.CONFLICT,
                    "This session has just been started by someone else");
        }
        userRepository.writeAudit(user == null ? null : user.userId(), "TRAINING_SESSION_START", "TRAINING_SESSION", sessionId, "SUCCESS",
                ip);
        return getOrThrow(sessionId);
    }

    /**
     * Kết thúc buổi tập (bình thường hoặc kết thúc sớm bởi Groom).
     */
    @Transactional
    public TrainingSessionResponse finish(int sessionId, AuthenticatedUser user, String ip) {
        return finish(sessionId, null, user, ip);
    }

    @Transactional
    public TrainingSessionResponse finish(int sessionId, FinishSessionRequest req,
                                          AuthenticatedUser user, String ip) {
        if (user != null && !"GROOM".equals(user.role()) && !"HEAD_TRAINER".equals(user.role())) {
            throw new ApiException(HttpStatus.FORBIDDEN, "Only Groom or Head Trainer can finish a training session");
        }
        TrainingSessionResponse s = getOrThrow(sessionId);
        checkCanAct(s, user);
        if (!"RUNNING".equals(s.status())) {
            throw new ApiException(HttpStatus.CONFLICT,
                    "Only a RUNNING session can be finished (currently " + s.status() + ")");
        }

        // Kết thúc trước giờ dự kiến (session_date + planned_duration) được tính là kết thúc sớm,
        // dù frontend không gửi cờ earlyStop.
        int plannedDuration = s.plannedDuration() != null ? s.plannedDuration() : DEFAULT_DURATION_MINUTES;
        LocalDateTime plannedEnd = s.sessionDate().plusMinutes(plannedDuration);
        boolean isBeforePlannedEnd = LocalDateTime.now().isBefore(plannedEnd);

        boolean earlyStopRequested = req != null
                && (Boolean.TRUE.equals(req.earlyStop())
                    || (req.earlyStopReason() != null && !req.earlyStopReason().isBlank()));

        boolean isEarlyStop = isBeforePlannedEnd || earlyStopRequested;

        if (isEarlyStop) {
            String rawReason = (req != null && req.earlyStopReason() != null) ? req.earlyStopReason().trim() : "";
            // Flow 1: the groom was asked to stop because the horse lost its Head Trainer.
            // Only true when the horse really has none; then no readings are needed first,
            // the point is to stop a session nobody supervises.
            boolean noTrainer = "NO_HEAD_TRAINER".equalsIgnoreCase(rawReason);
            if (noTrainer) {
                HorseResponse horse = horseRepository.findById(s.horseId()).orElse(null);
                if (horse != null && horse.trainerId() != null) {
                    throw new ApiException(HttpStatus.BAD_REQUEST,
                            s.horseName() + " has a Head Trainer (" + horse.trainerName() + "). Choose another reason",
                            Map.of("earlyStopReason", "This horse has a Head Trainer"));
                }
            }
            // V37: Chỉ được kết thúc sớm khi Groom đã báo cáo cả 2 chỉ số Heart Rate và Speed ít nhất 1 lần
            if (!noTrainer && (s.peakHeartRate() == null || s.peakVelocity() == null)) {
                throw new ApiException(HttpStatus.CONFLICT,
                        "You must report both heart rate and speed at least once during the session before ending it early");
            }
            if (rawReason.isEmpty()) {
                throw new ApiException(HttpStatus.BAD_REQUEST, "Please select a reason for ending the session early",
                        Map.of("earlyStopReason", "Select a reason for early termination"));
            }
            if (!noTrainer && (s.videoUrl() == null || s.videoUrl().isBlank())) {
                throw new ApiException(HttpStatus.CONFLICT,
                        "Please record and save the training session video before ending the session");
            }
            // Lý do lạ (không có trong bảng) vẫn được lưu nguyên văn.
            String reasonLabel = EARLY_STOP_REASONS.getOrDefault(rawReason.toUpperCase(Locale.ROOT), rawReason);
            if (reasonLabel.length() > 255) {
                reasonLabel = reasonLabel.substring(0, 255);
            }
            if (repository.markStoppedEarly(sessionId, reasonLabel) == 0) {
                throw new ApiException(HttpStatus.CONFLICT,
                        "This session has just been stopped or finished by someone else");
            }
            userRepository.writeAudit(user == null ? null : user.userId(), "TRAINING_SESSION_STOP_EARLY", "TRAINING_SESSION", sessionId,
                    "SUCCESS", ip, reasonLabel);
            notifications.notifyHorseTrainer(s.horseId(), user, new Notice(
                    "TRAINING_SESSION_FINISHED",
                    "Training session ended early",
                    s.horseName() + " ended the session of " + s.sessionDate().format(WHEN)
                            + " early (" + reasonLabel + "). Ready for evaluation (within 48h).",
                    Notice.TRAINING_SESSION, sessionId));
            return getOrThrow(sessionId);
        }

        if (s.videoUrl() == null || s.videoUrl().isBlank()) {
            throw new ApiException(HttpStatus.CONFLICT,
                    "Please record and save the training session video before finishing the session");
        }
        if (repository.markFinished(sessionId) == 0) {
            throw new ApiException(HttpStatus.CONFLICT,
                    "This session has just been stopped or finished by someone else");
        }
        userRepository.writeAudit(user == null ? null : user.userId(), "TRAINING_SESSION_FINISH", "TRAINING_SESSION", sessionId,
                "SUCCESS", ip);
        notifications.notifyHorseTrainer(s.horseId(), user, new Notice(
                "TRAINING_SESSION_FINISHED",
                "Training session finished",
                s.horseName() + " finished the session of " + s.sessionDate().format(WHEN)
                        + ". Ready for evaluation (within 48h).",
                Notice.TRAINING_SESSION, sessionId));
        return getOrThrow(sessionId);
    }

    /**
     * V50: Groom hoàn tất bài chạy (đủ giờ hoặc early stop không do chấn thương): RUNNING -> RECOVERY.
     */
    @Transactional
    public TrainingSessionResponse enterRecovery(int sessionId, AuthenticatedUser user, String ip) {
        return enterRecovery(sessionId, null, user, ip);
    }

    @Transactional
    public TrainingSessionResponse enterRecovery(int sessionId, FinishSessionRequest req,
                                                 AuthenticatedUser user, String ip) {
        if (user != null && !"GROOM".equals(user.role()) && !"HEAD_TRAINER".equals(user.role())) {
            throw new ApiException(HttpStatus.FORBIDDEN, "Only Groom or Head Trainer can start recovery phase");
        }
        TrainingSessionResponse s = getOrThrow(sessionId);
        checkCanAct(s, user);
        if (!"RUNNING".equals(s.status())) {
            throw new ApiException(HttpStatus.CONFLICT,
                    "Only a RUNNING session can enter recovery (currently " + s.status() + ")");
        }

        String rawReason = (req != null && req.earlyStopReason() != null) ? req.earlyStopReason().trim() : "";
        if ("INJURY".equalsIgnoreCase(rawReason)) {
            throw new ApiException(HttpStatus.CONFLICT,
                    "Injury stops skip the recovery phase; please use Stop & Call Vet instead");
        }
        boolean noTrainer = "NO_HEAD_TRAINER".equalsIgnoreCase(rawReason);
        if (!noTrainer && (s.peakHeartRate() == null || s.peakVelocity() == null)) {
            throw new ApiException(HttpStatus.CONFLICT,
                    "You must report both heart rate and speed at least once before entering recovery");
        }
        if (!noTrainer && (s.videoUrl() == null || s.videoUrl().isBlank())) {
            throw new ApiException(HttpStatus.CONFLICT,
                    "Please record and save the training session video before entering recovery");
        }

        int plannedDuration = s.plannedDuration() != null ? s.plannedDuration() : DEFAULT_DURATION_MINUTES;
        boolean isEndReached = !LocalDateTime.now().isBefore(s.sessionDate().plusMinutes(plannedDuration));
        boolean earlyStopRequested = req != null
                && (Boolean.TRUE.equals(req.earlyStop()) || !rawReason.isEmpty());
        if (!isEndReached && !earlyStopRequested) {
            throw new ApiException(HttpStatus.CONFLICT,
                    "You can only enter recovery after the scheduled duration completes or by selecting an early-stop reason");
        }
        if (!isEndReached && rawReason.isEmpty()) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "Please select an early-stop reason before entering recovery",
                    Map.of("earlyStopReason", "Select a reason for early termination"));
        }

        String earlyReasonLabel = rawReason.isEmpty()
                ? null
                : EARLY_STOP_REASONS.getOrDefault(rawReason.toUpperCase(Locale.ROOT), rawReason);
        if (earlyReasonLabel != null && earlyReasonLabel.length() > 255) {
            earlyReasonLabel = earlyReasonLabel.substring(0, 255);
        }

        int recMinutes = repository.findPhaseRecoveryMinutes(s.phaseId())
                .orElse(s.recoveryDurationMinutes() != null ? s.recoveryDurationMinutes() : 15);
        LocalDateTime now = LocalDateTime.now();
        LocalDateTime endsAt = now.plusMinutes(recMinutes);
        int updatedRows = earlyReasonLabel != null
                ? repository.markRecoveryStarted(sessionId, recMinutes, now, endsAt, earlyReasonLabel)
                : repository.markRecoveryStarted(sessionId, recMinutes, now, endsAt);
        if (updatedRows == 0) {
            throw new ApiException(HttpStatus.CONFLICT,
                    "This session has just changed status");
        }
        userRepository.writeAudit(user == null ? null : user.userId(),
                "TRAINING_SESSION_RECOVERY_START", "TRAINING_SESSION", sessionId, "SUCCESS", ip, earlyReasonLabel);
        return getOrThrow(sessionId);
    }

    /**
     * V50: Groom gửi nhịp tim hồi phục thực tế (actual_hrr) -> COMPLETED.
     * Nếu actual_hrr > target_hrr của plan thì bật overload_warning = true và cảnh báo Trainer.
     */
    @Transactional
    public TrainingSessionResponse completeRecovery(int sessionId, CompleteRecoveryRequest req,
                                                    AuthenticatedUser user, String ip) {
        if (user != null && !"GROOM".equals(user.role()) && !"HEAD_TRAINER".equals(user.role())) {
            throw new ApiException(HttpStatus.FORBIDDEN, "Only Groom or Head Trainer can complete recovery");
        }
        TrainingSessionResponse s = getOrThrow(sessionId);
        checkCanAct(s, user);
        if (!"RECOVERY".equals(s.status()) && !"RUNNING".equals(s.status())) {
            throw new ApiException(HttpStatus.CONFLICT,
                    "Only a RECOVERY or RUNNING session can be completed with recovery HR (currently " + s.status() + ")");
        }
        Integer targetHrr = repository.findPlanTargetHrr(s.phaseId()).orElse(s.targetHrr());
        boolean overload = targetHrr != null && req.actualHrr() > targetHrr;

        if (repository.markRecoveryCompleted(sessionId, req.actualHrr(), overload, req.remarks()) == 0) {
            throw new ApiException(HttpStatus.CONFLICT,
                    "This session has just been completed or modified by someone else");
        }
        userRepository.writeAudit(user == null ? null : user.userId(),
                "TRAINING_SESSION_COMPLETE_RECOVERY", "TRAINING_SESSION", sessionId, "SUCCESS", ip,
                "actualHrr=" + req.actualHrr() + (overload ? " OVERLOAD" : ""));

        if (overload) {
            notifications.notifyHorseTrainer(s.horseId(), user, new Notice(
                    "SESSION_OVERLOAD_WARNING",
                    "Overload warning: " + s.horseName(),
                    s.horseName() + " recorded recovery HR " + req.actualHrr()
                            + " bpm, exceeding target HRR (" + targetHrr + " bpm). Monitor for overtraining.",
                    Notice.TRAINING_SESSION, sessionId));
        } else {
            notifications.notifyHorseTrainer(s.horseId(), user, new Notice(
                    "TRAINING_SESSION_FINISHED",
                    "Training session finished",
                    s.horseName() + " completed training and recovery (HRR: " + req.actualHrr()
                            + " bpm). Ready for evaluation (within 48h).",
                    Notice.TRAINING_SESSION, sessionId));
        }
        return getOrThrow(sessionId);
    }

    /**
     * V37: Tự động kết thúc các buổi RUNNING đã quá thời lượng và tự động huỷ các buổi SCHEDULED quá giờ.
     * Gọi định kỳ từ MissedTaskJob / TrainingAutomationJob và trước khi đọc danh sách.
     */
    @Transactional
    public int processOverdueSessions() {
        // Hai việc: (1) buổi RUNNING đã hết thời lượng -> tự COMPLETED và báo HLV nhận xét;
        // (2) buổi SCHEDULED đã qua cả khung giờ mà chưa start -> tự CANCELLED.
        LocalDateTime now = LocalDateTime.now();
        int count = 0;
        List<TrainingSessionResponse> overdueRunning = repository.findOverdueRunningSessions(now);
        for (TrainingSessionResponse s : overdueRunning) {
            boolean hasAnyMetric = s.peakHeartRate() != null || s.peakVelocity() != null;
            if (!hasAnyMetric) {
                String failNote = "[Auto-failed: No heart rate or speed metrics were recorded within 5 minutes after the session ended]";
                if (repository.autoFailOverdueRunning(s.sessionId(), "Failed: no metrics recorded", failNote) == 1) {
                    userRepository.writeAudit(null, "TRAINING_SESSION_AUTO_FAILED", "TRAINING_SESSION",
                            s.sessionId(), "SUCCESS", null);
                    notifications.notifyHorseTrainer(s.horseId(), null, new Notice(
                            "TRAINING_SESSION_AUTO_FAILED",
                            "Session failed (no metrics recorded): " + s.horseName(),
                            s.horseName() + "'s session on " + s.sessionDate().format(WHEN)
                                    + " ended + 5 minutes with no heart rate or speed readings recorded."
                                    + " Automatically failed and closed without recovery.",
                            Notice.TRAINING_SESSION, s.sessionId()));
                    count++;
                }
            } else {
                int recMinutes = repository.findPhaseRecoveryMinutes(s.phaseId())
                        .orElse(s.recoveryDurationMinutes() != null ? s.recoveryDurationMinutes() : 15);
                LocalDateTime recNow = LocalDateTime.now();
                LocalDateTime endsAt = recNow.plusMinutes(recMinutes);
                if (repository.markAutoRecoveryStarted(s.sessionId(), recMinutes, recNow, endsAt) == 1) {
                    userRepository.writeAudit(null, "TRAINING_SESSION_AUTO_RECOVERY", "TRAINING_SESSION",
                            s.sessionId(), "SUCCESS", null);
                    notifications.notifyHorseTrainer(s.horseId(), null, new Notice(
                            "TRAINING_SESSION_AUTO_RECOVERY",
                            "Session auto-entered recovery: " + s.horseName(),
                            s.horseName() + "'s session on " + s.sessionDate().format(WHEN)
                                    + " exceeded its planned duration by 5 minutes and was automatically moved to Recovery ("
                                    + recMinutes + " mins).",
                            Notice.TRAINING_SESSION, s.sessionId()));
                    count++;
                }
            }
        }
        count += repository.autoCancelOverdueScheduled(now);

        List<TrainingSessionResponse> overdueRecovery = repository.findOverdueRecoverySessions(now);
        for (TrainingSessionResponse s : overdueRecovery) {
            String warning = "[Warning: Recovery heart rate was not recorded within 5 minutes after cooldown ended]";
            if (repository.autoCompleteOverdueRecovery(s.sessionId(), warning) == 1) {
                userRepository.writeAudit(null, "TRAINING_SESSION_RECOVERY_AUTO_SUBMIT", "TRAINING_SESSION",
                        s.sessionId(), "SUCCESS", null);
                notifications.notifyHorseTrainer(s.horseId(), null, new Notice(
                        "SESSION_RECOVERY_MISSING",
                        "Recovery HR not recorded: " + s.horseName(),
                        s.horseName() + "'s session #" + s.sessionId()
                                + " ended recovery cooldown + 5 minutes without recovery heart rate recorded."
                                + " Auto-submitted to Session Review with a missing recovery HR warning.",
                        Notice.TRAINING_SESSION, s.sessionId()));
                count++;
            }
        }
        return count;
    }

    @Transactional
    public TrainingSessionResponse saveVideo(int sessionId, String videoUrl, AuthenticatedUser user, String ip) {
        if (user != null && !"GROOM".equals(user.role()) && !"HEAD_TRAINER".equals(user.role())) {
            throw new ApiException(HttpStatus.FORBIDDEN, "Only assigned Groom or Head Trainer can attach session video");
        }
        TrainingSessionResponse s = getOrThrow(sessionId);
        checkCanAct(s, user);
        if ("CANCELLED".equals(s.status()) || "SCHEDULED".equals(s.status())) {
            throw new ApiException(HttpStatus.CONFLICT, "Session video can only be recorded during or after a session");
        }
        String trimmed = videoUrl == null ? "" : videoUrl.trim();
        if (trimmed.isEmpty()) {
            repository.updateVideoUrl(sessionId, null);
        } else {
            if (!trimmed.startsWith("http://") && !trimmed.startsWith("https://")) {
                throw new ApiException(HttpStatus.BAD_REQUEST, "Invalid data",
                        Map.of("videoUrl", "Video URL must start with http:// or https://"));
            }
            if (trimmed.length() > 500) {
                throw new ApiException(HttpStatus.BAD_REQUEST, "Invalid data",
                        Map.of("videoUrl", "Video URL must be at most 500 characters"));
            }
            repository.updateVideoUrl(sessionId, trimmed);
        }
        userRepository.writeAudit(user == null ? null : user.userId(), "TRAINING_SESSION_VIDEO",
                "TRAINING_SESSION", sessionId, "SUCCESS", ip);
        return getOrThrow(sessionId);
    }

    @Transactional
    public TrainingSessionResponse uploadVideo(int sessionId, MultipartFile file, AuthenticatedUser user, String ip) {
        if (user != null && !"GROOM".equals(user.role()) && !"HEAD_TRAINER".equals(user.role())) {
            throw new ApiException(HttpStatus.FORBIDDEN, "Only assigned Groom or Head Trainer can upload session video");
        }
        TrainingSessionResponse s = getOrThrow(sessionId);
        checkCanAct(s, user);
        if (!"RUNNING".equals(s.status())) {
            throw new ApiException(HttpStatus.CONFLICT,
                    "Session video can only be uploaded while the session is RUNNING");
        }
        if (file == null || file.isEmpty()) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "Invalid video file",
                    Map.of("file", "Please select a video file to upload"));
        }
        if (file.getSize() > MAX_VIDEO_BYTES) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "Invalid video file",
                    Map.of("file", "Video file must be 50 MB or smaller"));
        }
        String original = file.getOriginalFilename() == null ? "" : file.getOriginalFilename().trim();
        String ext = "";
        int dot = original.lastIndexOf('.');
        if (dot >= 0 && dot < original.length() - 1) {
            ext = original.substring(dot + 1).toLowerCase(Locale.ROOT);
        }
        String contentType = file.getContentType() == null ? "" : file.getContentType().toLowerCase(Locale.ROOT);
        boolean validMime = contentType.startsWith("video/");
        boolean validExt = ALLOWED_VIDEO_EXTENSIONS.contains(ext);
        if (!validMime && !validExt) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "Invalid video file",
                    Map.of("file", "Only video files (MP4, WebM, MOV, M4V, AVI, MKV) are allowed"));
        }
        if (ext.isEmpty()) {
            ext = switch (contentType) {
                case "video/webm" -> "webm";
                case "video/quicktime" -> "mov";
                case "video/x-msvideo" -> "avi";
                case "video/x-matroska" -> "mkv";
                default -> "mp4";
            };
        }
        String filename = sessionId + "-" + UUID.randomUUID() + "." + ext;
        try {
            Files.createDirectories(videoDir);
            Path dest = videoDir.resolve(filename).normalize();
            if (!dest.startsWith(videoDir)) {
                throw new ApiException(HttpStatus.BAD_REQUEST, "Invalid file path");
            }
            try (InputStream in = file.getInputStream()) {
                Files.copy(in, dest, StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (IOException ex) {
            throw new ApiException(HttpStatus.INTERNAL_SERVER_ERROR, "Could not store training video");
        }
        if (s.videoUrl() != null && s.videoUrl().startsWith("/uploads/session-videos/")) {
            String prevName = s.videoUrl().substring("/uploads/session-videos/".length());
            if (!prevName.isBlank() && !prevName.contains("/") && !prevName.contains("\\")) {
                try {
                    Files.deleteIfExists(videoDir.resolve(prevName).normalize());
                } catch (IOException ignored) {
                    // Best-effort cleanup
                }
            }
        }
        String url = "/uploads/session-videos/" + filename;
        repository.updateVideoUrl(sessionId, url);
        userRepository.writeAudit(user == null ? null : user.userId(), "TRAINING_SESSION_VIDEO_UPLOAD",
                "TRAINING_SESSION", sessionId, "SUCCESS", ip, filename);
        return getOrThrow(sessionId);
    }

    public TrainingSessionResponse get(int sessionId, AuthenticatedUser user) {
        TrainingSessionResponse s = getOrThrow(sessionId);
        checkCanAct(s, user);
        return s;
    }

    public List<TrainingSessionResponse> list(Integer horseId, String status, AuthenticatedUser user) {
        // Mỗi lần mở danh sách đều dọn buổi quá giờ trước, để màn hình luôn đúng trạng thái.
        processOverdueSessions();
        Integer assignedTo = "GROOM".equals(user.role()) ? user.userId() : null;
        Integer ownerId = "HORSE_OWNER".equals(user.role()) ? user.userId() : null;
        // Groom chỉ thấy lịch của ngựa còn ở CLB (ngựa đã rút bị ẩn).
        boolean activeHorsesOnly = assignedTo != null;
        List<TrainingSessionResponse> all = repository.findAll(horseId, status, assignedTo, ownerId, activeHorsesOnly);
        // V37: Head Trainer chỉ thấy lịch tập của những ngựa Club Manager giao cho mình.
        if ("HEAD_TRAINER".equals(user.role())) {
            java.util.Set<Integer> mine = horseRepository.findHorseIdsByTrainer(user.userId());
            return all.stream().filter(s -> mine.contains(s.horseId())).toList();
        }
        return all;
    }

    /**
     * Đổi ngày/giờ, thời lượng hoặc Groom phụ trách của một buổi tập.
     */
    @Transactional
    public TrainingSessionResponse reschedule(
            int sessionId,
            TrainingSessionScheduleRequest request,
            AuthenticatedUser user,
            String ip) {
        if (user != null && !"HEAD_TRAINER".equals(user.role())) {
            throw new ApiException(HttpStatus.FORBIDDEN, "Only Head Trainer can reschedule training sessions");
        }

        TrainingSessionResponse current = getOrThrow(sessionId);

        if (!"SCHEDULED".equals(current.status()) && !"UNASSIGNED".equals(current.status())) {
            throw new ApiException(
                    HttpStatus.CONFLICT,
                    "Only a SCHEDULED or UNASSIGNED session can be rescheduled");
        }

        TrainingSessionRepository.PhaseContext ctx =
                repository.findPhaseContext(current.phaseId())
                        .orElseThrow(() -> new ApiException(
                                HttpStatus.NOT_FOUND,
                                "Training phase not found"));

        if (!"ACTIVE".equals(ctx.planStatus())) {
            throw new ApiException(
                    HttpStatus.CONFLICT,
                    "Training plan is not active");
        }

        HorseResponse horse = checkHorseCanTrain(current.horseId(), current.plannedDistance());
        checkTrainerManagesHorse(horse, user);

        int duration = request.plannedDuration() != null
                ? request.plannedDuration()
                : (current.plannedDuration() != null ? current.plannedDuration() : DEFAULT_DURATION_MINUTES);

        Map<String, String> errors = new LinkedHashMap<>();
        validateGroomAssignee(request.assignedTo(), user, errors);

        // Chỉ kiểm "không ở quá khứ" khi giờ thật sự đổi: đổi Groom cho buổi sắp diễn ra hôm nay
        // vẫn được dù giờ hẹn đã qua vài phút.
        boolean timeChanged = !request.sessionDate().equals(current.sessionDate());
        validateSessionTimeAndDuration(request.sessionDate(), duration, ctx, timeChanged, errors);

        if (!errors.isEmpty()) {
            throw new ApiException(
                    HttpStatus.BAD_REQUEST,
                    "Invalid schedule data",
                    errors);
        }

        // excludeId = sessionId: không so trùng giờ với chính buổi đang sửa.
        checkSlot(ctx, request.assignedTo(), request.sessionDate(), duration, sessionId);

        int updated = repository.updateSchedule(
                sessionId,
                request.assignedTo(),
                request.sessionDate());

        if (updated == 0) {
            throw new ApiException(
                    HttpStatus.CONFLICT,
                    "Session is no longer available for rescheduling");
        }

        if (request.plannedDuration() != null) {
            repository.updatePlannedDuration(sessionId, duration);
        }

        userRepository.writeAudit(
                user == null ? null : user.userId(),
                "TRAINING_SESSION_RESCHEDULE",
                "TRAINING_SESSION",
                sessionId,
                "SUCCESS",
                ip);

        TrainingSessionResponse updatedSession = getOrThrow(sessionId);
        String when = updatedSession.sessionDate().format(WHEN);

        if (current.assignedTo() != updatedSession.assignedTo()) {
            if (current.assignedTo() > 0) {
                notifications.notifyUser(current.assignedTo(), new Notice(
                        "TRAINING_SESSION_UNASSIGNED",
                        "Training session reassigned",
                        updatedSession.horseName() + " is now handled by "
                                + updatedSession.assignedToName() + ".",
                        null, null));
            }
            if (updatedSession.assignedTo() > 0) {
                notifications.notifyUser(updatedSession.assignedTo(), new Notice(
                        "TRAINING_SESSION_ASSIGNED",
                        "New training session assigned",
                        updatedSession.horseName() + " on " + when + ".",
                        Notice.TRAINING_SESSION, sessionId));
            }
        } else if (updatedSession.assignedTo() > 0) {
            notifications.notifyUser(updatedSession.assignedTo(), new Notice(
                    "TRAINING_SESSION_RESCHEDULED",
                    "Training session rescheduled",
                    updatedSession.horseName() + " moved to " + when + ".",
                    Notice.TRAINING_SESSION, sessionId));
        }

        return updatedSession;
    }

    @Transactional
    public TrainingSessionResponse cancel(int sessionId, AuthenticatedUser user, String ip) {
        if (user != null && !"HEAD_TRAINER".equals(user.role()) && !"CLUB_MANAGER".equals(user.role())) {
            throw new ApiException(HttpStatus.FORBIDDEN, "Only Head Trainer or Club Manager can cancel training sessions");
        }
        TrainingSessionResponse current = getOrThrow(sessionId);
        checkCanAct(current, user);
        if (!"SCHEDULED".equals(current.status())) {
            throw new ApiException(HttpStatus.CONFLICT,
                    "Only a SCHEDULED session can be cancelled (currently " + current.status() + ")");
        }
        if (repository.cancel(sessionId) == 0) {
            throw new ApiException(HttpStatus.CONFLICT,
                    "Session is no longer available for cancellation");
        }
        userRepository.writeAudit(user == null ? null : user.userId(), "TRAINING_SESSION_CANCEL", "TRAINING_SESSION",
                sessionId, "SUCCESS", ip);
        notifications.notifyUser(current.assignedTo(), new Notice(
                "TRAINING_SESSION_CANCELLED",
                "Training session cancelled",
                "Session for " + current.horseName() + " on " + current.sessionDate().format(WHEN) + " was cancelled.",
                Notice.TRAINING_SESSION, sessionId));
        return getOrThrow(sessionId);
    }

    // ---------- helper ----------

    private TrainingSessionResponse getOrThrow(int sessionId) {
        return repository.findById(sessionId)
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "Session #" + sessionId + " not found"));
    }

    private void checkCanAct(TrainingSessionResponse s, AuthenticatedUser user) {
        // Kiểm quyền theo dữ liệu: Groom chỉ thao tác buổi được giao cho mình; chủ ngựa chỉ xem buổi
        // của ngựa mình. HLV và Manager không bị giới hạn ở đây.
        if ("GROOM".equals(user.role()) && s.assignedTo() != user.userId()) {
            throw new ApiException(HttpStatus.FORBIDDEN, "This session is not assigned to you");
        }
        if ("HORSE_OWNER".equals(user.role())) {
            HorseResponse horse = horseRepository.findById(s.horseId())
                    .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "Horse of this session not found"));
            if (horse.ownerId() != user.userId()) {
                throw new ApiException(HttpStatus.FORBIDDEN, "This session belongs to another owner's horse");
            }
        }
    }

    private void checkTrainerManagesHorse(HorseResponse horse, AuthenticatedUser user) {
        if (user == null || !"HEAD_TRAINER".equals(user.role())) return;
        if (horse.trainerId() != null && !horse.trainerId().equals(user.userId())) {
            throw new ApiException(HttpStatus.FORBIDDEN,
                    "This horse is assigned to Head Trainer "
                            + (horse.trainerName() != null ? horse.trainerName() : "#" + horse.trainerId()));
        } else if (horse.trainerId() == null) {
            throw new ApiException(HttpStatus.FORBIDDEN,
                    "This horse has not been assigned to you by the Club Manager yet");
        }
    }

    private void validateGroomAssignee(int groomId, AuthenticatedUser user, Map<String, String> errors) {
        if (!repository.isActiveGroom(groomId)) {
            errors.put("assignedTo", "Assignee must be an active Groom");
            return;
        }
        if (user != null && "HEAD_TRAINER".equals(user.role())
                && !repository.isGroomOfTrainer(groomId, user.userId())) {
            errors.put("assignedTo", "This Groom is not assigned to your management team by the Club Manager");
        }
    }

    private void validateSessionTimeAndDuration(LocalDateTime sessionDate, int duration,
                                                TrainingSessionRepository.PhaseContext ctx,
                                                boolean checkPastTime,
                                                Map<String, String> errors) {
        LocalDate day = sessionDate.toLocalDate();
        if (day.isBefore(LocalDate.now()) || (checkPastTime && sessionDate.isBefore(LocalDateTime.now()))) {
            errors.put("sessionDate", "Cannot schedule a session in the past");
        } else if ((ctx.startDate() != null && day.isBefore(ctx.startDate()))
                || (ctx.endDate() != null && day.isAfter(ctx.endDate()))) {
            errors.put("sessionDate", "Session must fall within the phase period");
        }

        if (duration < MIN_DURATION_MINUTES || duration > MAX_DURATION_MINUTES || duration % 5 != 0) {
            errors.put("plannedDuration", "Duration must be an integer divisible by 5, between 15 and 720 minutes (max 12 hours)");
        } else {
            LocalDateTime endTime = sessionDate.plusMinutes(duration);
            // Không được phép qua ngày hôm sau tính từ giờ đặt lịch
            if (!endTime.toLocalDate().equals(day)) {
                errors.put("plannedDuration",
                        "Session duration cannot cross into the next day (must finish before 23:59 on " + day.format(DAY) + ")");
            }
        }
    }

    /**
     * V37: Kiểm tra các nguyên tắc đặt lịch training:
     *  - Khoảng nghỉ tối thiểu của phase (min_gap_minutes)
     *  - 1 ngựa không được có 2 session trong cùng 1 ngày
     *  - 1 Groom chỉ được chỉ định huấn luyện 1 ngựa tại 1 thời điểm nhất định và đang không thực hiện session nào cả
     *  - Không vượt số buổi mỗi tuần của giai đoạn
     */
    private void checkSlot(TrainingSessionRepository.PhaseContext ctx, int groomId,
                           LocalDateTime when, int plannedDuration, int excludeId) {
        // Lấy giai đoạn hiện tại để biết min_gap_minutes và sessions_per_week.
        TrainingPlanResponse.Phase phase = planRepository.findPhases(ctx.planId()).stream()
                .filter(p -> p.phaseId() == ctx.phaseId())
                .findFirst().orElse(null);
        long horseGap = phase != null && phase.minGapMinutes() != null
                ? phase.minGapMinutes() : DEFAULT_GAP_MINUTES;
        LocalDateTime newEnd = when.plusMinutes(plannedDuration);
        List<TrainingSessionResponse> horseSessions = repository.findAll(ctx.horseId(), null, null, null, false);
        for (TrainingSessionResponse s : horseSessions) {
            if (s.sessionId() == excludeId || "CANCELLED".equals(s.status())) continue;

            int sDur = s.plannedDuration() != null ? s.plannedDuration() : DEFAULT_DURATION_MINUTES;
            LocalDateTime sStart = s.sessionDate();
            LocalDateTime sEnd = sStart.plusMinutes(sDur);
            // Hai khoảng [when, newEnd) và [sStart, sEnd) chồng nhau khi when < sEnd và sStart < newEnd.
            boolean overlaps = when.isBefore(sEnd) && sStart.isBefore(newEnd);

            if (OPEN.contains(s.status()) && tooClose(s.sessionDate(), when, horseGap)) {
                throw new ApiException(HttpStatus.CONFLICT, s.horseName() + " already has a session at "
                        + s.sessionDate().format(WHEN) + ". This phase needs at least "
                        + horseGap + " minutes of rest between sessions");
            }
            if (overlaps) {
                throw new ApiException(HttpStatus.CONFLICT, s.horseName() + " already has an overlapping session on "
                        + when.toLocalDate().format(DAY) + " from " + sStart.format(HOUR) + " to " + sEnd.format(HOUR)
                        + ". A horse cannot have 2 sessions on the same day with overlapping time slots");
            }
            if (s.sessionDate().toLocalDate().equals(when.toLocalDate())) {
                throw new ApiException(HttpStatus.CONFLICT, s.horseName() + " already has a session on "
                        + when.toLocalDate().format(DAY) + " at " + s.sessionDate().format(HOUR)
                        + ". A horse cannot have 2 sessions on the same day");
            }
        }
        // Vòng thứ hai: lịch của Groom (findAll lọc theo assignedTo = groomId).
        for (TrainingSessionResponse s : repository.findAll(null, null, groomId, null, false)) {
            if (s.sessionId() == excludeId || !OPEN.contains(s.status())) continue;

            if ("RUNNING".equals(s.status())) {
                throw new ApiException(HttpStatus.CONFLICT, s.assignedToName()
                        + " is currently executing an active session with " + s.horseName()
                        + " and cannot be assigned while a session is running");
            }
            int sDur = s.plannedDuration() != null ? s.plannedDuration() : DEFAULT_DURATION_MINUTES;
            LocalDateTime sStart = s.sessionDate();
            LocalDateTime sEnd = sStart.plusMinutes(sDur);
            boolean overlaps = when.isBefore(sEnd) && sStart.isBefore(newEnd);
            if (overlaps || (s.horseId() != ctx.horseId() && tooClose(sStart, when, DEFAULT_GAP_MINUTES))) {
                throw new ApiException(HttpStatus.CONFLICT, s.assignedToName() + " is already handling "
                        + s.horseName() + " at " + s.sessionDate().format(HOUR) + " that day");
            }
        }

        // Đếm buổi của giai đoạn này trong tuần (thứ Hai -> Chủ nhật) chứa ngày xếp lịch.
        Integer perWeek = phase == null ? null : phase.sessionsPerWeek();
        if (perWeek != null) {
            LocalDate monday = when.toLocalDate().with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY));
            long inWeek = horseSessions.stream()
                    .filter(s -> s.sessionId() != excludeId && s.phaseId() == ctx.phaseId())
                    .filter(s -> !"CANCELLED".equals(s.status()))
                    .map(s -> s.sessionDate().toLocalDate())
                    .filter(d -> !d.isBefore(monday) && d.isBefore(monday.plusDays(7)))
                    .count();
            if (inWeek >= perWeek) {
                throw new ApiException(HttpStatus.CONFLICT, "This phase allows " + perWeek
                        + " sessions per week, and the week of " + monday.format(DAY)
                        + " already has " + inWeek);
            }
        }
    }

    private static boolean tooClose(LocalDateTime a, LocalDateTime b, long gapMinutes) {
        return Math.abs(Duration.between(a, b).toMinutes()) < gapMinutes;
    }

    private HorseResponse checkHorseCanTrain(int horseId, Integer plannedDistance) {
        // Kiểm "ngựa có được tập không" dùng chung cho create, start, reschedule. Ném 409 với câu
        // lý do cụ thể ngay ở điều kiện đầu tiên thất bại.
        HorseResponse horse = horseRepository.findById(horseId)
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "Horse of this phase not found"));
        String typeProblem = HorsePolicy.trainingProblem(horse.recordType());
        if (typeProblem != null) {
            throw new ApiException(HttpStatus.CONFLICT, typeProblem);
        }
        String withdrawn = HorsePolicy.withdrawnProblem(horse);
        if (withdrawn != null) {
            throw new ApiException(HttpStatus.CONFLICT, withdrawn);
        }
        var activeLock = vitalsRepository.findActiveLock(horseId);
        if (activeLock.isPresent()) {
            var lock = activeLock.get();
            if (!"HEAVY_WORK_ONLY".equals(lock.lockLevel())) {
                throw new ApiException(HttpStatus.CONFLICT, "Horse is under a full training lock");
            }
            if (plannedDistance == null || plannedDistance > lightWorkMaxDistance) {
                throw new ApiException(HttpStatus.CONFLICT, "Horse is limited to light work by a training lock: "
                        + "at most " + lightWorkMaxDistance + " m per session");
            }
        } else if (!HorsePolicy.TRAINABLE_STATUSES.contains(horse.status())) {
            throw new ApiException(HttpStatus.CONFLICT,
                    "Horse status is " + horse.status() + ", cannot train");
        }
        incidentGuard.assertNoOpenReport(horseId);
        return horse;
    }
}
