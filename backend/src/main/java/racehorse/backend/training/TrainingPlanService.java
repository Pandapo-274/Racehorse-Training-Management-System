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

    /**
     * Các bước Head Trainer được làm. COMPLETED là điểm cuối.
     *
     * Từ V34 giáo án phải qua chủ ngựa trước khi chạy:
     *   DRAFT -> PENDING_APPROVAL (Head Trainer gửi) -> APPROVED (chủ ngựa duyệt) -> ACTIVE.
     * PENDING_APPROVAL không có bước nào cho Head Trainer: chỉ chủ ngựa chuyển nó
     * đi (ownerReview), duyệt thì sang APPROVED, từ chối thì về DRAFT kèm lý do.
     * Tạm dừng rồi chạy lại (PAUSED -> ACTIVE) không cần duyệt lại: vẫn là giáo án đã duyệt.
     */
    private static final Map<String, Set<String>> TRANSITIONS = Map.of(
            "DRAFT", Set.of("PENDING_APPROVAL"),
            "PENDING_APPROVAL", Set.of(),
            "APPROVED", Set.of("ACTIVE"),
            "ACTIVE", Set.of("PAUSED", "COMPLETED"),
            "PAUSED", Set.of("ACTIVE", "COMPLETED"),
            "COMPLETED", Set.of());

    /** Chưa từng chạy, nên chưa có buổi tập nào: xoá được mà không mất lịch sử. */
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
        if ("PENDING_APPROVAL".equals(newStatus) && plan.endDate().isBefore(LocalDate.now())) {
            throw new ApiException(HttpStatus.CONFLICT,
                    "This plan ended on " + plan.endDate() + ". Create a new plan instead");
        }
        /* Truyền plan.status() làm trạng thái mong đợi: nếu giữa lúc đọc và
           lúc ghi có người khác đổi, câu UPDATE không trúng dòng nào và cả
           giao dịch bị huỷ, thay vì hai yêu cầu cùng thắng và kết quả tuỳ thứ
           tự ghi. */
        try {
            if (planRepository.updateStatus(planId, plan.status(), newStatus) == 0) {
                throw new ApiException(HttpStatus.CONFLICT,
                        "Someone else has just changed this plan's status");
            }
        } catch (DuplicateKeyException e) {
            /* UX_PLAN_active (V26): hai người cùng kích hoạt hai giáo án của một
               ngựa đúng cùng lúc, cả hai đều qua phép kiểm existsActivePlan. */
            throw new ApiException(HttpStatus.CONFLICT, "This horse already has an active training plan");
        }

        /* COMPLETED là điểm cuối (xem TRANSITIONS), nên những buổi tập còn
           SCHEDULED của giáo án này sẽ không bao giờ chạy được nữa. Để nguyên
           thì lịch của Groom vẫn hiện việc đã chết, và không ai biết vì sao
           bấm Start lại báo lỗi.

           PAUSED thì CỐ Ý không huỷ. Tạm dừng là tạm thời - huỷ buổi tập lúc
           này là phá đúng cái lịch mà người dùng muốn lấy lại khi chạy tiếp.
           Trường hợp PAUSED do start() chặn: nó kiểm trạng thái giáo án nên
           buổi tập vẫn còn đó mà không chạy được, đúng như mong đợi.

           Hai lỗi này hay bị gộp thành một, nhưng chúng khác nhau: một là lỗ
           hổng CƯỠNG CHẾ (buổi tập có được bắt đầu không), một là lỗ hổng
           TRẠNG THÁI (lịch còn hiện việc sẽ không bao giờ xảy ra). Sửa một
           cái vẫn để lại cái kia. */
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

    /**
     * Chủ ngựa duyệt hoặc từ chối giáo án đang chờ (PENDING_APPROVAL).
     *
     * Chủ ngựa trả tiền nuôi và thuê HLV, nên giáo án không chạy khi họ chưa
     * đồng ý: hướng ngựa đi cự ly ngắn mà HLV lên giáo án sức bền thì chủ chặn
     * được ngay ở đây, trước khi có buổi tập nào. Từ chối phải nói lý do để
     * Head Trainer biết sửa gì; giáo án về DRAFT, Head Trainer xoá rồi lập lại
     * hoặc gửi lại.
     */
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

    /**
     * Đọc một giáo án, có kiểm quyền.
     *
     * Nhận AuthenticatedUser chứ không chỉ planId: mở @RequireRole cho
     * HORSE_OWNER mà chỉ lọc ở list() thì GET /api/training-plans/{id} vẫn
     * trả về giáo án của bất kỳ con ngựa nào, chỉ cần đoán số id.
     */
    public TrainingPlanResponse get(int planId, AuthenticatedUser user) {
        TrainingPlanResponse plan = getOrThrow(planId);
        if ("HORSE_OWNER".equals(user.role())) {
            HorseResponse horse = horseRepository.findById(plan.horseId())
                    .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "Horse of this plan not found"));
            if (horse.ownerId() != user.userId()) {
                throw new ApiException(HttpStatus.FORBIDDEN, "This plan belongs to another owner's horse");
            }
        }
        return plan;
    }

    public List<TrainingPlanResponse.Summary> list(Integer horseId, String status,
                                                   AuthenticatedUser user) {
        // Chủ ngựa chỉ thấy giáo án của ngựa mình; lọc trong SQL.
        Integer ownerId = "HORSE_OWNER".equals(user.role()) ? user.userId() : null;
        return planRepository.findSummaries(horseId, status, ownerId);
    }

    /**
     * Xoá một giáo án chưa từng chạy (DRAFT, chờ duyệt, đã duyệt): chọn nhầm
     * ngựa, gõ sai ngày, hay chủ ngựa từ chối.
     *
     * Chưa ACTIVE thì chưa xếp được buổi tập nào, nên xoá không mất lịch sử.
     * Không có đường này thì giáo án sai nằm trong danh sách mãi - qua ngày kết
     * thúc thì cũng không kích hoạt được nữa.
     */
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

    /**
     * Kết thúc mọi giáo án ACTIVE hoặc PAUSED đã qua ngày kết thúc (MissedTaskJob
     * gọi mỗi đêm). PAUSED cũng tính: qua ngày kết thúc thì không kích hoạt lại
     * được nữa (checkCanActivate chặn), nên để PAUSED là treo mãi.
     * @return số giáo án đã kết thúc.
     */
    @Transactional
    public int completeExpiredPlans() {
        return completeExpired(planRepository.findSummaries(null, "ACTIVE", null))
                + completeExpired(planRepository.findSummaries(null, "PAUSED", null));
    }

    /** Dùng lại updateStatus (có điều kiện) và cancelRemainingSessions sẵn có. */
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

    private void checkCanActivate(TrainingPlanResponse plan) {
        HorseResponse horse = horseRepository.findById(plan.horseId())
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "Horse of this plan not found"));
        /* Kiểm lại loại hồ sơ ở đây, không chỉ ở validate() lúc tạo: loại hồ
           sơ của một con ngựa có thể bị đổi sang PEDIGREE sau khi giáo án đã
           được tạo ở trạng thái DRAFT. Kiểm một lần lúc tạo là tin rằng dữ
           liệu không đổi giữa lúc tạo và lúc kích hoạt. */
        String typeProblem = HorsePolicy.trainingProblem(horse.recordType());
        if (typeProblem != null) {
            throw new ApiException(HttpStatus.CONFLICT, typeProblem);
        }
        String withdrawn = HorsePolicy.withdrawnProblem(horse);
        if (withdrawn != null) {
            throw new ApiException(HttpStatus.CONFLICT, withdrawn);
        }
        /* Giáo án đã qua ngày kết thúc thì không xếp được buổi nào nữa
           (buổi tập phải nằm trong giai đoạn và không ở quá khứ). */
        if (plan.endDate().isBefore(LocalDate.now())) {
            throw new ApiException(HttpStatus.CONFLICT,
                    "This plan ended on " + plan.endDate() + ". Create a new plan instead");
        }
        /* Chỉ khoá FULL chặn kích hoạt. HEAVY_WORK_ONLY vẫn cho tập nhẹ, nên
           giáo án vẫn chạy được; từng buổi tập tự kiểm cự ly (TrainingSessionService). */
        var activeLock = vitalsRepository.findActiveLock(plan.horseId());
        if (activeLock.isPresent()) {
            if (!"HEAVY_WORK_ONLY".equals(activeLock.get().lockLevel())) {
                throw new ApiException(HttpStatus.CONFLICT, "Horse is under a full training lock");
            }
        } else if (!HorsePolicy.TRAINABLE_STATUSES.contains(horse.status())) {
            throw new ApiException(HttpStatus.CONFLICT,
                    "Horse status is " + horse.status() + ", cannot start training yet");
        }
        /* Giáo án ACTIVE cũ đã qua ngày kết thúc không còn chặn giáo án mới:
           kết thúc nó trước (huỷ luôn buổi còn treo), rồi mới kiểm. */
        completeExpired(planRepository.findSummaries(plan.horseId(), "ACTIVE", null));
        if (planRepository.existsActivePlan(plan.horseId(), plan.planId())) {
            throw new ApiException(HttpStatus.CONFLICT, "This horse already has an active training plan");
        }
    }

    /** Gom hết lỗi rồi báo một lần, để frontend tô đỏ được nhiều ô cùng lúc. */
    private void validate(TrainingPlanRequest r) {
        Map<String, String> errors = new LinkedHashMap<>();

        /* findById thay cho existsById: cần chính con ngựa để đọc recordType,
           chứ không chỉ cần biết nó có tồn tại. */
        HorseResponse horse = horseRepository.findById(r.horseId()).orElse(null);
        if (horse == null) {
            errors.put("horseId", "Horse does not exist");
        } else {
            /* Hồ sơ PEDIGREE là bản ghi tham chiếu để làm cha mẹ trong phả hệ,
               không phải ngựa của học viện - HorsePolicy đã nói thế từ V6.
               Không kiểm ở đây thì một hồ sơ tổ tiên 25 tuổi, không chuồng,
               vẫn xếp được giáo án. Frontend có ẩn chúng đi, nhưng đó chỉ là
               lớp che: API vẫn nhận. */
            String typeProblem = HorsePolicy.trainingProblem(horse.recordType());
            if (typeProblem != null) {
                errors.put("horseId", typeProblem);
            }
            String withdrawn = HorsePolicy.withdrawnProblem(horse);
            if (withdrawn != null) {
                errors.put("horseId", withdrawn);
            }
        }
        if (r.endDate().isBefore(r.startDate())) {
            errors.put("endDate", "End date must be on or after start date");
        }
        /* Giáo án là kế hoạch cho tương lai; ngày bắt đầu đã qua nghĩa là các
           buổi tập của nó không bao giờ xếp được (TrainingSessionService chặn
           buổi tập trong quá khứ), nên giáo án sinh ra là đã chết. */
        if (r.startDate().isBefore(LocalDate.now())) {
            errors.put("startDate", "Start date cannot be in the past");
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