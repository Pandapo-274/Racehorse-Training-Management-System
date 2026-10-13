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
public class TrainingPlanService {

    /** Các bước chuyển trạng thái được phép. COMPLETED là điểm cuối. */
    private static final Map<String, Set<String>> TRANSITIONS = Map.of(
            "DRAFT", Set.of("ACTIVE"),
            "ACTIVE", Set.of("PAUSED", "COMPLETED"),
            "PAUSED", Set.of("ACTIVE", "COMPLETED"),
            "COMPLETED", Set.of());

    /** Trạng thái ngựa cho phép bắt đầu tập. */
    private static final Set<String> TRAINABLE = Set.of("ELIGIBLE", "MONITOR");

    private final TrainingPlanRepository planRepository;
    private final HorseRepository horseRepository;
    private final HorseVitalsRepository vitalsRepository;
    private final UserRepository userRepository;

    public TrainingPlanService(TrainingPlanRepository planRepository, HorseRepository horseRepository,
                               HorseVitalsRepository vitalsRepository, UserRepository userRepository) {
        this.planRepository = planRepository;
        this.horseRepository = horseRepository;
        this.vitalsRepository = vitalsRepository;
        this.userRepository = userRepository;
    }

    /** Một transaction: nếu chèn giai đoạn thứ 3 lỗi thì giáo án và 2 giai đoạn trước cũng bị hủy. */
    @Transactional
    public TrainingPlanResponse create(TrainingPlanRequest r, AuthenticatedUser user, String ip) {
        validate(r);
        int planId = planRepository.insertPlan(r.horseId(), user.userId(), r.planName().trim(),
                r.startDate(), r.endDate());
        int phaseNo = 1;
        for (TrainingPlanRequest.PhaseRequest p : r.phases()) {
            planRepository.insertPhase(planId, phaseNo++, p);
        }
        userRepository.writeAudit(user.userId(), "TRAINING_PLAN_CREATE", "TRAINING_PLAN", planId, "SUCCESS", ip);
        return getOrThrow(planId);
    }

    @Transactional
    public TrainingPlanResponse changeStatus(int planId, String newStatus, AuthenticatedUser user, String ip) {
        TrainingPlanResponse plan = getOrThrow(planId);
        if (!TRANSITIONS.getOrDefault(plan.status(), Set.of()).contains(newStatus)) {
            throw new ApiException(HttpStatus.CONFLICT,
                    "Không thể chuyển giáo án từ " + plan.status() + " sang " + newStatus);
        }
        if ("ACTIVE".equals(newStatus)) {
            checkCanActivate(plan);
        }
        planRepository.updateStatus(planId, newStatus);
        userRepository.writeAudit(user.userId(), "TRAINING_PLAN_" + newStatus, "TRAINING_PLAN", planId, "SUCCESS", ip);
        return getOrThrow(planId);
    }

    public TrainingPlanResponse get(int planId) {
        return getOrThrow(planId);
    }

    public List<TrainingPlanResponse.Summary> list(Integer horseId, String status) {
        return planRepository.findSummaries(horseId, status);
    }

    // ---------- helper ----------

    private TrainingPlanResponse getOrThrow(int planId) {
        return planRepository.findById(planId)
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "Không tìm thấy giáo án #" + planId));
    }

    private void checkCanActivate(TrainingPlanResponse plan) {
        HorseResponse horse = horseRepository.findById(plan.horseId())
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "Không tìm thấy ngựa của giáo án"));
        if (!TRAINABLE.contains(horse.status())) {
            throw new ApiException(HttpStatus.CONFLICT,
                    "Ngựa đang ở trạng thái " + horse.status() + ", chưa thể bắt đầu tập");
        }
        if (vitalsRepository.findActiveLock(plan.horseId()).isPresent()) {
            throw new ApiException(HttpStatus.CONFLICT, "Ngựa đang bị khóa huấn luyện");
        }
        if (planRepository.existsActivePlan(plan.horseId(), plan.planId())) {
            throw new ApiException(HttpStatus.CONFLICT, "Ngựa này đã có một giáo án đang chạy");
        }
    }

    /** Gom hết lỗi rồi báo một lần, để frontend tô đỏ được nhiều ô cùng lúc. */
    private void validate(TrainingPlanRequest r) {
        Map<String, String> errors = new LinkedHashMap<>();

        if (!horseRepository.existsById(r.horseId())) {
            errors.put("horseId", "Ngựa không tồn tại");
        }
        if (r.endDate().isBefore(r.startDate())) {
            errors.put("endDate", "Ngày kết thúc phải sau hoặc bằng ngày bắt đầu");
        }

        LocalDate prevEnd = null;
        for (int i = 0; i < r.phases().size(); i++) {
            TrainingPlanRequest.PhaseRequest p = r.phases().get(i);
            String key = "phases[" + i + "]";
            if (p.endDate().isBefore(p.startDate())) {
                errors.putIfAbsent(key + ".endDate", "Ngày kết thúc giai đoạn phải sau hoặc bằng ngày bắt đầu");
            }
            if (p.startDate().isBefore(r.startDate()) || p.endDate().isAfter(r.endDate())) {
                errors.putIfAbsent(key + ".startDate", "Giai đoạn phải nằm trong thời gian của giáo án");
            }
            // Cho phép chung đúng một ngày biên (cuối giai đoạn trước = đầu giai đoạn sau), giống dữ liệu V2.
            if (prevEnd != null && p.startDate().isBefore(prevEnd)) {
                errors.putIfAbsent(key + ".startDate", "Giai đoạn bắt đầu trước khi giai đoạn trước kết thúc");
            }
            prevEnd = p.endDate();
        }

        if (!errors.isEmpty()) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "Dữ liệu không hợp lệ", errors);
        }
    }
}