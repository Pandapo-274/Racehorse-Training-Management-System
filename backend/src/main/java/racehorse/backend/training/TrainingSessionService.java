package racehorse.backend.training;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
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
import racehorse.backend.notification.Notice;
import racehorse.backend.notification.NotificationService;

@Service
public class TrainingSessionService {

    private static final DateTimeFormatter WHEN =
            DateTimeFormatter.ofPattern("dd MMM yyyy HH:mm", Locale.ENGLISH);

    /** Trạng thái ngựa cho phép tập. */
    private static final Set<String> TRAINABLE = Set.of("ELIGIBLE", "MONITOR");

    private final TrainingSessionRepository repository;
    private final HorseRepository horseRepository;
    private final HorseVitalsRepository vitalsRepository;
    private final UserRepository userRepository;
    private final NotificationService notifications;

    public TrainingSessionService(TrainingSessionRepository repository, HorseRepository horseRepository,
            HorseVitalsRepository vitalsRepository, UserRepository userRepository,
            NotificationService notifications) {
        this.repository = repository;
        this.horseRepository = horseRepository;
        this.vitalsRepository = vitalsRepository;
        this.userRepository = userRepository;
        this.notifications = notifications;
    }
    /** Danh sách Groom đang hoạt động để Head Trainer phân công. */
    public List<TrainingSessionRepository.GroomOption> listActiveGrooms() {
        return repository.findActiveGrooms();
    }

    /** Giao buổi tập: horse_id lấy từ phase, không nhận từ client. */
    @Transactional
    public TrainingSessionResponse create(TrainingSessionRequest r, AuthenticatedUser user, String ip) {
        TrainingSessionRepository.PhaseContext ctx = repository.findPhaseContext(r.phaseId())
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND,
                        "Phase #" + r.phaseId() + " not found"));

        if (!"ACTIVE".equals(ctx.planStatus())) {
            throw new ApiException(HttpStatus.CONFLICT,
                    "Training plan is not active (current status: " + ctx.planStatus() + ")");
        }
        checkHorseCanTrain(ctx.horseId());

        Map<String, String> errors = new LinkedHashMap<>();
        if (!repository.isActiveGroom(r.assignedTo())) {
            errors.put("assignedTo", "Assignee must be an active Groom");
        }
        LocalDate day = r.sessionDate().toLocalDate();
        if (day.isBefore(LocalDate.now())) {
            errors.put("sessionDate", "Cannot schedule a session in the past");
        } else if ((ctx.startDate() != null && day.isBefore(ctx.startDate()))
                || (ctx.endDate() != null && day.isAfter(ctx.endDate()))) {
            errors.put("sessionDate", "Session must fall within the phase period");
        }
        if (!errors.isEmpty()) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "Invalid data", errors);
        }

        // Không nhập cự ly dự kiến thì lấy theo giai đoạn.
        Integer planned = r.plannedDistance() != null ? r.plannedDistance() : ctx.distance();
        int id = repository.insert(ctx.phaseId(), ctx.horseId(), r.assignedTo(), r.sessionDate(), planned);
        userRepository.writeAudit(user.userId(), "TRAINING_SESSION_ASSIGN", "TRAINING_SESSION", id, "SUCCESS", ip);

        TrainingSessionResponse created = getOrThrow(id);
        notifications.notifyUser(created.assignedTo(), new Notice(
                "TRAINING_SESSION_ASSIGNED",
                "New training session assigned",
                created.horseName() + " on " + created.sessionDate().format(WHEN) + ".",
                Notice.TRAINING_SESSION, id));
        return created;
    }

    /**
     * SCHEDULED -> RUNNING. Kiểm tra lại ngựa vì tình trạng có thể đổi sau khi xếp
     * lịch
     * (bị khóa, bị thương). Sau bước này UC11 mới nhận được số đo của buổi tập.
     */
    @Transactional
    public TrainingSessionResponse start(int sessionId, AuthenticatedUser user, String ip) {
        TrainingSessionResponse s = getOrThrow(sessionId);
        checkCanAct(s, user);
        if (!"SCHEDULED".equals(s.status())) {
            throw new ApiException(HttpStatus.CONFLICT,
                    "Only a SCHEDULED session can be started (currently " + s.status() + ")");
        }
        /* Giáo án phải còn ACTIVE. create() và reschedule() đều đã kiểm điều
           này; chỉ start() bỏ qua, nên tạm dừng một giáo án không dừng được
           những buổi đã lên lịch của nó - Groom vẫn bấm Start được và buổi tập
           vẫn chạy như không có gì xảy ra. Đó là đúng cái việc mà nút Pause
           sinh ra để làm. */
        TrainingSessionRepository.PhaseContext ctx = repository.findPhaseContext(s.phaseId())
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND,
                        "Training phase of this session not found"));
        if (!"ACTIVE".equals(ctx.planStatus())) {
            throw new ApiException(HttpStatus.CONFLICT,
                    "Training plan is not active (current status: " + ctx.planStatus() + ")");
        }
        checkHorseCanTrain(s.horseId());
        if (repository.markRunning(sessionId) == 0) {
            throw new ApiException(HttpStatus.CONFLICT,
                    "This session has just been started by someone else");
        }
        userRepository.writeAudit(user.userId(), "TRAINING_SESSION_START", "TRAINING_SESSION", sessionId, "SUCCESS",
                ip);
        return getOrThrow(sessionId);
    }
    
    public TrainingSessionResponse get(int sessionId, AuthenticatedUser user) {
        TrainingSessionResponse s = getOrThrow(sessionId);
        checkCanAct(s, user);
        return s;
    }

    public List<TrainingSessionResponse> list(Integer horseId, String status, AuthenticatedUser user) {
        /* Groom chỉ thấy buổi tập được giao cho mình; chủ ngựa chỉ thấy buổi
           tập của ngựa mình; các vai trò còn lại thấy toàn bộ.

           Hai bộ lọc này loại trừ nhau trên thực tế (một người không thể vừa
           là GROOM vừa là HORSE_OWNER) nhưng vẫn truyền cả hai xuống
           repository thay vì viết if/else trả về hai câu khác nhau: nếu sau
           này có vai trò kép, cả hai điều kiện cùng áp và kết quả vẫn đúng
           theo nghĩa hẹp nhất. */
        Integer assignedTo = "GROOM".equals(user.role()) ? user.userId() : null;
        Integer ownerId = "HORSE_OWNER".equals(user.role()) ? user.userId() : null;
        return repository.findAll(horseId, status, assignedTo, ownerId);
    }
    
/**
 * Đổi ngày/giờ hoặc Groom phụ trách của một buổi tập.
 * Chỉ Head Trainer được gọi thông qua Controller.
 */
@Transactional
public TrainingSessionResponse reschedule(
        int sessionId,
        TrainingSessionScheduleRequest request,
        AuthenticatedUser user,
        String ip) {

    // 1. Buổi tập phải tồn tại.
    TrainingSessionResponse current = getOrThrow(sessionId);

    // 2. Chỉ đổi được lịch khi chưa bắt đầu.
    if (!"SCHEDULED".equals(current.status())) {
        throw new ApiException(
                HttpStatus.CONFLICT,
                "Only a SCHEDULED session can be rescheduled");
    }

    // 3. Tìm phase và giáo án chứa buổi tập.
    TrainingSessionRepository.PhaseContext ctx =
            repository.findPhaseContext(current.phaseId())
                    .orElseThrow(() -> new ApiException(
                            HttpStatus.NOT_FOUND,
                            "Training phase not found"));

    // 4. Giáo án phải còn ACTIVE.
    if (!"ACTIVE".equals(ctx.planStatus())) {
        throw new ApiException(
                HttpStatus.CONFLICT,
                "Training plan is not active");
    }

    // 5. Kiểm tra lại tình trạng ngựa và training lock.
    checkHorseCanTrain(current.horseId());

    Map<String, String> errors = new LinkedHashMap<>();

    // 6. Groom mới phải là tài khoản đang hoạt động.
    if (!repository.isActiveGroom(request.assignedTo())) {
        errors.put(
                "assignedTo",
                "Assignee must be an active Groom");
    }

    // 7. Ngày mới không được ở trong quá khứ.
    LocalDate day = request.sessionDate().toLocalDate();

    if (day.isBefore(LocalDate.now())) {
        errors.put(
                "sessionDate",
                "Cannot schedule a session in the past");
    }

    // 8. Ngày mới phải nằm trong thời gian của phase.
    if (ctx.startDate() != null && day.isBefore(ctx.startDate())) {
        errors.put(
                "sessionDate",
                "Session cannot be before the phase start date");
    }

    if (ctx.endDate() != null && day.isAfter(ctx.endDate())) {
        errors.put(
                "sessionDate",
                "Session cannot be after the phase end date");
    }

    if (!errors.isEmpty()) {
        throw new ApiException(
                HttpStatus.BAD_REQUEST,
                "Invalid schedule data",
                errors);
    }

    // 9. Cập nhật có điều kiện để tránh đổi lịch khi buổi tập
    // đã chuyển khỏi trạng thái SCHEDULED.
    int updated = repository.updateSchedule(
            sessionId,
            request.assignedTo(),
            request.sessionDate());

    if (updated == 0) {
        throw new ApiException(
                HttpStatus.CONFLICT,
                "Session is no longer available for rescheduling");
    }

    // 10. Ghi lịch sử thao tác.
    userRepository.writeAudit(
            user.userId(),
            "TRAINING_SESSION_RESCHEDULE",
            "TRAINING_SESSION",
            sessionId,
            "SUCCESS",
            ip);

    // 11. Báo cho Groom. Đổi người phụ trách thì người cũ cũng phải biết
    // mình không còn buổi này nữa, nếu không họ vẫn ra chuồng đúng giờ cũ.
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

    // 12. Trả lại dữ liệu mới nhất để frontend cập nhật lịch.
    return updatedSession;
}

    // ---------- helper ----------

    private TrainingSessionResponse getOrThrow(int sessionId) {
        return repository.findById(sessionId)
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "Session #" + sessionId + " not found"));
    }

    /**
     * Groom chỉ thao tác trên buổi tập của chính mình; chủ ngựa chỉ xem được
     * buổi tập của ngựa mình.
     *
     * Phép kiểm chủ ngựa phải có ở ĐÂY, không chỉ ở list(). Thêm HORSE_OWNER
     * vào @RequireRole của controller mà chỉ lọc ở danh sách thì GET
     * /api/training-sessions/{id} vẫn trả về buổi tập của bất kỳ con ngựa nào
     * - chỉ cần đoán số id. Mở quyền xem mà quên chỗ này là biến một lỗi thiếu
     * quyền thành một lỗ lộ dữ liệu.
     */
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

    private void checkHorseCanTrain(int horseId) {
        HorseResponse horse = horseRepository.findById(horseId)
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "Horse of this phase not found"));
        /* Hồ sơ PEDIGREE không phải ngựa của học viện nên không giao buổi tập.
           Phép kiểm nằm ở đây, trong checkHorseCanTrain, chứ không rải ra ba
           chỗ gọi: create(), start() và reschedule() đều đi qua hàm này, nên
           đặt một lần là cả ba đường đều được bảo vệ - và đường thứ tư, nếu
           sau này có, cũng vậy. */
        String typeProblem = HorsePolicy.trainingProblem(horse.recordType());
        if (typeProblem != null) {
            throw new ApiException(HttpStatus.CONFLICT, typeProblem);
        }
        if (!TRAINABLE.contains(horse.status())) {
            throw new ApiException(HttpStatus.CONFLICT,
                    "Horse status is " + horse.status() + ", cannot train");
        }
        if (vitalsRepository.findActiveLock(horseId).isPresent()) {
            throw new ApiException(HttpStatus.CONFLICT, "Horse is under an active training lock");
        }
    }
}