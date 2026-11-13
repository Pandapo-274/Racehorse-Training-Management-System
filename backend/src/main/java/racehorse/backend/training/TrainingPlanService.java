package racehorse.backend.training;

import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.springframework.dao.DuplicateKeyException;
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
public class TrainingPlanService {

    private static final Map<String, Set<String>> TRANSITIONS = Map.of(
            "DRAFT", Set.of("PENDING_APPROVAL"),
            "PENDING_APPROVAL", Set.of(),
            "APPROVED", Set.of("ACTIVE"),
            "ACTIVE", Set.of("PAUSED", "COMPLETED"),
            "PAUSED", Set.of("ACTIVE", "COMPLETED"),
            "COMPLETED", Set.of());

    private static final Set<String> DELETABLE = Set.of("DRAFT", "PENDING_APPROVAL", "APPROVED");

    private final TrainingPlanRepository planRepository;
    private final HorseRepository horseRepository;
    private final HorseVitalsRepository vitalsRepository;
    private final UserRepository userRepository;
    private final NotificationService notifications;

    public TrainingPlanService(TrainingPlanRepository planRepository, HorseRepository horseRepository,
                               HorseVitalsRepository vitalsRepository, UserRepository userRepository,
                               NotificationService notifications) {
        this.planRepository = planRepository;
        this.horseRepository = horseRepository;
        this.vitalsRepository = vitalsRepository;
        this.userRepository = userRepository;
        this.notifications = notifications;
    }

    /** Một transaction: nếu chèn giai đoạn thứ 3 lỗi thì giáo án và 2 giai đoạn trước cũng bị hủy. */
    @Transactional
    public TrainingPlanResponse create(TrainingPlanRequest r, AuthenticatedUser user, String ip) {
        validate(r, user);
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
        horseRepository.findById(plan.horseId()).ifPresent(horse -> checkTrainerManagesHorse(horse, user, null));

        if (!TRANSITIONS.getOrDefault(plan.status(), Set.of()).contains(newStatus)) {
            throw new ApiException(HttpStatus.CONFLICT,
                    "Cannot change plan status from " + plan.status() + " to " + newStatus);
        }
        if ("ACTIVE".equals(newStatus)) {
            checkCanActivate(plan);
        }
        if ("PENDING_APPROVAL".equals(newStatus) && plan.endDate().isBefore(LocalDate.now())) {
            throw new ApiException(HttpStatus.CONFLICT,
                    "This plan ended on " + plan.endDate() + ". Create a new plan instead");
        }
        try {
            if (planRepository.updateStatus(planId, plan.status(), newStatus) == 0) {
                throw new ApiException(HttpStatus.CONFLICT,
                        "Someone else has just changed this plan's status");
            }
        } catch (DuplicateKeyException e) {
            throw new ApiException(HttpStatus.CONFLICT, "This horse already has an active training plan");
        }

        int cancelled = 0;
        if ("COMPLETED".equals(newStatus)) {
            cancelled = planRepository.cancelRemainingSessions(planId);
        }

        userRepository.writeAudit(user.userId(), "TRAINING_PLAN_" + newStatus, "TRAINING_PLAN", planId, "SUCCESS", ip);
        if (cancelled > 0) {
            userRepository.writeAudit(user.userId(), "TRAINING_SESSION_CANCEL_BULK",
                    "TRAINING_PLAN", planId, "SUCCESS", ip);
        }
        if ("PENDING_APPROVAL".equals(newStatus)) {
            horseRepository.findById(plan.horseId()).ifPresent(horse -> notifications.notifyUser(horse.ownerId(),
                    new Notice("TRAINING_PLAN_APPROVAL_REQUESTED", "Training plan needs your approval",
                            plan.horseName() + ": \"" + plan.planName() + "\" (" + plan.startDate() + " to "
                                    + plan.endDate() + "). Review it on the Training page.",
                            Notice.TRAINING_PLAN, planId)));
        }
        return getOrThrow(planId);
    }

    @Transactional
    public TrainingPlanResponse ownerReview(int planId, boolean approve, String note,
                                            AuthenticatedUser owner, String ip) {
        TrainingPlanResponse plan = get(planId, owner);
        if (!"PENDING_APPROVAL".equals(plan.status())) {
            throw new ApiException(HttpStatus.CONFLICT,
                    "This plan is not waiting for your approval (currently " + plan.status() + ")");
        }
        String trimmed = note == null || note.isBlank() ? null : note.trim();
        if (!approve && trimmed == null) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "Invalid data",
                    Map.of("note", "Tell the head trainer why you are rejecting this plan"));
        }
        String next = approve ? "APPROVED" : "DRAFT";
        if (planRepository.recordOwnerReview(planId, next, trimmed) == 0) {
            throw new ApiException(HttpStatus.CONFLICT, "Someone else has just changed this plan's status");
        }
        userRepository.writeAudit(owner.userId(), approve ? "TRAINING_PLAN_OWNER_APPROVE" : "TRAINING_PLAN_OWNER_REJECT",
                "TRAINING_PLAN", planId, "SUCCESS", ip);
        notifications.notifyUser(plan.createdBy(), approve
                ? new Notice("TRAINING_PLAN_APPROVED", "Training plan approved",
                        plan.horseName() + ": the owner approved \"" + plan.planName() + "\". You can activate it now.",
                        Notice.TRAINING_PLAN, planId)
                : new Notice("TRAINING_PLAN_REJECTED", "Training plan rejected",
                        clip(plan.horseName() + ": the owner rejected \"" + plan.planName() + "\". " + trimmed, 500),
                        Notice.TRAINING_PLAN, planId));
        return getOrThrow(planId);
    }

    private static String clip(String s, int max) {
        return s.length() <= max ? s : s.substring(0, max - 3) + "...";
    }

    public TrainingPlanResponse get(int planId, AuthenticatedUser user) {
        TrainingPlanResponse plan = getOrThrow(planId);
        if ("HORSE_OWNER".equals(user.role())) {
            HorseResponse horse = horseRepository.findById(plan.horseId())
                    .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "Horse of this plan not found"));
            if (horse.ownerId() != user.userId()) {
                throw new ApiException(HttpStatus.FORBIDDEN, "This plan belongs to another owner's horse");
            }
        } else if ("HEAD_TRAINER".equals(user.role())) {
            HorseResponse horse = horseRepository.findById(plan.horseId())
                    .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "Horse of this plan not found"));
            if (horse.trainerId() == null || !horse.trainerId().equals(user.userId())) {
                throw new ApiException(HttpStatus.FORBIDDEN, "This plan belongs to a horse not assigned to you");
            }
        }
        return plan;
    }

    public List<TrainingPlanResponse.Summary> list(Integer horseId, String status,
                                                   AuthenticatedUser user) {
        Integer ownerId = "HORSE_OWNER".equals(user.role()) ? user.userId() : null;
        List<TrainingPlanResponse.Summary> all = planRepository.findSummaries(horseId, status, ownerId);
        // V37: Head Trainer chỉ thấy giáo án của những ngựa Club Manager giao cho mình.
        if ("HEAD_TRAINER".equals(user.role())) {
            java.util.Set<Integer> mine = horseRepository.findHorseIdsByTrainer(user.userId());
            return all.stream().filter(p -> mine.contains(p.horseId())).toList();
        }
        return all;
    }

    @Transactional
    public void deleteDraft(int planId, AuthenticatedUser user, String ip) {
        TrainingPlanResponse plan = getOrThrow(planId);
        if (!DELETABLE.contains(plan.status())) {
            throw new ApiException(HttpStatus.CONFLICT,
                    "Only a plan that has never been activated can be deleted (this one is " + plan.status() + ")");
        }
        if (planRepository.deleteDraft(planId) == 0) {
            throw new ApiException(HttpStatus.CONFLICT, "Someone else has just changed this plan's status");
        }
        userRepository.writeAudit(user.userId(), "TRAINING_PLAN_DELETE", "TRAINING_PLAN", planId, "SUCCESS", ip);
    }

    @Transactional
    public int completeExpiredPlans() {
        return completeExpired(planRepository.findSummaries(null, "ACTIVE", null))
                + completeExpired(planRepository.findSummaries(null, "PAUSED", null));
    }

    private int completeExpired(List<TrainingPlanResponse.Summary> plans) {
        int done = 0;
        LocalDate today = LocalDate.now();
        for (TrainingPlanResponse.Summary p : plans) {
            if (p.endDate() != null && p.endDate().isBefore(today)
                    && planRepository.updateStatus(p.planId(), p.status(), "COMPLETED") == 1) {
                planRepository.cancelRemainingSessions(p.planId());
                userRepository.writeAudit(null, "TRAINING_PLAN_AUTO_COMPLETED", "TRAINING_PLAN",
                        p.planId(), "SUCCESS", null);
                done++;
            }
        }
        return done;
    }

    // ---------- helper ----------

    private TrainingPlanResponse getOrThrow(int planId) {
        return planRepository.findById(planId)
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "Training plan #" + planId + " not found"));
    }

    private void checkTrainerManagesHorse(HorseResponse horse, AuthenticatedUser user, Map<String, String> errors) {
        if (user == null || !"HEAD_TRAINER".equals(user.role())) return;
        if (horse.trainerId() != null && !horse.trainerId().equals(user.userId())) {
            String msg = "This horse is assigned to Head Trainer "
                    + (horse.trainerName() != null ? horse.trainerName() : "#" + horse.trainerId());
            if (errors != null) errors.put("horseId", msg);
            else throw new ApiException(HttpStatus.FORBIDDEN, msg);
        } else if (horse.trainerId() == null) {
            String msg = "This horse has not been assigned to you by the Club Manager yet";
            if (errors != null) errors.put("horseId", msg);
            else throw new ApiException(HttpStatus.FORBIDDEN, msg);
        }
    }

    private void checkCanActivate(TrainingPlanResponse plan) {
        HorseResponse horse = horseRepository.findById(plan.horseId())
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "Horse of this plan not found"));
        String typeProblem = HorsePolicy.trainingProblem(horse.recordType());
        if (typeProblem != null) {
            throw new ApiException(HttpStatus.CONFLICT, typeProblem);
        }
        String withdrawn = HorsePolicy.withdrawnProblem(horse);
        if (withdrawn != null) {
            throw new ApiException(HttpStatus.CONFLICT, withdrawn);
        }
        if (plan.endDate().isBefore(LocalDate.now())) {
            throw new ApiException(HttpStatus.CONFLICT,
                    "This plan ended on " + plan.endDate() + ". Create a new plan instead");
        }
        var activeLock = vitalsRepository.findActiveLock(plan.horseId());
        if (activeLock.isPresent()) {
            if (!"HEAVY_WORK_ONLY".equals(activeLock.get().lockLevel())) {
                throw new ApiException(HttpStatus.CONFLICT, "Horse is under a full training lock");
            }
        } else if (!HorsePolicy.TRAINABLE_STATUSES.contains(horse.status())) {
            throw new ApiException(HttpStatus.CONFLICT,
                    "Horse status is " + horse.status() + ", cannot start training yet");
        }
        completeExpired(planRepository.findSummaries(plan.horseId(), "ACTIVE", null));
        if (planRepository.existsActivePlan(plan.horseId(), plan.planId())) {
            throw new ApiException(HttpStatus.CONFLICT, "This horse already has an active training plan");
        }
    }

    private void validate(TrainingPlanRequest r, AuthenticatedUser user) {
        Map<String, String> errors = new LinkedHashMap<>();

        HorseResponse horse = horseRepository.findById(r.horseId()).orElse(null);
        if (horse == null) {
            errors.put("horseId", "Horse does not exist");
        } else {
            String typeProblem = HorsePolicy.trainingProblem(horse.recordType());
            if (typeProblem != null) {
                errors.put("horseId", typeProblem);
            }
            String withdrawn = HorsePolicy.withdrawnProblem(horse);
            if (withdrawn != null) {
                errors.put("horseId", withdrawn);
            }
            checkTrainerManagesHorse(horse, user, errors);
        }
        if (r.endDate().isBefore(r.startDate())) {
            errors.put("endDate", "Plan end date must be on or after start date");
        }
        if (r.startDate().isBefore(LocalDate.now())) {
            errors.put("startDate", "Plan start date cannot be in the past. Please choose today or a future date");
        }

        LocalDate prevEnd = null;
        for (int i = 0; i < r.phases().size(); i++) {
            TrainingPlanRequest.PhaseRequest p = r.phases().get(i);
            String key = "phases[" + i + "]";
            if (p.endDate().isBefore(p.startDate())) {
                errors.putIfAbsent(key + ".endDate", "Phase " + (i + 1) + " end date must be on or after its start date");
            }
            if (p.startDate().isBefore(r.startDate()) || p.endDate().isAfter(r.endDate())) {
                errors.putIfAbsent(key + ".startDate", "Phase " + (i + 1) + " must fall within the plan period (" + r.startDate() + " to " + r.endDate() + ")");
            }
            if (prevEnd != null && p.startDate().isBefore(prevEnd)) {
                errors.putIfAbsent(key + ".startDate", "Phase " + (i + 1) + " starts before Phase " + i + " ends");
            }
            prevEnd = p.endDate();
        }

        if (!errors.isEmpty()) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "Invalid data", errors);
        }
    }
}
