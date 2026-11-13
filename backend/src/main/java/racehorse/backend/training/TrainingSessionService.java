package racehorse.backend.training;

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
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import racehorse.backend.auth.ApiException;
import racehorse.backend.auth.AuthenticatedUser;
import racehorse.backend.auth.UserRepository;
import racehorse.backend.horse.HorsePolicy;
import racehorse.backend.horse.HorseRepository;
import racehorse.backend.horse.HorseResponse;
import racehorse.backend.horse.HorseVitalsRepository;
import racehorse.backend.incident.IncidentGuard;
import racehorse.backend.notification.Notice;
import racehorse.backend.notification.NotificationService;

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

    private final TrainingSessionRepository repository;
    private final TrainingPlanRepository planRepository;
    private final HorseRepository horseRepository;
    private final HorseVitalsRepository vitalsRepository;
    private final UserRepository userRepository;
    private final NotificationService notifications;
    private final IncidentGuard incidentGuard;
    private final int lightWorkMaxDistance;

    public TrainingSessionService(TrainingSessionRepository repository, TrainingPlanRepository planRepository,
            HorseRepository horseRepository, HorseVitalsRepository vitalsRepository,
            UserRepository userRepository, NotificationService notifications, IncidentGuard incidentGuard,
            @Value("${app.lock.light-work-max-distance:1200}") int lightWorkMaxDistance) {
        this.repository = repository;
        this.planRepository = planRepository;
        this.horseRepository = horseRepository;
        this.vitalsRepository = vitalsRepository;
        this.userRepository = userRepository;
        this.notifications = notifications;
        this.incidentGuard = incidentGuard;
        this.lightWorkMaxDistance = lightWorkMaxDistance;
    }

    /** Danh sách Groom đang hoạt động để Head Trainer phân công. */
    public List<TrainingSessionRepository.GroomOption> listActiveGrooms() {
        return repository.findActiveGrooms();
    }

    /** V37: Head Trainer chỉ thấy các Groom được Club Manager phân quyền cho mình. */
    public List<TrainingSessionRepository.GroomOption> listActiveGrooms(AuthenticatedUser user) {
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

        validateGroomAssignee(r.assignedTo(), user, errors);
        validateSessionTimeAndDuration(r.sessionDate(), duration, ctx, true, errors);

        if (!errors.isEmpty()) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "Invalid data", errors);
        }
        checkSlot(ctx, r.assignedTo(), r.sessionDate(), duration, 0);

        int id = repository.insert(ctx.phaseId(), ctx.horseId(), r.assignedTo(), r.sessionDate(), planned);
        if (duration != DEFAULT_DURATION_MINUTES) {
            repository.updatePlannedDuration(id, duration);
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
            notifications.notifyRole("HEAD_TRAINER", user, new Notice(
                    "TRAINING_SESSION_FINISHED",
                    "Training session ended early",
                    s.horseName() + " ended the session of " + s.sessionDate().format(WHEN)
                            + " early (" + reasonLabel + "). Ready for evaluation (within 48h).",
                    Notice.TRAINING_SESSION, sessionId));
            return getOrThrow(sessionId);
        }

        if (repository.markFinished(sessionId) == 0) {
            throw new ApiException(HttpStatus.CONFLICT,
                    "This session has just been stopped or finished by someone else");
        }
        userRepository.writeAudit(user == null ? null : user.userId(), "TRAINING_SESSION_FINISH", "TRAINING_SESSION", sessionId,
                "SUCCESS", ip);
        notifications.notifyRole("HEAD_TRAINER", user, new Notice(
                "TRAINING_SESSION_FINISHED",
                "Training session finished",
                s.horseName() + " finished the session of " + s.sessionDate().format(WHEN)
                        + ". Ready for evaluation (within 48h).",
                Notice.TRAINING_SESSION, sessionId));
        return getOrThrow(sessionId);
    }

    /**
     * V37: Tự động kết thúc các buổi RUNNING đã quá thời lượng và tự động huỷ các buổi SCHEDULED quá giờ.
     * Gọi định kỳ từ MissedTaskJob / TrainingAutomationJob và trước khi đọc danh sách.
     */
    @Transactional
    public int processOverdueSessions() {
        LocalDateTime now = LocalDateTime.now();
        int count = 0;
        List<TrainingSessionResponse> overdueRunning = repository.findOverdueRunningSessions(now);
        for (TrainingSessionResponse s : overdueRunning) {
            if (repository.autoFinishOverdueRunning(s.sessionId()) == 1) {
                userRepository.writeAudit(null, "TRAINING_SESSION_AUTO_FINISH", "TRAINING_SESSION",
                        s.sessionId(), "SUCCESS", null);
                notifications.notifyRole("HEAD_TRAINER", null, new Notice(
                        "TRAINING_SESSION_AUTO_FINISHED",
                        "Training session auto-completed on time limit",
                        s.horseName() + "'s session on " + s.sessionDate().format(WHEN)
                                + " reached its planned duration and was automatically finished. Please review within 48 hours.",
                        Notice.TRAINING_SESSION, s.sessionId()));
                count++;
            }
        }
        count += repository.autoCancelOverdueScheduled(now);
        return count;
    }

    public TrainingSessionResponse get(int sessionId, AuthenticatedUser user) {
        TrainingSessionResponse s = getOrThrow(sessionId);
        checkCanAct(s, user);
        return s;
    }

    public List<TrainingSessionResponse> list(Integer horseId, String status, AuthenticatedUser user) {
        processOverdueSessions();
        Integer assignedTo = "GROOM".equals(user.role()) ? user.userId() : null;
        Integer ownerId = "HORSE_OWNER".equals(user.role()) ? user.userId() : null;
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

        if (!"SCHEDULED".equals(current.status())) {
            throw new ApiException(
                    HttpStatus.CONFLICT,
                    "Only a SCHEDULED session can be rescheduled");
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

        boolean timeChanged = !request.sessionDate().equals(current.sessionDate());
        validateSessionTimeAndDuration(request.sessionDate(), duration, ctx, timeChanged, errors);

        if (!errors.isEmpty()) {
            throw new ApiException(
                    HttpStatus.BAD_REQUEST,
                    "Invalid schedule data",
                    errors);
        }

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
            notifications.notifyUser(current.assignedTo(), new Notice(
                    "TRAINING_SESSION_UNASSIGNED",
                    "Training session reassigned",
                    updatedSession.horseName() + " is now handled by "
                            + updatedSession.assignedToName() + ".",
                    null, null));
            notifications.notifyUser(updatedSession.assignedTo(), new Notice(
                    "TRAINING_SESSION_ASSIGNED",
                    "New training session assigned",
                    updatedSession.horseName() + " on " + when + ".",
                    Notice.TRAINING_SESSION, sessionId));
        } else {
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
