package racehorse.backend.training;

import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import racehorse.backend.auth.ApiException;
import racehorse.backend.auth.AuthenticatedUser;
import racehorse.backend.auth.UserRepository;
import racehorse.backend.horse.HorseRepository;
import racehorse.backend.horse.HorseResponse;
import racehorse.backend.horse.HorseVitalsRepository;

@Service
public class TrainingSessionService {

    /** Trạng thái ngựa cho phép tập. */
    private static final Set<String> TRAINABLE = Set.of("ELIGIBLE", "MONITOR");

    private final TrainingSessionRepository repository;
    private final HorseRepository horseRepository;
    private final HorseVitalsRepository vitalsRepository;
    private final UserRepository userRepository;

    public TrainingSessionService(TrainingSessionRepository repository, HorseRepository horseRepository,
                                  HorseVitalsRepository vitalsRepository, UserRepository userRepository) {
        this.repository = repository;
        this.horseRepository = horseRepository;
        this.vitalsRepository = vitalsRepository;
        this.userRepository = userRepository;
    }

    /** Giao buổi tập: horse_id lấy từ phase, không nhận từ client. */
    @Transactional
    public TrainingSessionResponse create(TrainingSessionRequest r, AuthenticatedUser user, String ip) {
        TrainingSessionRepository.PhaseContext ctx = repository.findPhaseContext(r.phaseId())
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND,
                        "Không tìm thấy giai đoạn #" + r.phaseId()));

        if (!"ACTIVE".equals(ctx.planStatus())) {
            throw new ApiException(HttpStatus.CONFLICT,
                    "Giáo án chưa được kích hoạt (đang ở trạng thái " + ctx.planStatus() + ")");
        }
        checkHorseCanTrain(ctx.horseId());

        Map<String, String> errors = new LinkedHashMap<>();
        if (!repository.isActiveGroom(r.assignedTo())) {
            errors.put("assignedTo", "Người phụ trách phải là Groom đang hoạt động");
        }
        LocalDate day = r.sessionDate().toLocalDate();
        if (day.isBefore(LocalDate.now())) {
            errors.put("sessionDate", "Không thể xếp buổi tập vào ngày đã qua");
        } else if ((ctx.startDate() != null && day.isBefore(ctx.startDate()))
                || (ctx.endDate() != null && day.isAfter(ctx.endDate()))) {
            errors.put("sessionDate", "Buổi tập phải nằm trong thời gian của giai đoạn");
        }
        if (!errors.isEmpty()) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "Dữ liệu không hợp lệ", errors);
        }

        // Không nhập cự ly dự kiến thì lấy theo giai đoạn.
        Integer planned = r.plannedDistance() != null ? r.plannedDistance() : ctx.distance();
        int id = repository.insert(ctx.phaseId(), ctx.horseId(), r.assignedTo(), r.sessionDate(), planned);
        userRepository.writeAudit(user.userId(), "TRAINING_SESSION_ASSIGN", "TRAINING_SESSION", id, "SUCCESS", ip);
        return getOrThrow(id);
    }

    /**
     * SCHEDULED -> RUNNING. Kiểm tra lại ngựa vì tình trạng có thể đổi sau khi xếp lịch
     * (bị khóa, bị thương). Sau bước này UC11 mới nhận được số đo của buổi tập.
     */
    @Transactional
    public TrainingSessionResponse start(int sessionId, AuthenticatedUser user, String ip) {
        TrainingSessionResponse s = getOrThrow(sessionId);
        checkCanAct(s, user);
        if (!"SCHEDULED".equals(s.status())) {
            throw new ApiException(HttpStatus.CONFLICT,
                    "Chỉ bắt đầu được buổi tập đang SCHEDULED (hiện là " + s.status() + ")");
        }
        checkHorseCanTrain(s.horseId());
        repository.markRunning(sessionId);
        userRepository.writeAudit(user.userId(), "TRAINING_SESSION_START", "TRAINING_SESSION", sessionId, "SUCCESS", ip);
        return getOrThrow(sessionId);
    }

    public TrainingSessionResponse get(int sessionId, AuthenticatedUser user) {
        TrainingSessionResponse s = getOrThrow(sessionId);
        checkCanAct(s, user);
        return s;
    }

    public List<TrainingSessionResponse> list(Integer horseId, String status, AuthenticatedUser user) {
        // Groom chỉ thấy buổi tập của mình; các role khác thấy toàn bộ.
        Integer assignedTo = "GROOM".equals(user.role()) ? user.userId() : null;
        return repository.findAll(horseId, status, assignedTo);
    }

    // ---------- helper ----------

    private TrainingSessionResponse getOrThrow(int sessionId) {
        return repository.findById(sessionId)
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "Không tìm thấy buổi tập #" + sessionId));
    }

    /** Groom chỉ thao tác trên buổi tập của chính mình. */
    private void checkCanAct(TrainingSessionResponse s, AuthenticatedUser user) {
        if ("GROOM".equals(user.role()) && s.assignedTo() != user.userId()) {
            throw new ApiException(HttpStatus.FORBIDDEN, "Buổi tập này không được giao cho bạn");
        }
    }

    private void checkHorseCanTrain(int horseId) {
        HorseResponse horse = horseRepository.findById(horseId)
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "Không tìm thấy ngựa của giai đoạn"));
        if (!TRAINABLE.contains(horse.status())) {
            throw new ApiException(HttpStatus.CONFLICT,
                    "Ngựa đang ở trạng thái " + horse.status() + ", không thể tập");
        }
        if (vitalsRepository.findActiveLock(horseId).isPresent()) {
            throw new ApiException(HttpStatus.CONFLICT, "Ngựa đang bị khóa huấn luyện");
        }
    }
}