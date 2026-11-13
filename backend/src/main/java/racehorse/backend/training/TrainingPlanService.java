package racehorse.backend.training;

import java.time.LocalDate;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.springframework.beans.factory.annotation.Autowired;
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
import racehorse.backend.staff.StaffRepository;
import racehorse.backend.staff.StaffRow;

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
    private final StaffRepository staffRepository;

    @Autowired
    public TrainingPlanService(TrainingPlanRepository planRepository, HorseRepository horseRepository,
                               HorseVitalsRepository vitalsRepository, UserRepository userRepository,
                               NotificationService notifications, StaffRepository staffRepository) {
        this.planRepository = planRepository;
        this.horseRepository = horseRepository;
        this.vitalsRepository = vitalsRepository;
        this.userRepository = userRepository;
        this.notifications = notifications;
        this.staffRepository = staffRepository;
    }

    public TrainingPlanService(TrainingPlanRepository planRepository, HorseRepository horseRepository,
                               HorseVitalsRepository vitalsRepository, UserRepository userRepository,
                               NotificationService notifications) {
        this(planRepository, horseRepository, vitalsRepository, userRepository, notifications, null);
    }

    public record TrainerGroomOption(int userId, String fullName, int activePlanCount, boolean eligible) {}

    public List<TrainerGroomOption> listGroomsForPlan(AuthenticatedUser trainer) {
        if (trainer == null) {
            return List.of();
        }
        List<TrainingPlanRepository.GroomOption> grooms = "HEAD_TRAINER".equalsIgnoreCase(trainer.role())
                ? planRepository.findActiveGroomsByTrainer(trainer.userId())
                : planRepository.findAllActiveGrooms();

        // Fallback: nếu danh sách groom trống cho Head Trainer, tra cứu qua staffRepository (khớp màn Manager Staff)
        if (grooms.isEmpty() && staffRepository != null && "HEAD_TRAINER".equalsIgnoreCase(trainer.role())) {
            try {
                var staffGrooms = staffRepository.findAllGrouped().stream()
                        .filter(g -> "GROOM".equalsIgnoreCase(g.roleName()))
                        .flatMap(g -> g.members().stream())
                        .filter(m -> "ACTIVE".equalsIgnoreCase(m.status())
                                && m.trainerId() != null && m.trainerId().intValue() == trainer.userId())
                        .map(m -> new TrainingPlanRepository.GroomOption(m.userId(), m.fullName()))
                        .toList();
                if (!staffGrooms.isEmpty()) {
                    grooms = staffGrooms;
                }
            } catch (Exception ignored) {
            }
        }

        return grooms.stream()
                .map(g -> {
                    int active = planRepository.countActivePlansByGroom(g.userId());
                    return new TrainerGroomOption(g.userId(), g.fullName(), active, active < 2);
                })
                .toList();
    }

    /** Một transaction: nếu chèn giai đoạn thứ 3 lỗi thì giáo án và 2 giai đoạn trước cũng bị hủy. */
    @Transactional
    public TrainingPlanResponse create(TrainingPlanRequest r, AuthenticatedUser user, String ip) {
        validate(r, user);
        int planId = planRepository.insertPlan(r.horseId(), user.userId(), r.groomId(), r.planName().trim(),
                r.startDate(), r.endDate());
        int phaseNo = 1;
        for (TrainingPlanRequest.PhaseRequest p : r.phases()) {
            planRepository.insertPhase(planId, phaseNo++, p);
        }
        userRepository.writeAudit(user.userId(), "TRAINING_PLAN_CREATE", "TRAINING_PLAN", planId, "SUCCESS", ip);
        return getOrThrow(planId);
    }

    /**
     * Cập nhật giáo án: Chỉ cho phép cập nhật Groom phụ trách và nội dung các Phase.
     * Thông tin chính như Horse và khoảng thời gian (startDate, endDate) của giáo án được khóa cố định.
     * Khi cập nhật thành công, tự động gửi thông báo đến chủ ngựa để xem xét lại.
     */
    @Transactional
    public TrainingPlanResponse update(int planId, TrainingPlanRequest.Update r, AuthenticatedUser user, String ip) {
        TrainingPlanResponse existing = get(planId, user);
        if ("COMPLETED".equals(existing.status())) {
            throw new ApiException(HttpStatus.CONFLICT, "Cannot update a completed training plan");
        }

        HorseResponse horse = horseRepository.findById(existing.horseId())
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "Horse of this plan not found"));
        checkTrainerManagesHorse(horse, user, null);

        Map<String, String> errors = new LinkedHashMap<>();

        // 1. Kiểm tra Groom phụ trách
        boolean isSameGroom = r.groomId() != null && r.groomId().equals(existing.groomId());
        if (r.groomId() == null) {
            errors.put("groomId", "Assigned groom is required");
        } else if (!isSameGroom && staffRepository != null) {
            StaffRow groom = staffRepository.findRow(r.groomId()).orElse(null);
            if (groom == null || !"GROOM".equals(groom.roleName()) || !"ACTIVE".equals(groom.status())) {
                errors.put("groomId", "Selected user is not an active groom");
            } else if (groom.trainerId() == null || !groom.trainerId().equals(user.userId())) {
                errors.put("groomId", "Selected groom is not under your management");
            } else {
                int activePlans = planRepository.countActivePlansByGroom(r.groomId());
                if (activePlans >= 2) {
                    errors.put("groomId", "Groom " + groom.fullName()
                            + " is already assigned to 2 active training plans (maximum allowed)");
                }
            }
        }

        // 2. Kiểm tra các Phase: phải nằm trong khoảng thời gian cố định của plan (startDate - endDate)
        LocalDate prevEnd = null;
        for (int i = 0; i < r.phases().size(); i++) {
            TrainingPlanRequest.PhaseRequest p = r.phases().get(i);
            String key = "phases[" + i + "]";
            if (p.endDate().isBefore(p.startDate())) {
                errors.putIfAbsent(key + ".endDate", "Phase " + (i + 1) + " end date must be on or after its start date");
            }
            if (p.startDate().isBefore(existing.startDate()) || p.endDate().isAfter(existing.endDate())) {
                errors.putIfAbsent(key + ".startDate", "Phase " + (i + 1) + " must fall within the plan period ("
                        + existing.startDate() + " to " + existing.endDate() + ")");
            }
            if (prevEnd != null && p.startDate().isBefore(prevEnd)) {
                errors.putIfAbsent(key + ".startDate", "Phase " + (i + 1) + " starts before Phase " + i + " ends");
            }
            prevEnd = p.endDate();
        }

        if (!errors.isEmpty()) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "Invalid data", errors);
        }

        // 3. Cập nhật Groom và tên giáo án
        planRepository.updatePlanGroomAndName(planId, r.groomId(), r.planName());

        // 4. Cập nhật các Phase:
        // 4a. Thu thập ID các phase hiện có và phase được giữ lại
        List<TrainingPlanResponse.Phase> currentPhases = planRepository.findPhases(planId);
        Set<Integer> keptPhaseIds = new HashSet<>();
        for (TrainingPlanRequest.PhaseRequest p : r.phases()) {
            if (p.phaseId() != null) {
                keptPhaseIds.add(p.phaseId());
            }
        }

        // 4b. Xóa những Phase không còn trong danh sách (kiểm tra không vướng FK buổi tập đã tạo)
        for (TrainingPlanResponse.Phase cp : currentPhases) {
            if (!keptPhaseIds.contains(cp.phaseId())) {
                int sessions = planRepository.countSessionsInPhase(cp.phaseId());
                if (sessions > 0) {
                    throw new ApiException(HttpStatus.CONFLICT,
                            "Cannot remove Phase \"" + cp.phaseName() + "\" because " + sessions
                                    + " session(s) are already linked to it. You can adjust its settings instead.");
                }
                planRepository.deletePhase(cp.phaseId(), planId);
            }
        }

        // 4c. Đổi tạm phase_no sang số âm để tránh xung đột UNIQUE (plan_id, phase_no) khi đánh số lại
        planRepository.preparePhasesForReorder(planId);

        // 4d. Cập nhật các phase được giữ lại hoặc chèn phase mới
        int phaseNo = 1;
        for (TrainingPlanRequest.PhaseRequest p : r.phases()) {
            if (p.phaseId() != null && currentPhases.stream().anyMatch(cp -> cp.phaseId() == p.phaseId())) {
                planRepository.updatePhase(p.phaseId(), planId, phaseNo++, p);
            } else {
                planRepository.insertPhase(planId, phaseNo++, p);
            }
        }

        userRepository.writeAudit(user.userId(), "TRAINING_PLAN_UPDATE", "TRAINING_PLAN", planId, "SUCCESS", ip);

        // 5. Gửi thông báo đến chủ ngựa để xem xét lại
        String targetPlanName = (r.planName() != null && !r.planName().isBlank()) ? r.planName().trim() : existing.planName();
        notifications.notifyUser(horse.ownerId(), new Notice(
                "TRAINING_PLAN_UPDATED",
                "Training plan updated - Review needed",
                horse.horseName() + ": Head Trainer updated plan \"" + targetPlanName
                        + "\". Please review the phases and groom assignment.",
                Notice.TRAINING_PLAN, planId));

        // 6. Nếu giáo án đang ACTIVE và groom được đổi mới, gửi thông báo cho Groom được giao
        if ("ACTIVE".equals(existing.status()) && r.groomId() != null && !r.groomId().equals(existing.groomId())) {
            notifications.notifyUser(r.groomId(), new Notice(
                    "TRAINING_PLAN_ACTIVATED",
                    "Assigned to active training plan",
                    horse.horseName() + ": You have been assigned as the Groom for active training plan \""
                            + targetPlanName + "\".",
                    Notice.TRAINING_PLAN, planId));
        }

        return getOrThrow(planId);
    }

    /**
     * Chủ ngựa gửi phản hồi/nhận xét (optional) cho Head Trainer về giáo án.
     */
    @Transactional
    public TrainingPlanResponse ownerFeedback(int planId, String note, AuthenticatedUser owner, String ip) {
        TrainingPlanResponse plan = get(planId, owner);
        String trimmed = note == null ? "" : note.trim();
        if (trimmed.isEmpty()) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "Feedback note cannot be blank");
        }
        if (planRepository.recordOwnerFeedback(planId, trimmed) == 0) {
            throw new ApiException(HttpStatus.NOT_FOUND, "Training plan #" + planId + " not found");
        }

        userRepository.writeAudit(owner.userId(), "TRAINING_PLAN_OWNER_FEEDBACK", "TRAINING_PLAN", planId, "SUCCESS", ip);

        notifications.notifyUser(plan.createdBy(), new Notice(
                "TRAINING_PLAN_OWNER_FEEDBACK",
                "Owner feedback on training plan",
                plan.horseName() + ": Owner provided feedback on plan \"" + plan.planName() + "\": " + clip(trimmed, 150),
                Notice.TRAINING_PLAN, planId));

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
        if ("ACTIVE".equals(newStatus) && plan.groomId() != null) {
            horseRepository.findById(plan.horseId()).ifPresent(horse -> notifications.notifyUser(plan.groomId(),
                    new Notice("TRAINING_PLAN_ACTIVATED", "Assigned to active training plan",
                            horse.horseName() + ": Training plan \"" + plan.planName() + "\" (" + plan.startDate() + " to "
                                    + plan.endDate() + ") has been activated. You are the assigned Groom in charge.",
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
            if (r.horseId() != null && r.horseId() > 0 && planRepository.existsOpenPlan(r.horseId(), 0)) {
                errors.put("horseId", "This horse already has an ongoing training plan. A new plan can only be created once the current plan is completed or cancelled.");
            }
        }

        // V40: Bắt buộc chọn 1 groom đang thuộc quyền quản lý của Head Trainer đó
        // và 1 groom chỉ được tiếp nhận tối đa 2 plan cùng 1 lúc.
        if (r.groomId() == null) {
            errors.put("groomId", "Assigned groom is required");
        } else if (staffRepository != null) {
            StaffRow groom = staffRepository.findRow(r.groomId()).orElse(null);
            if (groom == null || !"GROOM".equals(groom.roleName()) || !"ACTIVE".equals(groom.status())) {
                errors.put("groomId", "Selected user is not an active groom");
            } else if (groom.trainerId() == null || !groom.trainerId().equals(user.userId())) {
                errors.put("groomId", "Selected groom is not under your management");
            } else {
                int activePlans = planRepository.countActivePlansByGroom(r.groomId());
                if (activePlans >= 2) {
                    errors.put("groomId", "Groom " + groom.fullName()
                            + " is already assigned to 2 active training plans (maximum allowed)");
                }
            }
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