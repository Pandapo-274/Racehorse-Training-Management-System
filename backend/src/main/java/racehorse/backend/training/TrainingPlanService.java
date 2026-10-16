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
                    "Cannot change plan status from " + plan.status() + " to " + newStatus);
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
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "Training plan #" + planId + " not found"));
    }

    private void checkCanActivate(TrainingPlanResponse plan) {
        HorseResponse horse = horseRepository.findById(plan.horseId())
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "Horse of this plan not found"));
        if (!TRAINABLE.contains(horse.status())) {
            throw new ApiException(HttpStatus.CONFLICT,
                    "Horse status is " + horse.status() + ", cannot start training yet");
        }
        if (vitalsRepository.findActiveLock(plan.horseId()).isPresent()) {
            throw new ApiException(HttpStatus.CONFLICT, "Horse is under an active training lock");
        }
        if (planRepository.existsActivePlan(plan.horseId(), plan.planId())) {
            throw new ApiException(HttpStatus.CONFLICT, "This horse already has an active training plan");
        }
    }

    /** Gom hết lỗi rồi báo một lần, để frontend tô đỏ được nhiều ô cùng lúc. */
    private void validate(TrainingPlanRequest r) {
        Map<String, String> errors = new LinkedHashMap<>();

        if (!horseRepository.existsById(r.horseId())) {
            errors.put("horseId", "Horse does not exist");
        }
        if (r.endDate().isBefore(r.startDate())) {
            errors.put("endDate", "End date must be on or after start date");
        }

        LocalDate prevEnd = null;
        for (int i = 0; i < r.phases().size(); i++) {
            TrainingPlanRequest.PhaseRequest p = r.phases().get(i);
            String key = "phases[" + i + "]";
            if (p.endDate().isBefore(p.startDate())) {
                errors.putIfAbsent(key + ".endDate", "Phase end date must be on or after phase start date");
            }
            if (p.startDate().isBefore(r.startDate()) || p.endDate().isAfter(r.endDate())) {
                errors.putIfAbsent(key + ".startDate", "Phase must fall within the plan period");
            }
            // Cho phép chung đúng một ngày biên (cuối giai đoạn trước = đầu giai đoạn sau), giống dữ liệu V2.
            if (prevEnd != null && p.startDate().isBefore(prevEnd)) {
                errors.putIfAbsent(key + ".startDate", "Phase starts before the previous phase ends");
            }
            prevEnd = p.endDate();
        }

        if (!errors.isEmpty()) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "Invalid data", errors);
        }
    }
}