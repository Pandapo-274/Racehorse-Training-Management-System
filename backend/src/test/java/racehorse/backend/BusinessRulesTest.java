package racehorse.backend;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.time.DayOfWeek;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.temporal.TemporalAdjusters;
import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.Test;

import racehorse.backend.alert.AlertRepository;
import racehorse.backend.alert.AlertResponse;
import racehorse.backend.alert.AlertService;
import racehorse.backend.alert.ResolveAlertRequest;
import racehorse.backend.alert.SessionContext;
import racehorse.backend.alert.VitalsRequest;
import racehorse.backend.alert.VitalsResult;
import racehorse.backend.auth.TokenBlacklist;
import racehorse.backend.auth.ApiException;
import racehorse.backend.auth.AuthenticatedUser;
import racehorse.backend.auth.UserRepository;
import racehorse.backend.expense.ExpenseRepository;
import racehorse.backend.horse.HorseIdentityRequest;
import racehorse.backend.horse.HorseRepository;
import racehorse.backend.horse.HorseResponse;
import racehorse.backend.horse.HorseService;
import racehorse.backend.horse.HorseUpdateRequest;
import racehorse.backend.horse.HorseVitalsRepository;
import racehorse.backend.horse.HorseVitalsResponse;
import racehorse.backend.incident.IncidentGuard;
import racehorse.backend.incident.IncidentReportResponse;
import racehorse.backend.incident.IncidentRepository;
import racehorse.backend.incident.IncidentService;
import racehorse.backend.incident.StopSessionRequest;
import racehorse.backend.staff.StaffController;
import racehorse.backend.staff.StaffRepository;
import racehorse.backend.staff.StaffRow;
import racehorse.backend.staff.StaffService;
import org.springframework.security.crypto.password.PasswordEncoder;
import racehorse.backend.medical.HealthClearanceRepository;
import racehorse.backend.medical.HealthClearanceService;
import racehorse.backend.medical.InjuryRepository;
import racehorse.backend.medical.MedicalExamRequest;
import racehorse.backend.medical.MedicalRecordRepository;
import racehorse.backend.medical.MedicalRecordRow;
import racehorse.backend.medical.MedicalRecordService;
import racehorse.backend.medical.RecoveryConfirmationRequest;
import racehorse.backend.medical.RecoveryConfirmationResponse;
import racehorse.backend.medical.TrainingLockRepository;
import racehorse.backend.medical.TreatmentRepository;
import racehorse.backend.medical.TreatmentRow;
import racehorse.backend.medical.TreatmentService;
import racehorse.backend.care.CareModels;
import racehorse.backend.care.CareRepository;
import racehorse.backend.care.CareService;
import racehorse.backend.care.MissedTaskJob;
import racehorse.backend.notification.NotificationService;
import racehorse.backend.preventive.PreventiveCareService;
import racehorse.backend.race.RaceModels;
import racehorse.backend.race.RaceRepository;
import racehorse.backend.race.RaceRiskNotifier;
import racehorse.backend.race.RaceService;
import racehorse.backend.session.EvaluateSessionRequest;
import racehorse.backend.session.SessionRepository;
import racehorse.backend.session.SessionResponse;
import racehorse.backend.session.SessionService;
import racehorse.backend.supply.SupplyService;
import racehorse.backend.training.FinishSessionRequest;
import racehorse.backend.training.TrainingPlanRepository;
import racehorse.backend.training.TrainingPlanRequest;
import racehorse.backend.training.TrainingPlanResponse;
import racehorse.backend.training.TrainingPlanService;
import racehorse.backend.training.TrainingSessionRepository;
import racehorse.backend.training.TrainingSessionRequest;
import racehorse.backend.training.TrainingSessionScheduleRequest;
import racehorse.backend.training.TrainingSessionResponse;
import racehorse.backend.training.TrainingSessionService;

/**
 * Luật nghiệp vụ của 3 flow bắt buộc, kiểm bằng repository giả (Mockito):
 * chạy trong vài giây, không cần database. Mỗi test là một luật hội đồng có
 * thể hỏi tới; tên test nói luật đó.
 */
class BusinessRulesTest {

    private static final LocalDate TODAY = LocalDate.now();
    private static final AuthenticatedUser MANAGER = user(1, "CLUB_MANAGER");
    private static final AuthenticatedUser TRAINER = user(2, "HEAD_TRAINER");
    private static final AuthenticatedUser VET = user(3, "VETERINARIAN");
    private static final AuthenticatedUser GROOM = user(5, "GROOM");
    private static final AuthenticatedUser OWNER = user(7, "HORSE_OWNER");

    // ---------------------------------------------------------------- dữ liệu mẫu

    private static AuthenticatedUser user(int id, String role) {
        return new AuthenticatedUser(id, "u" + id, role, "t" + id, Instant.now().plusSeconds(3600), Instant.now());
    }

    private static HorseResponse horse(int id, String status, LocalDate dob, String gender) {
        return new HorseResponse(id, 7, "Owner", null, null, "Horse " + id, "TM-" + id, "TB", gender, dob,
                "Bay", null, "A-" + id, status, LocalDateTime.now(), null, "ACADEMY", null, "ACTIVE", null);
    }

    private static HorseResponse horseWithTrainer(int id, String status, LocalDate dob, String gender,
                                                  Integer trainerId, String trainerName) {
        return new HorseResponse(id, 7, "Owner", null, null, "Horse " + id, "TM-" + id, "TB", gender, dob,
                "Bay", null, "A-" + id, status, LocalDateTime.now(), null, "ACADEMY", null, "ACTIVE", null,
                trainerId, trainerName);
    }

    private static TrainingSessionResponse session(int id, int horseId, int groomId, LocalDateTime when,
                                                   String status, Integer distance) {
        return new TrainingSessionResponse(id, 10, 1, "Base", horseId, "Horse " + horseId, groomId, "Groom " + groomId,
                when, distance, null, null, null, null, null, null, null, null, status, 180, 0, false, null);
    }

    private static SessionResponse evaluationSession(int sessionId, int horseId, int groomId, LocalDateTime when, String status) {
        return new SessionResponse(sessionId, 10, "Base", null, horseId, "Horse " + horseId, groomId, "Groom " + groomId,
                when, 1600, null, 60, 120, 160, 90, null, null, null, 2, status, false, null);
    }

    private static TrainingPlanResponse plan(String status, LocalDate end) {
        return new TrainingPlanResponse(1, 100, "Horse 100", "Plan", end.minusDays(30), end, status, 2, "Trainer",
                LocalDateTime.now(), null, null, List.of());
    }

    private static TrainingPlanService planService(TrainingPlanRepository plans, HorseRepository horses,
                                                   HorseVitalsRepository vitals, NotificationService notifications) {
        return new TrainingPlanService(plans, horses, vitals, mock(UserRepository.class), notifications);
    }

    private static TrainingPlanService planService(TrainingPlanRepository plans, HorseRepository horses,
                                                   HorseVitalsRepository vitals) {
        return planService(plans, horses, vitals, mock(NotificationService.class));
    }

    private static TrainingPlanService planService(TrainingPlanRepository plans) {
        return planService(plans, mock(HorseRepository.class), mock(HorseVitalsRepository.class),
                mock(NotificationService.class));
    }

    private static <T extends Throwable> T expect(Class<T> type, int status, org.junit.jupiter.api.function.Executable run) {
        T ex = assertThrows(type, run);
        if (ex instanceof ApiException api) {
            assertEquals(status, api.getStatus().value(), api.getMessage());
        }
        return ex;
    }

    // ---------------------------------------------------------------- Flow 2: giáo án

    @Test
    void completedPlanCannotBeReactivated() {
        TrainingPlanRepository plans = mock(TrainingPlanRepository.class);
        when(plans.findById(1)).thenReturn(Optional.of(plan("COMPLETED", TODAY.plusDays(10))));
        TrainingPlanService service = planService(plans);

        expect(ApiException.class, 409, () -> service.changeStatus(1, "ACTIVE", TRAINER, "ip"));
        verify(plans, never()).updateStatus(anyInt(), any(), any());
    }

    @Test
    void planPastItsEndDateCannotBeActivated() {
        TrainingPlanRepository plans = mock(TrainingPlanRepository.class);
        HorseRepository horses = mock(HorseRepository.class);
        HorseVitalsRepository vitals = mock(HorseVitalsRepository.class);
        when(plans.findById(1)).thenReturn(Optional.of(plan("APPROVED", TODAY.minusDays(1))));
        when(horses.findById(100)).thenReturn(Optional.of(horseWithTrainer(100, "ELIGIBLE", TODAY.minusYears(4), "COLT", 2, "Trainer 2")));
        when(vitals.findActiveLock(100)).thenReturn(Optional.empty());
        TrainingPlanService service = planService(plans, horses, vitals, mock(NotificationService.class));

        ApiException ex = expect(ApiException.class, 409, () -> service.changeStatus(1, "ACTIVE", TRAINER, "ip"));
        assertTrue(ex.getMessage().contains("ended"), ex.getMessage());
    }

    @Test
    void draftPlanCannotBeActivatedWithoutOwnerApproval() {
        TrainingPlanRepository plans = mock(TrainingPlanRepository.class);
        when(plans.findById(1)).thenReturn(Optional.of(plan("DRAFT", TODAY.plusDays(10))));
        TrainingPlanService service = planService(plans);

        expect(ApiException.class, 409, () -> service.changeStatus(1, "ACTIVE", TRAINER, "ip"));
        verify(plans, never()).updateStatus(anyInt(), any(), any());
    }

    @Test
    void submittingAPlanAsksTheOwnerToApprove() {
        TrainingPlanRepository plans = mock(TrainingPlanRepository.class);
        HorseRepository horses = mock(HorseRepository.class);
        NotificationService notifications = mock(NotificationService.class);
        when(plans.findById(1)).thenReturn(Optional.of(plan("DRAFT", TODAY.plusDays(10))));
        when(plans.updateStatus(1, "DRAFT", "PENDING_APPROVAL")).thenReturn(1);
        when(horses.findById(100)).thenReturn(Optional.of(horseWithTrainer(100, "ELIGIBLE", TODAY.minusYears(4), "COLT", 2, "Trainer 2")));
        TrainingPlanService service = planService(plans, horses, mock(HorseVitalsRepository.class), notifications);

        service.changeStatus(1, "PENDING_APPROVAL", TRAINER, "ip");
        verify(notifications).notifyUser(eq(7), any());   // chủ của ngựa 100
    }

    @Test
    void trainingPlanServiceActivationNotifiesAssignedGroom() {
        TrainingPlanRepository plans = mock(TrainingPlanRepository.class);
        HorseRepository horses = mock(HorseRepository.class);
        NotificationService notifications = mock(NotificationService.class);
        TrainingPlanResponse approvedPlan = new TrainingPlanResponse(1, 100, "Horse 100", 15, "Groom 15", "Plan",
                TODAY.minusDays(5), TODAY.plusDays(25), "APPROVED", 2, "Trainer", LocalDateTime.now(), null, null, List.of());
        when(plans.findById(1)).thenReturn(Optional.of(approvedPlan));
        when(plans.updateStatus(1, "APPROVED", "ACTIVE")).thenReturn(1);
        when(plans.existsActivePlan(100, 1)).thenReturn(false);
        when(horses.findById(100)).thenReturn(Optional.of(horseWithTrainer(100, "ELIGIBLE", TODAY.minusYears(4), "COLT", 2, "Trainer 2")));
        TrainingPlanService service = planService(plans, horses, mock(HorseVitalsRepository.class), notifications);

        service.changeStatus(1, "ACTIVE", TRAINER, "ip");
        verify(notifications).notifyUser(eq(15), argThat(n -> "TRAINING_PLAN_ACTIVATED".equals(n.type())));
    }

    @Test
    void ownerApprovesAPendingPlan() {
        TrainingPlanRepository plans = mock(TrainingPlanRepository.class);
        HorseRepository horses = mock(HorseRepository.class);
        when(plans.findById(1)).thenReturn(Optional.of(plan("PENDING_APPROVAL", TODAY.plusDays(10))));
        when(horses.findById(100)).thenReturn(Optional.of(horse(100, "ELIGIBLE", TODAY.minusYears(4), "COLT")));
        when(plans.recordOwnerReview(1, "APPROVED", null)).thenReturn(1);
        TrainingPlanService service = planService(plans, horses, mock(HorseVitalsRepository.class),
                mock(NotificationService.class));

        service.ownerReview(1, true, null, OWNER, "ip");
        verify(plans).recordOwnerReview(1, "APPROVED", null);
    }

    @Test
    void ownerMustSayWhyWhenRejectingAPlan() {
        TrainingPlanRepository plans = mock(TrainingPlanRepository.class);
        HorseRepository horses = mock(HorseRepository.class);
        when(plans.findById(1)).thenReturn(Optional.of(plan("PENDING_APPROVAL", TODAY.plusDays(10))));
        when(horses.findById(100)).thenReturn(Optional.of(horse(100, "ELIGIBLE", TODAY.minusYears(4), "COLT")));
        TrainingPlanService service = planService(plans, horses, mock(HorseVitalsRepository.class),
                mock(NotificationService.class));

        ApiException ex = expect(ApiException.class, 400, () -> service.ownerReview(1, false, "  ", OWNER, "ip"));
        assertTrue(ex.getFieldErrors().containsKey("note"), ex.getFieldErrors().toString());
        verify(plans, never()).recordOwnerReview(anyInt(), any(), any());
    }

    @Test
    void anotherOwnerCannotReviewThePlan() {
        TrainingPlanRepository plans = mock(TrainingPlanRepository.class);
        HorseRepository horses = mock(HorseRepository.class);
        when(plans.findById(1)).thenReturn(Optional.of(plan("PENDING_APPROVAL", TODAY.plusDays(10))));
        when(horses.findById(100)).thenReturn(Optional.of(horse(100, "ELIGIBLE", TODAY.minusYears(4), "COLT")));
        TrainingPlanService service = planService(plans, horses, mock(HorseVitalsRepository.class),
                mock(NotificationService.class));

        expect(ApiException.class, 403, () -> service.ownerReview(1, true, null, user(8, "HORSE_OWNER"), "ip"));
    }

    @Test
    void anActivatedPlanCannotBeDeleted() {
        TrainingPlanRepository plans = mock(TrainingPlanRepository.class);
        when(plans.findById(1)).thenReturn(Optional.of(plan("ACTIVE", TODAY.plusDays(10))));
        TrainingPlanService service = planService(plans);

        expect(ApiException.class, 409, () -> service.deleteDraft(1, TRAINER, "ip"));
        verify(plans, never()).deleteDraft(anyInt());
    }

    @Test
    void pausedPlanPastItsEndDateIsCompletedOvernight() {
        TrainingPlanRepository plans = mock(TrainingPlanRepository.class);
        when(plans.findSummaries(null, "PAUSED", null)).thenReturn(List.of(new TrainingPlanResponse.Summary(
                1, 100, "Horse 100", "Plan", TODAY.minusDays(40), TODAY.minusDays(1), "PAUSED", 2, null)));
        when(plans.updateStatus(1, "PAUSED", "COMPLETED")).thenReturn(1);
        TrainingPlanService service = planService(plans);

        assertEquals(1, service.completeExpiredPlans());
        verify(plans).cancelRemainingSessions(1);
    }

    // ---------------------------------------------------------------- Flow 2: buổi tập

    private static TrainingSessionService sessionService(TrainingSessionRepository repo, TrainingPlanRepository plans,
                                                         HorseRepository horses, HorseVitalsRepository vitals) {
        return new TrainingSessionService(repo, plans, horses, vitals, mock(UserRepository.class),
                mock(NotificationService.class), mock(IncidentGuard.class), 1200);
    }

    @Test
    void sessionCanOnlyBeStartedOnItsDay() {
        TrainingSessionRepository repo = mock(TrainingSessionRepository.class);
        when(repo.findById(1)).thenReturn(Optional.of(
                session(1, 100, 5, TODAY.plusDays(1).atTime(9, 0), "SCHEDULED", 1600)));
        TrainingSessionService service = sessionService(repo, mock(TrainingPlanRepository.class),
                mock(HorseRepository.class), mock(HorseVitalsRepository.class));

        ApiException ex = expect(ApiException.class, 409, () -> service.start(1, GROOM, "ip"));
        assertTrue(ex.getMessage().contains("only be started on that day"), ex.getMessage());
        verify(repo, never()).markRunning(anyInt());
    }

    /** Dựng một giao buổi tập hợp lệ cho ngựa 100 / Groom 5, lúc 09:00 ngày mai. */
    private static TrainingSessionRepository readyToSchedule(HorseRepository horses, HorseVitalsRepository vitals,
                                                             TrainingPlanRepository plans) {
        return readyToSchedule(horses, vitals, plans, null);
    }

    /** minGap = khoảng nghỉ của giai đoạn (null = mặc định 90 phút). */
    private static TrainingSessionRepository readyToSchedule(HorseRepository horses, HorseVitalsRepository vitals,
                                                             TrainingPlanRepository plans, Integer minGap) {
        TrainingSessionRepository repo = mock(TrainingSessionRepository.class);
        when(repo.findPhaseContext(10)).thenReturn(Optional.of(new TrainingSessionRepository.PhaseContext(
                10, 1, "ACTIVE", 100, 1600, TODAY, TODAY.plusDays(30))));
        when(repo.isActiveGroom(5)).thenReturn(true);
        when(repo.isGroomOfTrainer(5, 2)).thenReturn(true);
        when(horses.findById(100)).thenReturn(Optional.of(horseWithTrainer(100, "ELIGIBLE", TODAY.minusYears(4), "COLT", 2, "Trainer 2")));
        when(vitals.findActiveLock(100)).thenReturn(Optional.empty());
        when(plans.findPhases(1)).thenReturn(List.of(new TrainingPlanResponse.Phase(
                10, 1, "Base", 1600, "Moderate", "TURF", 3, 180, TODAY, TODAY.plusDays(30), minGap, null)));
        return repo;
    }

    @Test
    void horseCannotHaveTwoSessionsOnTheSameDay() {
        HorseRepository horses = mock(HorseRepository.class);
        HorseVitalsRepository vitals = mock(HorseVitalsRepository.class);
        TrainingPlanRepository plans = mock(TrainingPlanRepository.class);
        // V37: 1 ngựa không được có 2 buổi tập trong cùng 1 ngày dù cách nhau 5 tiếng.
        TrainingSessionRepository repo = readyToSchedule(horses, vitals, plans, 20);
        LocalDateTime nine = TODAY.plusDays(1).atTime(9, 0);
        List<TrainingSessionResponse> morningSession = List.of(session(1, 100, 5, nine, "SCHEDULED", 400));
        when(repo.findAll(100, null, null, null, false)).thenReturn(morningSession);
        when(repo.findAll(null, null, 5, null, false)).thenReturn(morningSession);
        TrainingSessionService service = sessionService(repo, plans, horses, vitals);

        ApiException ex = expect(ApiException.class, 409,
                () -> service.create(new TrainingSessionRequest(10, 5, nine.plusHours(5), null), TRAINER, "ip"));
        assertTrue(ex.getMessage().contains("cannot have 2 sessions on the same day"), ex.getMessage());
        verify(repo, never()).insert(anyInt(), anyInt(), anyInt(), any(), any());
    }

    @Test
    void horseCannotHaveTwoSessionsWithOverlappingTimeSlots() {
        HorseRepository horses = mock(HorseRepository.class);
        HorseVitalsRepository vitals = mock(HorseVitalsRepository.class);
        TrainingPlanRepository plans = mock(TrainingPlanRepository.class);
        TrainingSessionRepository repo = readyToSchedule(horses, vitals, plans, 20);
        LocalDateTime nine = TODAY.plusDays(1).atTime(9, 0);
        // Buổi tập 1: 09:00 kéo dài 60 phút (đến 10:00)
        List<TrainingSessionResponse> morningSession = List.of(session(1, 100, 5, nine, "SCHEDULED", 400));
        when(repo.findAll(100, null, null, null, false)).thenReturn(morningSession);
        when(repo.findAll(null, null, 5, null, false)).thenReturn(morningSession);
        TrainingSessionService service = sessionService(repo, plans, horses, vitals);

        // Buổi tập 2 đặt lúc 09:30 kéo dài 60 phút -> trùng khung giờ 09:30 - 10:00
        ApiException ex = expect(ApiException.class, 409,
                () -> service.create(new TrainingSessionRequest(10, 5, nine.plusMinutes(30), null), TRAINER, "ip"));
        assertTrue(ex.getMessage().contains("overlapping session"), ex.getMessage());
        verify(repo, never()).insert(anyInt(), anyInt(), anyInt(), any(), any());
    }

    @Test
    void phaseRestGapIsStillEnforced() {
        HorseRepository horses = mock(HorseRepository.class);
        HorseVitalsRepository vitals = mock(HorseVitalsRepository.class);
        TrainingPlanRepository plans = mock(TrainingPlanRepository.class);
        TrainingSessionRepository repo = readyToSchedule(horses, vitals, plans, 20);
        LocalDateTime nine = TODAY.plusDays(1).atTime(9, 0);
        when(repo.findAll(100, null, null, null, false)).thenReturn(List.of(
                session(1, 100, 5, nine, "SCHEDULED", 400)));
        TrainingSessionService service = sessionService(repo, plans, horses, vitals);

        ApiException ex = expect(ApiException.class, 409, () -> service.create(
                new TrainingSessionRequest(10, 5, nine.plusMinutes(10), null), TRAINER, "ip"));
        assertTrue(ex.getMessage().contains("20 minutes"), ex.getMessage());
    }

    @Test
    void groomCannotBeBookedForTwoHorsesAtTheSameTime() {
        HorseRepository horses = mock(HorseRepository.class);
        HorseVitalsRepository vitals = mock(HorseVitalsRepository.class);
        TrainingPlanRepository plans = mock(TrainingPlanRepository.class);
        TrainingSessionRepository repo = readyToSchedule(horses, vitals, plans);
        LocalDateTime nine = TODAY.plusDays(1).atTime(9, 0);
        when(repo.findAll(100, null, null, null, false)).thenReturn(List.of());
        // Groom 5 đã có buổi với ngựa khác lúc 09:30 cùng ngày.
        when(repo.findAll(null, null, 5, null, false)).thenReturn(List.of(
                session(77, 200, 5, nine.plusMinutes(30), "SCHEDULED", 1600)));
        TrainingSessionService service = sessionService(repo, plans, horses, vitals);

        ApiException ex = expect(ApiException.class, 409,
                () -> service.create(new TrainingSessionRequest(10, 5, nine, null), TRAINER, "ip"));
        assertTrue(ex.getMessage().contains("already handling"), ex.getMessage());
        verify(repo, never()).insert(anyInt(), anyInt(), anyInt(), any(), any());
    }

    @Test
    void phaseSessionsPerWeekIsEnforced() {
        HorseRepository horses = mock(HorseRepository.class);
        HorseVitalsRepository vitals = mock(HorseVitalsRepository.class);
        TrainingPlanRepository plans = mock(TrainingPlanRepository.class);
        TrainingSessionRepository repo = readyToSchedule(horses, vitals, plans);
        LocalDate nextMonday = TODAY.with(TemporalAdjusters.next(DayOfWeek.MONDAY));
        // Giai đoạn cho 3 buổi/tuần; tuần sau đã có 3 buổi ở Thứ 2, Thứ 3, Thứ 4.
        when(repo.findAll(100, null, null, null, false)).thenReturn(List.of(
                session(1, 100, 5, nextMonday.atTime(9, 0), "COMPLETED", 1600),
                session(2, 100, 5, nextMonday.plusDays(1).atTime(9, 0), "SCHEDULED", 1600),
                session(3, 100, 5, nextMonday.plusDays(2).atTime(9, 0), "SCHEDULED", 1600)));
        when(repo.findAll(null, null, 5, null, false)).thenReturn(List.of());
        TrainingSessionService service = sessionService(repo, plans, horses, vitals);

        ApiException ex = expect(ApiException.class, 409,
                () -> service.create(new TrainingSessionRequest(10, 5, nextMonday.plusDays(3).atTime(9, 0), null), TRAINER, "ip"));
        assertTrue(ex.getMessage().contains("3 sessions per week"), ex.getMessage());
    }

    @Test
    void lightWorkLockRefusesHeavySessions() {
        HorseRepository horses = mock(HorseRepository.class);
        HorseVitalsRepository vitals = mock(HorseVitalsRepository.class);
        TrainingPlanRepository plans = mock(TrainingPlanRepository.class);
        TrainingSessionRepository repo = readyToSchedule(horses, vitals, plans);
        when(vitals.findActiveLock(100)).thenReturn(Optional.of(
                new HorseVitalsResponse.ActiveLock("HEAVY_WORK_ONLY", "tendon", LocalDateTime.now())));
        TrainingSessionService service = sessionService(repo, plans, horses, vitals);

        ApiException ex = expect(ApiException.class, 409, () -> service.create(
                new TrainingSessionRequest(10, 5, TODAY.plusDays(1).atTime(9, 0), 2000), TRAINER, "ip"));
        assertTrue(ex.getMessage().contains("light work"), ex.getMessage());
    }

    @Test
    void sessionCannotBeScheduledEarlierToday() {
        HorseRepository horses = mock(HorseRepository.class);
        HorseVitalsRepository vitals = mock(HorseVitalsRepository.class);
        TrainingPlanRepository plans = mock(TrainingPlanRepository.class);
        TrainingSessionRepository repo = readyToSchedule(horses, vitals, plans);
        TrainingSessionService service = sessionService(repo, plans, horses, vitals);

        ApiException ex = expect(ApiException.class, 400, () -> service.create(
                new TrainingSessionRequest(10, 5, LocalDateTime.now().minusMinutes(1), null), TRAINER, "ip"));
        assertTrue(ex.getFieldErrors().containsKey("sessionDate"), ex.getFieldErrors().toString());
        verify(repo, never()).insert(anyInt(), anyInt(), anyInt(), any(), any());
    }

    @Test
    void groomCanBeChangedOnASessionWhoseTimeHasPassedToday() {
        HorseRepository horses = mock(HorseRepository.class);
        HorseVitalsRepository vitals = mock(HorseVitalsRepository.class);
        TrainingPlanRepository plans = mock(TrainingPlanRepository.class);
        TrainingSessionRepository repo = readyToSchedule(horses, vitals, plans);
        LocalDateTime earlyToday = TODAY.atStartOfDay();
        when(repo.findById(1)).thenReturn(Optional.of(session(1, 100, 5, earlyToday, "SCHEDULED", 1600)));
        when(repo.isActiveGroom(6)).thenReturn(true);
        when(repo.isGroomOfTrainer(6, 2)).thenReturn(true);
        when(repo.updateSchedule(1, 6, earlyToday)).thenReturn(1);
        TrainingSessionService service = sessionService(repo, plans, horses, vitals);

        service.reschedule(1, new TrainingSessionScheduleRequest(6, earlyToday), TRAINER, "ip");
        verify(repo).updateSchedule(1, 6, earlyToday);
    }

    @Test
    void groomFinishesTheirOwnRunningSession() {
        TrainingSessionRepository repo = mock(TrainingSessionRepository.class);
        // Buổi tập đã hoàn thành đủ thời lượng đặt lịch (bắt đầu 70 phút trước, duration 60 phút)
        when(repo.findById(1)).thenReturn(Optional.of(session(1, 100, 5, LocalDateTime.now().minusMinutes(70), "RUNNING", 1600)));
        when(repo.markFinished(1)).thenReturn(1);
        TrainingSessionService service = sessionService(repo, mock(TrainingPlanRepository.class),
                mock(HorseRepository.class), mock(HorseVitalsRepository.class));

        service.finish(1, GROOM, "ip");
        verify(repo).markFinished(1);
    }

    @Test
    void groomAttemptingToFinishBeforePlannedDurationMustEndEarlyWithReasonAndMetrics() {
        TrainingSessionRepository repo = mock(TrainingSessionRepository.class);
        // Buổi tập mới chạy được 10 phút trên tổng thời lượng 60 phút -> tính là kết thúc sớm
        when(repo.findById(1)).thenReturn(Optional.of(session(1, 100, 5, LocalDateTime.now().minusMinutes(10), "RUNNING", 1600)));
        TrainingSessionService service = sessionService(repo, mock(TrainingPlanRepository.class),
                mock(HorseRepository.class), mock(HorseVitalsRepository.class));

        ApiException ex = expect(ApiException.class, 409, () -> service.finish(1, GROOM, "ip"));
        assertTrue(ex.getMessage().contains("heart rate and speed at least once"), ex.getMessage());
        verify(repo, never()).markFinished(anyInt());
    }

    @Test
    void groomCannotFinishAnotherGroomsSession() {
        TrainingSessionRepository repo = mock(TrainingSessionRepository.class);
        when(repo.findById(1)).thenReturn(Optional.of(session(1, 100, 6, LocalDateTime.now(), "RUNNING", 1600)));
        TrainingSessionService service = sessionService(repo, mock(TrainingPlanRepository.class),
                mock(HorseRepository.class), mock(HorseVitalsRepository.class));

        expect(ApiException.class, 403, () -> service.finish(1, GROOM, "ip"));
        verify(repo, never()).markFinished(anyInt());
    }

    @Test
    void onlyARunningSessionCanBeFinished() {
        TrainingSessionRepository repo = mock(TrainingSessionRepository.class);
        when(repo.findById(1)).thenReturn(Optional.of(session(1, 100, 5, LocalDateTime.now(), "SCHEDULED", 1600)));
        TrainingSessionService service = sessionService(repo, mock(TrainingPlanRepository.class),
                mock(HorseRepository.class), mock(HorseVitalsRepository.class));

        expect(ApiException.class, 409, () -> service.finish(1, GROOM, "ip"));
        verify(repo, never()).markFinished(anyInt());
    }

    @Test
    void sessionDurationCannotCrossIntoTheNextDay() {
        HorseRepository horses = mock(HorseRepository.class);
        HorseVitalsRepository vitals = mock(HorseVitalsRepository.class);
        TrainingPlanRepository plans = mock(TrainingPlanRepository.class);
        TrainingSessionRepository repo = readyToSchedule(horses, vitals, plans);
        TrainingSessionService service = sessionService(repo, plans, horses, vitals);

        // Đặt lúc 22:00 kéo dài 180 phút -> sang 01:00 ngày hôm sau -> bị chặn
        ApiException ex = expect(ApiException.class, 400, () -> service.create(
                new TrainingSessionRequest(10, 5, TODAY.plusDays(1).atTime(22, 0), 1600, 180), TRAINER, "ip"));
        assertTrue(ex.getFieldErrors().containsKey("plannedDuration"), ex.getFieldErrors().toString());
    }

    @Test
    void groomCannotEndSessionEarlyWithoutReportingBothHeartRateAndSpeed() {
        TrainingSessionRepository repo = mock(TrainingSessionRepository.class);
        // Mới có peakHeartRate = 165, chưa có peakVelocity (null)
        TrainingSessionResponse runningOnlyHr = new TrainingSessionResponse(
                1, 10, 1, "Base", 100, "Horse 100", 5, "Groom 5",
                LocalDateTime.now(), 1600, null, null, null, 165, null, null, null, null,
                "RUNNING", 180, 0, false, null);
        when(repo.findById(1)).thenReturn(Optional.of(runningOnlyHr));
        TrainingSessionService service = sessionService(repo, mock(TrainingPlanRepository.class),
                mock(HorseRepository.class), mock(HorseVitalsRepository.class));

        ApiException ex = expect(ApiException.class, 409,
                () -> service.finish(1, new FinishSessionRequest(true, "TARGET_REACHED"), GROOM, "ip"));
        assertTrue(ex.getMessage().contains("heart rate and speed at least once"), ex.getMessage());
        verify(repo, never()).markStoppedEarly(anyInt(), any());
    }

    @Test
    void groomEndsSessionEarlyAfterReportingBothHeartRateAndSpeed() {
        TrainingSessionRepository repo = mock(TrainingSessionRepository.class);
        TrainingSessionResponse runningWithBoth = new TrainingSessionResponse(
                1, 10, 1, "Base", 100, "Horse 100", 5, "Groom 5",
                LocalDateTime.now(), 1600, null, null, null, 165, null, new BigDecimal("15.2"), null, null,
                "RUNNING", 180, 0, false, null);
        when(repo.findById(1)).thenReturn(Optional.of(runningWithBoth));
        when(repo.markStoppedEarly(eq(1), any())).thenReturn(1);
        TrainingSessionService service = sessionService(repo, mock(TrainingPlanRepository.class),
                mock(HorseRepository.class), mock(HorseVitalsRepository.class));

        service.finish(1, new FinishSessionRequest(true, "TARGET_REACHED"), GROOM, "ip");
        verify(repo).markStoppedEarly(eq(1), argThat(r -> r.contains("Target fitness reached")));
    }

    // ---------------------------------------------------------------- Flow 2: đánh giá buổi tập

    private static SessionResponse evaluated(String status) {
        return new SessionResponse(1, 10, "Base", 180, 100, "Horse 100", 5, "Groom 5", LocalDateTime.now(), 1600,
                null, null, null, null, null, null, null, null, null, status, false, null);
    }

    private static EvaluateSessionRequest evaluation(String status) {
        return new EvaluateSessionRequest(1500, 30, 150, 190, 110, null, "GOOD", null, status);
    }

    @Test
    void stoppedSessionCannotBeEvaluatedAsCompleted() {
        SessionRepository repo = mock(SessionRepository.class);
        when(repo.findById(1)).thenReturn(Optional.of(evaluated("STOPPED_EARLY")));
        SessionService service = new SessionService(repo, mock(UserRepository.class));

        ApiException ex = expect(ApiException.class, 400,
                () -> service.evaluate(TRAINER, 1, evaluation("COMPLETED"), "ip"));
        assertTrue(ex.getFieldErrors().containsKey("status"), ex.getFieldErrors().toString());
        verify(repo, never()).saveEvaluation(anyInt(), any(), any(), any(), anyInt());
    }

    @Test
    void evaluationDoesNotOverwriteASessionStoppedMeanwhile() {
        SessionRepository repo = mock(SessionRepository.class);
        when(repo.findById(1)).thenReturn(Optional.of(evaluated("RUNNING")));
        // Groom vừa bấm Stop: câu UPDATE có điều kiện RUNNING không trúng dòng nào.
        when(repo.saveEvaluation(eq(1), any(), eq("RUNNING"), eq("COMPLETED"), eq(2))).thenReturn(0);
        SessionService service = new SessionService(repo, mock(UserRepository.class));

        expect(ApiException.class, 409, () -> service.evaluate(TRAINER, 1, evaluation(null), "ip"));
    }

    @Test
    void overdueSessionReviewIsAutoArchivedWithPreliminaryReportToOwner() {
        SessionRepository repo = mock(SessionRepository.class);
        NotificationService notifications = mock(NotificationService.class);
        SessionResponse overdue = new SessionResponse(
                15, 10, "Base", 180, 100, "Horse 100", 5, "Groom 5",
                LocalDateTime.now().minusDays(3), 1600, null, null, null, 172, null, new BigDecimal("16.4"),
                null, null, null, "COMPLETED", false, null,
                60, LocalDateTime.now().minusHours(50), null, "PENDING", null);
        when(repo.findOverduePendingReviews(any())).thenReturn(List.of(overdue));
        when(repo.markAutoArchived(eq(15), eq(1600), eq(60), eq(172), eq("FAIR"), any())).thenReturn(1);
        when(repo.findOwnerOfSession(15)).thenReturn(Optional.of(7));

        SessionService service = new SessionService(repo, mock(UserRepository.class), notifications, null);
        assertEquals(1, service.processOverdueReviews());

        verify(repo).markAutoArchived(eq(15), eq(1600), eq(60), eq(172), eq("FAIR"),
                argThat(r -> r.contains("AUTOMATED PRELIMINARY REPORT")
                        && r.contains("Peak HR = 172 bpm")
                        && r.contains("Peak Speed = 16.4 m/s")));
        verify(notifications).notifyUser(eq(7),
                argThat(n -> "SESSION_AUTO_PRELIMINARY_REPORT".equals(n.type())
                        && n.message().contains("Peak HR 172 bpm")
                        && n.message().contains("Peak Speed 16.4 m/s")));
    }

    @Test
    void overdueStoppedEarlySessionReviewIsAutoArchivedWithReasonAndMetrics() {
        SessionRepository repo = mock(SessionRepository.class);
        NotificationService notifications = mock(NotificationService.class);
        SessionResponse overdueStopped = new SessionResponse(
                16, 10, "Base", 180, 100, "Horse 100", 5, "Groom 5",
                LocalDateTime.now().minusDays(3), 1200, 1000, 35, 150, 168, 110, new BigDecimal("15.8"),
                null, null, null, "STOPPED_EARLY", false, null,
                60, LocalDateTime.now().minusHours(52), "Target fitness reached", "PENDING", null);
        when(repo.findOverduePendingReviews(any())).thenReturn(List.of(overdueStopped));
        when(repo.markAutoArchived(eq(16), eq(1000), eq(35), eq(150), eq("FAIR"), any())).thenReturn(1);
        when(repo.findOwnerOfSession(16)).thenReturn(Optional.of(7));

        SessionService service = new SessionService(repo, mock(UserRepository.class), notifications, null);
        assertEquals(1, service.processOverdueReviews());

        verify(repo).markAutoArchived(eq(16), eq(1000), eq(35), eq(150), eq("FAIR"),
                argThat(r -> r.contains("Early stop: Target fitness reached")
                        && r.contains("Peak HR = 168 bpm")
                        && r.contains("Peak Speed = 15.8 m/s")));
    }

    // ---------------------------------------------------------------- V37: Kiểm tra bổ sung 4 nghiệp vụ huấn luyện (R1-R4)

    @Test
    void clubManagerAssignsGroomToHeadTrainerSuccessfully() {
        StaffRepository staffRepo = mock(StaffRepository.class);
        NotificationService notifications = mock(NotificationService.class);
        StaffRow groom = new StaffRow(5, "groom5", "Groom 5", "g5@test.com", "0912345678", "GROOM", "Groom", "ACTIVE", null, LocalDateTime.now(), 0);
        StaffRow trainer = new StaffRow(2, "trainer2", "Trainer 2", "t2@test.com", "0912345679", "HEAD_TRAINER", "Head Trainer", "ACTIVE", null, LocalDateTime.now(), 0);
        when(staffRepo.findRow(5)).thenReturn(Optional.of(groom));
        when(staffRepo.findRow(2)).thenReturn(Optional.of(trainer));
        when(staffRepo.countActiveGroomsOfTrainer(2, 5)).thenReturn(2);

        StaffService service = new StaffService(mock(UserRepository.class), staffRepo,
                mock(PasswordEncoder.class), mock(TokenBlacklist.class), notifications);

        service.assignTrainer(5, 2, MANAGER, "ip");
        verify(staffRepo).updateTrainer(5, 2);
        verify(notifications).notifyUser(eq(2), argThat(n -> "GROOM_ASSIGNED".equals(n.type())));
        verify(notifications).notifyUser(eq(5), argThat(n -> "TRAINER_ASSIGNED".equals(n.type())));
    }

    @Test
    void clubManagerAssigningFourthGroomToHeadTrainerIsRejected() {
        StaffRepository staffRepo = mock(StaffRepository.class);
        StaffRow groom = new StaffRow(5, "groom5", "Groom 5", "g5@test.com", "0912345678", "GROOM", "Groom", "ACTIVE", null, LocalDateTime.now(), 0);
        StaffRow trainer = new StaffRow(2, "trainer2", "Trainer 2", "t2@test.com", "0912345679", "HEAD_TRAINER", "Head Trainer", "ACTIVE", null, LocalDateTime.now(), 0);
        when(staffRepo.findRow(5)).thenReturn(Optional.of(groom));
        when(staffRepo.findRow(2)).thenReturn(Optional.of(trainer));
        when(staffRepo.countActiveGroomsOfTrainer(2, 5)).thenReturn(3);

        StaffService service = new StaffService(mock(UserRepository.class), staffRepo,
                mock(PasswordEncoder.class), mock(TokenBlacklist.class), mock(NotificationService.class));

        ApiException ex = expect(ApiException.class, 409, () -> service.assignTrainer(5, 2, MANAGER, "ip"));
        assertTrue(ex.getMessage().contains("maximum of 3 active grooms"), ex.getMessage());
        verify(staffRepo, never()).updateTrainer(anyInt(), any());
    }

    @Test
    void headTrainerCannotSelfAssignGrooms() {
        StaffRepository staffRepo = mock(StaffRepository.class);
        StaffService service = new StaffService(mock(UserRepository.class), staffRepo,
                mock(PasswordEncoder.class), mock(TokenBlacklist.class), mock(NotificationService.class));

        ApiException ex = expect(ApiException.class, 403, () -> service.assignTrainer(5, 2, TRAINER, "ip"));
        assertTrue(ex.getMessage().contains("Only Club Manager can assign Grooms"), ex.getMessage());
        verify(staffRepo, never()).updateTrainer(anyInt(), any());
    }

    @Test
    void clubManagerRegistersGroomWithHeadTrainerSuccessfully() {
        StaffRepository staffRepo = mock(StaffRepository.class);
        UserRepository users = mock(UserRepository.class);
        PasswordEncoder encoder = mock(PasswordEncoder.class);
        StaffRow trainer = new StaffRow(2, "trainer2", "Trainer 2", "t2@test.com", "0912345679", "HEAD_TRAINER", "Head Trainer", "ACTIVE", null, LocalDateTime.now(), 0);
        when(users.findRoleId("GROOM")).thenReturn(Optional.of(4));
        when(users.existsByUsername("groom99")).thenReturn(false);
        when(users.existsByEmail("g99@test.com")).thenReturn(false);
        when(users.insert(eq(4), eq("groom99"), any(), eq("Groom 99"), eq("g99@test.com"), any())).thenReturn(99);
        when(staffRepo.findRow(2)).thenReturn(Optional.of(trainer));
        when(staffRepo.countActiveGroomsOfTrainer(2, null)).thenReturn(2);
        StaffRow createdGroom = new StaffRow(99, "groom99", "Groom 99", "g99@test.com", "0912345678", "GROOM", "Groom", "ACTIVE", null, LocalDateTime.now(), 0);
        when(staffRepo.findRow(99)).thenReturn(Optional.of(createdGroom));

        NotificationService notifications = mock(NotificationService.class);
        StaffService service = new StaffService(users, staffRepo, encoder, mock(TokenBlacklist.class), notifications);
        StaffController.CreateRequest req = new StaffController.CreateRequest(
                "groom99", "Password123", "Groom 99", "g99@test.com", "0912345678", "GROOM", 2, null);

        StaffRow result = service.create(req, MANAGER, "ip");
        assertNotNull(result);
        verify(staffRepo).updateTrainer(99, 2);
        verify(notifications).notifyUser(eq(2), argThat(n -> "GROOM_ASSIGNED".equals(n.type())));
        verify(notifications).notifyUser(eq(99), argThat(n -> "TRAINER_ASSIGNED".equals(n.type())));
    }

    @Test
    void clubManagerRegisteringGroomWithHeadTrainerAtLimitIsRejected() {
        StaffRepository staffRepo = mock(StaffRepository.class);
        UserRepository users = mock(UserRepository.class);
        StaffRow trainer = new StaffRow(2, "trainer2", "Trainer 2", "t2@test.com", "0912345679", "HEAD_TRAINER", "Head Trainer", "ACTIVE", null, LocalDateTime.now(), 0);
        when(users.findRoleId("GROOM")).thenReturn(Optional.of(4));
        when(staffRepo.findRow(2)).thenReturn(Optional.of(trainer));
        when(staffRepo.countActiveGroomsOfTrainer(2, null)).thenReturn(3);

        StaffService service = new StaffService(users, staffRepo, mock(PasswordEncoder.class), mock(TokenBlacklist.class), mock(NotificationService.class));
        StaffController.CreateRequest req = new StaffController.CreateRequest(
                "groom99", "Password123", "Groom 99", "g99@test.com", "0912345678", "GROOM", 2, null);

        ApiException ex = expect(ApiException.class, 409, () -> service.create(req, MANAGER, "ip"));
        assertTrue(ex.getMessage().contains("maximum of 3 active grooms"), ex.getMessage());
        verify(users, never()).insert(anyInt(), any(), any(), any(), any(), any());
    }

    @Test
    void nonManagerCannotRegisterStaff() {
        StaffService service = new StaffService(mock(UserRepository.class), mock(StaffRepository.class),
                mock(PasswordEncoder.class), mock(TokenBlacklist.class), mock(NotificationService.class));
        StaffController.CreateRequest req = new StaffController.CreateRequest(
                "groom99", "Password123", "Groom 99", "g99@test.com", "0912345678", "GROOM", 2, null);

        ApiException ex = expect(ApiException.class, 403, () -> service.create(req, TRAINER, "ip"));
        assertTrue(ex.getMessage().contains("Only Club Manager can create staff"), ex.getMessage());
    }

    @Test
    void nonManagerCannotChangeStaffStatus() {
        StaffService service = new StaffService(mock(UserRepository.class), mock(StaffRepository.class),
                mock(PasswordEncoder.class), mock(TokenBlacklist.class), mock(NotificationService.class));

        ApiException ex = expect(ApiException.class, 403, () -> service.setStatus(5, "SUSPENDED", TRAINER, "ip"));
        assertTrue(ex.getMessage().contains("Only Club Manager can change staff account status"), ex.getMessage());
    }

    @Test
    void nonManagerCannotChangeStaffRole() {
        StaffService service = new StaffService(mock(UserRepository.class), mock(StaffRepository.class),
                mock(PasswordEncoder.class), mock(TokenBlacklist.class), mock(NotificationService.class));

        ApiException ex = expect(ApiException.class, 403, () -> service.changeRole(5, "HEAD_TRAINER", TRAINER, "ip"));
        assertTrue(ex.getMessage().contains("Only Club Manager can change staff roles"), ex.getMessage());
    }

    @Test
    void clubManagerUpdatesHeadTrainerHorseQuotaSuccessfully() {
        StaffRepository staffRepo = mock(StaffRepository.class);
        UserRepository users = mock(UserRepository.class);
        StaffRow trainer = new StaffRow(2, "trainer2", "Trainer 2", "t2@test.com", "0912345679", "HEAD_TRAINER", "Head Trainer", "ACTIVE", null, LocalDateTime.now(), 0, null, null, 2, 4, 10);
        when(staffRepo.findRow(2)).thenReturn(Optional.of(trainer));

        StaffService service = new StaffService(users, staffRepo, mock(PasswordEncoder.class), mock(TokenBlacklist.class), mock(NotificationService.class));
        service.updateMaxHorses(2, 20, MANAGER, "ip");

        verify(staffRepo).updateMaxHorses(2, 20);
        verify(users).writeAudit(eq(1), eq("STAFF_MAX_HORSES:20"), eq("APP_USER"), eq(2), eq("SUCCESS"), eq("ip"));
    }

    @Test
    void clubManagerLoweringHorseQuotaBelowCurrentlyManagedCountIsRejected() {
        StaffRepository staffRepo = mock(StaffRepository.class);
        StaffRow trainer = new StaffRow(2, "trainer2", "Trainer 2", "t2@test.com", "0912345679", "HEAD_TRAINER", "Head Trainer", "ACTIVE", null, LocalDateTime.now(), 0, null, null, 2, 8, 10);
        when(staffRepo.findRow(2)).thenReturn(Optional.of(trainer));

        StaffService service = new StaffService(mock(UserRepository.class), staffRepo, mock(PasswordEncoder.class), mock(TokenBlacklist.class), mock(NotificationService.class));
        ApiException ex = expect(ApiException.class, 409, () -> service.updateMaxHorses(2, 5, MANAGER, "ip"));
        assertTrue(ex.getMessage().contains("currently managing 8 horses"), ex.getMessage());
        verify(staffRepo, never()).updateMaxHorses(anyInt(), anyInt());
    }

    @Test
    void nonManagerCannotUpdateTrainerHorseQuota() {
        StaffService service = new StaffService(mock(UserRepository.class), mock(StaffRepository.class),
                mock(PasswordEncoder.class), mock(TokenBlacklist.class), mock(NotificationService.class));
        ApiException ex = expect(ApiException.class, 403, () -> service.updateMaxHorses(2, 20, TRAINER, "ip"));
        assertTrue(ex.getMessage().contains("Only Club Manager can update horse quota"), ex.getMessage());
    }

    @Test
    void headTrainerReviewsArchivedSessionFromArchive() {
        SessionRepository repo = mock(SessionRepository.class);
        UserRepository users = mock(UserRepository.class);
        NotificationService notifications = mock(NotificationService.class);
        SessionResponse archived = new SessionResponse(
                15, 10, "Base", 180, 100, "Horse 100", 5, "Groom 5",
                LocalDateTime.now().minusDays(3), 1600, null, null, null, 172, null, new BigDecimal("16.4"),
                null, null, null, "COMPLETED", false, null,
                60, LocalDateTime.now().minusHours(50), null, "ARCHIVED_AUTO", null);
        when(repo.findById(15)).thenReturn(Optional.of(archived));
        when(repo.saveEvaluation(eq(15), any(), eq("COMPLETED"), eq("COMPLETED"), eq(2))).thenReturn(1);
        when(repo.findOwnerOfSession(15)).thenReturn(Optional.of(7));

        SessionService service = new SessionService(repo, users, notifications, null);
        EvaluateSessionRequest evalReq = new EvaluateSessionRequest(1600, 60, 140, 175, 110, new BigDecimal("16.4"), "EXCELLENT", "Verified after auto-archive", "COMPLETED");
        service.evaluate(TRAINER, 15, evalReq, "ip");

        verify(repo).saveEvaluation(eq(15), any(), eq("COMPLETED"), eq("COMPLETED"), eq(2));
        verify(users).writeAudit(eq(2), eq("EVALUATE_ARCHIVED_SESSION"), eq("TRAINING_SESSION"), eq(15), eq("SUCCESS"), eq("ip"));
        verify(notifications).notifyUser(eq(7), argThat(n -> n.title().contains("updated from archive")));
    }

    @Test
    void sessionDurationNotDivisibleByFiveIsRejected() {
        HorseRepository horses = mock(HorseRepository.class);
        HorseVitalsRepository vitals = mock(HorseVitalsRepository.class);
        TrainingPlanRepository plans = mock(TrainingPlanRepository.class);
        TrainingSessionRepository repo = readyToSchedule(horses, vitals, plans);
        TrainingSessionService service = sessionService(repo, plans, horses, vitals);

        ApiException ex = expect(ApiException.class, 400, () -> service.create(
                new TrainingSessionRequest(10, 5, TODAY.plusDays(1).atTime(9, 0), 1600, 123), TRAINER, "ip"));
        assertTrue(ex.getFieldErrors().containsKey("plannedDuration"), ex.getFieldErrors().toString());
        assertTrue(ex.getFieldErrors().get("plannedDuration").contains("divisible by 5"), ex.getFieldErrors().toString());
    }

    @Test
    void sessionPlannedDistanceExceedingFiveThousandMetersIsRejected() {
        HorseRepository horses = mock(HorseRepository.class);
        HorseVitalsRepository vitals = mock(HorseVitalsRepository.class);
        TrainingPlanRepository plans = mock(TrainingPlanRepository.class);
        TrainingSessionRepository repo = readyToSchedule(horses, vitals, plans);
        TrainingSessionService service = sessionService(repo, plans, horses, vitals);

        ApiException ex = expect(ApiException.class, 400, () -> service.create(
                new TrainingSessionRequest(10, 5, TODAY.plusDays(1).atTime(9, 0), 5001, 60), TRAINER, "ip"));
        assertTrue(ex.getFieldErrors().containsKey("plannedDistance"), ex.getFieldErrors().toString());
        assertTrue(ex.getFieldErrors().get("plannedDistance").contains("5,000 meters"), ex.getFieldErrors().toString());
    }

    @Test
    void sessionDurationExceedingTwelveHoursIsRejected() {
        HorseRepository horses = mock(HorseRepository.class);
        HorseVitalsRepository vitals = mock(HorseVitalsRepository.class);
        TrainingPlanRepository plans = mock(TrainingPlanRepository.class);
        TrainingSessionRepository repo = readyToSchedule(horses, vitals, plans);
        TrainingSessionService service = sessionService(repo, plans, horses, vitals);

        ApiException ex = expect(ApiException.class, 400, () -> service.create(
                new TrainingSessionRequest(10, 5, TODAY.plusDays(1).atTime(9, 0), 1600, 721), TRAINER, "ip"));
        assertTrue(ex.getFieldErrors().containsKey("plannedDuration"), ex.getFieldErrors().toString());
        assertTrue(ex.getFieldErrors().get("plannedDuration").contains("max 12 hours"), ex.getFieldErrors().toString());
    }

    @Test
    void sessionWithNullPlannedDurationIsRejectedWhenMandatory() {
        HorseRepository horses = mock(HorseRepository.class);
        HorseVitalsRepository vitals = mock(HorseVitalsRepository.class);
        TrainingPlanRepository plans = mock(TrainingPlanRepository.class);
        TrainingSessionRepository repo = readyToSchedule(horses, vitals, plans);
        TrainingSessionService service = sessionService(repo, plans, horses, vitals);

        ApiException ex = expect(ApiException.class, 400, () -> service.create(
                new TrainingSessionRequest(10, 5, TODAY.plusDays(1).atTime(9, 0), 1600, null), TRAINER, "ip"));
        assertTrue(ex.getFieldErrors().containsKey("plannedDuration"), ex.getFieldErrors().toString());
        assertTrue(ex.getFieldErrors().get("plannedDuration").contains("Planned duration is required"), ex.getFieldErrors().toString());
    }

    @Test
    void sessionEndingNextDayIsRejected() {
        HorseRepository horses = mock(HorseRepository.class);
        HorseVitalsRepository vitals = mock(HorseVitalsRepository.class);
        TrainingPlanRepository plans = mock(TrainingPlanRepository.class);
        TrainingSessionRepository repo = readyToSchedule(horses, vitals, plans);
        TrainingSessionService service = sessionService(repo, plans, horses, vitals);

        // Đặt lúc 23:30 thời lượng 60 phút -> kết thúc lúc 00:30 ngày hôm sau
        ApiException ex = expect(ApiException.class, 400, () -> service.create(
                new TrainingSessionRequest(10, 5, TODAY.plusDays(1).atTime(23, 30), 1600, 60), TRAINER, "ip"));
        assertTrue(ex.getFieldErrors().containsKey("plannedDuration"), ex.getFieldErrors().toString());
        assertTrue(ex.getFieldErrors().get("plannedDuration").contains("cannot cross into the next day"), ex.getFieldErrors().toString());
        verify(repo, never()).insert(anyInt(), anyInt(), anyInt(), any(), any());
    }

    @Test
    void rescheduleSessionEndingNextDayIsRejected() {
        HorseRepository horses = mock(HorseRepository.class);
        HorseVitalsRepository vitals = mock(HorseVitalsRepository.class);
        TrainingPlanRepository plans = mock(TrainingPlanRepository.class);
        TrainingSessionRepository repo = readyToSchedule(horses, vitals, plans);
        HorseResponse horseOfTrainer = new HorseResponse(100, 7, "Owner", null, null, "Horse 100", "TM-100",
                "TB", "COLT", TODAY.minusYears(4), "Bay", null, "A-100", "ELIGIBLE", LocalDateTime.now(), null,
                "ACADEMY", null, "ACTIVE", null, 2, "Trainer 2");
        when(horses.findById(100)).thenReturn(Optional.of(horseOfTrainer));
        when(horses.findActiveTrainer(2)).thenReturn(Optional.of(new HorseRepository.TrainerSummary(2, "trainer2", "Trainer 2", 5, 10)));
        when(repo.isGroomOfTrainer(5, 2)).thenReturn(true);
        LocalDateTime sched = TODAY.plusDays(2).atTime(10, 0);
        when(repo.findById(1)).thenReturn(Optional.of(session(1, 100, 5, sched, "SCHEDULED", 1600)));
        TrainingSessionService service = sessionService(repo, plans, horses, vitals);

        // Dời sang 23:45 với thời lượng 30 phút -> kết thúc lúc 00:15 ngày hôm sau
        ApiException ex = expect(ApiException.class, 400, () -> service.reschedule(
                1, new TrainingSessionScheduleRequest(5, TODAY.plusDays(3).atTime(23, 45), 30), TRAINER, "ip"));
        assertTrue(ex.getFieldErrors().containsKey("plannedDuration"), ex.getFieldErrors().toString());
        assertTrue(ex.getFieldErrors().get("plannedDuration").contains("cannot cross into the next day"), ex.getFieldErrors().toString());
        verify(repo, never()).updateSchedule(anyInt(), anyInt(), any());
    }

    @Test
    void sessionWithinSameDayWithMaxDurationIsAccepted() {
        HorseRepository horses = mock(HorseRepository.class);
        HorseVitalsRepository vitals = mock(HorseVitalsRepository.class);
        TrainingPlanRepository plans = mock(TrainingPlanRepository.class);
        TrainingSessionRepository repo = readyToSchedule(horses, vitals, plans);
        when(repo.insert(anyInt(), anyInt(), anyInt(), any(), any())).thenReturn(99);
        LocalDateTime start = TODAY.plusDays(1).atTime(8, 0);
        TrainingSessionResponse created = session(99, 100, 5, start, "SCHEDULED", 1600);
        when(repo.findById(99)).thenReturn(Optional.of(created));
        TrainingSessionService service = sessionService(repo, plans, horses, vitals);

        // 08:00 sáng, thời lượng tối đa 720 phút (12 tiếng) -> kết thúc lúc 20:00 cùng ngày
        TrainingSessionResponse res = service.create(
                new TrainingSessionRequest(10, 5, start, 1600, 720), TRAINER, "ip");
        assertNotNull(res);
        verify(repo).insert(eq(10), eq(100), eq(5), eq(start), eq(1600));
        verify(repo).updatePlannedDuration(99, 720);
    }

    @Test
    void overdueRunningSessionIsAutomaticallyFinished() {
        TrainingSessionRepository repo = mock(TrainingSessionRepository.class);
        UserRepository users = mock(UserRepository.class);
        NotificationService notifications = mock(NotificationService.class);
        TrainingSessionResponse runningOverdue = new TrainingSessionResponse(
                12, 10, 1, "Base", 100, "Horse 100", 5, "Groom 5",
                LocalDateTime.now().minusMinutes(90), 1600, null, null, null, 168, null, new BigDecimal("15.5"),
                null, null, "RUNNING", 60, 0, false, null);
        when(repo.findOverdueRunningSessions(any())).thenReturn(List.of(runningOverdue));
        when(repo.autoFinishOverdueRunning(12)).thenReturn(1);
        when(repo.autoCancelOverdueScheduled(any())).thenReturn(0);

        TrainingSessionService service = new TrainingSessionService(repo, mock(TrainingPlanRepository.class),
                mock(HorseRepository.class), mock(HorseVitalsRepository.class), users, notifications,
                mock(IncidentGuard.class), 1200);

        int finished = service.processOverdueSessions();
        assertEquals(1, finished);
        verify(repo).autoFinishOverdueRunning(12);
        verify(users).writeAudit(null, "TRAINING_SESSION_AUTO_FINISH", "TRAINING_SESSION", 12, "SUCCESS", null);
        verify(notifications).notifyRole(eq("HEAD_TRAINER"), any(),
                argThat(n -> "TRAINING_SESSION_AUTO_FINISHED".equals(n.type()) && n.message().contains("review within 48 hours")));
    }

    @Test
    void groomCannotEndSessionEarlyWithoutSelectingReason() {
        TrainingSessionRepository repo = mock(TrainingSessionRepository.class);
        TrainingSessionResponse runningWithBoth = new TrainingSessionResponse(
                1, 10, 1, "Base", 100, "Horse 100", 5, "Groom 5",
                LocalDateTime.now(), 1600, null, null, null, 165, null, new BigDecimal("15.2"), null, null,
                "RUNNING", 180, 0, false, null);
        when(repo.findById(1)).thenReturn(Optional.of(runningWithBoth));
        TrainingSessionService service = sessionService(repo, mock(TrainingPlanRepository.class),
                mock(HorseRepository.class), mock(HorseVitalsRepository.class));

        ApiException ex = expect(ApiException.class, 400,
                () -> service.finish(1, new FinishSessionRequest(true, "   "), GROOM, "ip"));
        assertTrue(ex.getFieldErrors().containsKey("earlyStopReason"), ex.getFieldErrors().toString());
        verify(repo, never()).markStoppedEarly(anyInt(), any());
    }

    @Test
    void sessionCannotBeScheduledInThePastDate() {
        HorseRepository horses = mock(HorseRepository.class);
        HorseVitalsRepository vitals = mock(HorseVitalsRepository.class);
        TrainingPlanRepository plans = mock(TrainingPlanRepository.class);
        TrainingSessionRepository repo = readyToSchedule(horses, vitals, plans);
        TrainingSessionService service = sessionService(repo, plans, horses, vitals);

        ApiException ex = expect(ApiException.class, 400, () -> service.create(
                new TrainingSessionRequest(10, 5, TODAY.minusDays(1).atTime(9, 0), 1600, 60), TRAINER, "ip"));
        assertTrue(ex.getFieldErrors().containsKey("sessionDate"), ex.getFieldErrors().toString());
        assertTrue(ex.getFieldErrors().get("sessionDate").contains("Cannot schedule a session in the past"), ex.getFieldErrors().toString());
    }

    @Test
    void groomCannotBeBookedWhenCurrentlyExecutingRunningSession() {
        HorseRepository horses = mock(HorseRepository.class);
        HorseVitalsRepository vitals = mock(HorseVitalsRepository.class);
        TrainingPlanRepository plans = mock(TrainingPlanRepository.class);
        TrainingSessionRepository repo = readyToSchedule(horses, vitals, plans);
        LocalDateTime tomorrow = TODAY.plusDays(1).atTime(14, 0);
        when(repo.findAll(100, null, null, null, false)).thenReturn(List.of());
        when(repo.findAll(null, null, 5, null, false)).thenReturn(List.of(
                session(88, 201, 5, LocalDateTime.now(), "RUNNING", 1600)));
        TrainingSessionService service = sessionService(repo, plans, horses, vitals);

        ApiException ex = expect(ApiException.class, 409,
                () -> service.create(new TrainingSessionRequest(10, 5, tomorrow, 1600, 60), TRAINER, "ip"));
        assertTrue(ex.getMessage().contains("currently executing an active session"), ex.getMessage());
        verify(repo, never()).insert(anyInt(), anyInt(), anyInt(), any(), any());
    }

    @Test
    void horseAssignmentExceedingHeadTrainerQuotaIsRejected() {
        HorseRepository horseRepo = mock(HorseRepository.class);
        HorseResponse horse = horse(105, "ELIGIBLE", TODAY.minusYears(4), "COLT");
        when(horseRepo.findById(105)).thenReturn(Optional.of(horse));
        when(horseRepo.findActiveTrainer(2)).thenReturn(Optional.of(
                new HorseRepository.TrainerSummary(2, "trainer2", "Trainer 2", 3, 3)));
        when(horseRepo.countManagedHorses(2, 105)).thenReturn(3);

        HorseService service = new HorseService(horseRepo, mock(HorseVitalsRepository.class), mock(UserRepository.class));

        ApiException ex = expect(ApiException.class, 409,
                () -> service.assignTrainer(105, 2, MANAGER, "ip"));
        assertTrue(ex.getMessage().contains("reached their maximum limit of 3 horses"), ex.getMessage());
        verify(horseRepo, never()).updateTrainer(anyInt(), any());
    }

    @Test
    void horseIdentityUpdateExceedingHeadTrainerQuotaIsRejected() {
        HorseRepository horseRepo = mock(HorseRepository.class);
        HorseResponse horse = horse(105, "ELIGIBLE", TODAY.minusYears(4), "COLT");
        when(horseRepo.findById(105)).thenReturn(Optional.of(horse));
        when(horseRepo.existsByRegistrationCode("RC-999", 105)).thenReturn(false);
        when(horseRepo.existsByStallNo("B-01", 105)).thenReturn(false);
        when(horseRepo.findActiveTrainer(2)).thenReturn(Optional.of(
                new HorseRepository.TrainerSummary(2, "trainer2", "Trainer 2", 5, 5)));
        when(horseRepo.countManagedHorses(2, 105)).thenReturn(5);

        HorseService service = new HorseService(horseRepo, mock(HorseVitalsRepository.class), mock(UserRepository.class));

        ApiException ex = expect(ApiException.class, 400, () -> service.updateIdentity(
                105, new HorseIdentityRequest("RC-999", "B-01", 2), MANAGER, "ip"));
        assertTrue(ex.getFieldErrors().containsKey("trainerId"), ex.getFieldErrors().toString());
        assertTrue(ex.getFieldErrors().get("trainerId").contains("maximum limit of 5 horses"), ex.getFieldErrors().toString());
    }

    @Test
    void headTrainerCannotScheduleSessionForHorseAssignedToAnotherTrainer() {
        HorseRepository horses = mock(HorseRepository.class);
        HorseVitalsRepository vitals = mock(HorseVitalsRepository.class);
        TrainingPlanRepository plans = mock(TrainingPlanRepository.class);
        TrainingSessionRepository repo = readyToSchedule(horses, vitals, plans);
        HorseResponse horseOfOtherTrainer = new HorseResponse(100, 7, "Owner", null, null, "Horse 100", "TM-100",
                "TB", "COLT", TODAY.minusYears(4), "Bay", null, "A-100", "ELIGIBLE", LocalDateTime.now(), null,
                "ACADEMY", null, "ACTIVE", null, 99, "Other Trainer");
        when(horses.findById(100)).thenReturn(Optional.of(horseOfOtherTrainer));
        TrainingSessionService service = sessionService(repo, plans, horses, vitals);

        ApiException ex = expect(ApiException.class, 403, () -> service.create(
                new TrainingSessionRequest(10, 5, TODAY.plusDays(1).atTime(9, 0), 1600, 60), TRAINER, "ip"));
        assertTrue(ex.getMessage().contains("assigned to Head Trainer Other Trainer"), ex.getMessage());
        verify(repo, never()).insert(anyInt(), anyInt(), anyInt(), any(), any());
    }

    @Test
    void headTrainerCannotSelfAssignHorse() {
        HorseRepository horseRepo = mock(HorseRepository.class);
        HorseResponse horse = horse(105, "ELIGIBLE", TODAY.minusYears(4), "COLT");
        when(horseRepo.findById(105)).thenReturn(Optional.of(horse));
        HorseService service = new HorseService(horseRepo, mock(HorseVitalsRepository.class), mock(UserRepository.class));

        ApiException ex = expect(ApiException.class, 403, () -> service.assignTrainer(105, 2, TRAINER, "ip"));
        assertTrue(ex.getMessage().contains("Only Club Manager can assign horses"), ex.getMessage());
        verify(horseRepo, never()).updateTrainer(anyInt(), any());
    }

    @Test
    void nonManagerCannotUpdateHorseIdentity() {
        HorseRepository horseRepo = mock(HorseRepository.class);
        HorseService service = new HorseService(horseRepo, mock(HorseVitalsRepository.class), mock(UserRepository.class));

        ApiException ex = expect(ApiException.class, 403, () -> service.updateIdentity(
                105, new HorseIdentityRequest("RC-999", "B-01", 2), TRAINER, "ip"));
        assertTrue(ex.getMessage().contains("Only Club Manager can update horse identity"), ex.getMessage());
        verify(horseRepo, never()).updateIdentity(anyInt(), any(), any());
    }

    @Test
    void headTrainerCannotAssignGroomFromAnotherManagementTeam() {
        HorseRepository horses = mock(HorseRepository.class);
        HorseVitalsRepository vitals = mock(HorseVitalsRepository.class);
        TrainingPlanRepository plans = mock(TrainingPlanRepository.class);
        TrainingSessionRepository repo = readyToSchedule(horses, vitals, plans);
        HorseResponse horseOfTrainer = new HorseResponse(100, 7, "Owner", null, null, "Horse 100", "TM-100",
                "TB", "COLT", TODAY.minusYears(4), "Bay", null, "A-100", "ELIGIBLE", LocalDateTime.now(), null,
                "ACADEMY", null, "ACTIVE", null, 2, "Trainer 2");
        when(horses.findById(100)).thenReturn(Optional.of(horseOfTrainer));
        when(horses.findActiveTrainer(2)).thenReturn(Optional.of(new HorseRepository.TrainerSummary(2, "trainer2", "Trainer 2", 5, 10)));
        when(repo.isGroomOfTrainer(5, 2)).thenReturn(false);

        TrainingSessionService service = sessionService(repo, plans, horses, vitals);
        ApiException ex = expect(ApiException.class, 400, () -> service.create(
                new TrainingSessionRequest(10, 5, TODAY.plusDays(1).atTime(9, 0), 1600, 60), TRAINER, "ip"));
        assertTrue(ex.getFieldErrors().containsKey("assignedTo"), ex.getFieldErrors().toString());
        assertTrue(ex.getFieldErrors().get("assignedTo").contains("not assigned to your management team"), ex.getFieldErrors().toString());
        verify(repo, never()).insert(anyInt(), anyInt(), anyInt(), any(), any());
    }

    @Test
    void headTrainerCannotRescheduleSessionForHorseAssignedToAnotherTrainer() {
        HorseRepository horses = mock(HorseRepository.class);
        HorseVitalsRepository vitals = mock(HorseVitalsRepository.class);
        TrainingPlanRepository plans = mock(TrainingPlanRepository.class);
        TrainingSessionRepository repo = readyToSchedule(horses, vitals, plans);
        HorseResponse horseOfOtherTrainer = new HorseResponse(100, 7, "Owner", null, null, "Horse 100", "TM-100",
                "TB", "COLT", TODAY.minusYears(4), "Bay", null, "A-100", "ELIGIBLE", LocalDateTime.now(), null,
                "ACADEMY", null, "ACTIVE", null, 99, "Other Trainer");
        when(horses.findById(100)).thenReturn(Optional.of(horseOfOtherTrainer));
        LocalDateTime sched = TODAY.plusDays(2).atTime(10, 0);
        when(repo.findById(1)).thenReturn(Optional.of(session(1, 100, 5, sched, "SCHEDULED", 1600)));
        TrainingSessionService service = sessionService(repo, plans, horses, vitals);

        ApiException ex = expect(ApiException.class, 403, () -> service.reschedule(
                1, new TrainingSessionScheduleRequest(5, sched.plusHours(1), 60), TRAINER, "ip"));
        assertTrue(ex.getMessage().contains("assigned to Head Trainer Other Trainer"), ex.getMessage());
        verify(repo, never()).updateSchedule(anyInt(), anyInt(), any());
    }

    @Test
    void horseServiceListForHeadTrainerReturnsOnlyAssignedHorsesViaFindByTrainer() {
        HorseRepository horseRepo = mock(HorseRepository.class);
        HorseResponse horse1 = horseWithTrainer(101, "ELIGIBLE", TODAY.minusYears(3), "COLT", 2, "Trainer 2");
        HorseResponse horse2 = horseWithTrainer(102, "ELIGIBLE", TODAY.minusYears(4), "FILLY", 2, "Trainer 2");
        when(horseRepo.findByTrainer(2)).thenReturn(List.of(horse1, horse2));

        HorseService service = new HorseService(horseRepo, mock(HorseVitalsRepository.class),
                mock(UserRepository.class), mock(NotificationService.class));

        List<HorseResponse> result = service.list(TRAINER);
        assertEquals(2, result.size());
        assertEquals(101, result.get(0).horseId());
        assertEquals(102, result.get(1).horseId());
        verify(horseRepo).findByTrainer(2);
        verify(horseRepo, never()).findAll();
    }

    @Test
    void trainingSessionCreateWithUnassignedHorseThrows403() {
        HorseRepository horses = mock(HorseRepository.class);
        HorseVitalsRepository vitals = mock(HorseVitalsRepository.class);
        TrainingPlanRepository plans = mock(TrainingPlanRepository.class);
        TrainingSessionRepository repo = readyToSchedule(horses, vitals, plans);
        HorseResponse unassignedHorse = horse(100, "ELIGIBLE", TODAY.minusYears(4), "COLT");
        when(horses.findById(100)).thenReturn(Optional.of(unassignedHorse));
        TrainingSessionService service = sessionService(repo, plans, horses, vitals);

        ApiException ex = expect(ApiException.class, 403, () -> service.create(
                new TrainingSessionRequest(10, 5, TODAY.plusDays(1).atTime(9, 0), 1600, 60), TRAINER, "ip"));
        assertTrue(ex.getMessage().contains("not been assigned to you by the Club Manager yet"), ex.getMessage());
        verify(repo, never()).insert(anyInt(), anyInt(), anyInt(), any(), any());
    }

    @Test
    void trainingSessionCreateWithAssignedGroomAndAssignedHorseSucceeds() {
        HorseRepository horses = mock(HorseRepository.class);
        HorseVitalsRepository vitals = mock(HorseVitalsRepository.class);
        TrainingPlanRepository plans = mock(TrainingPlanRepository.class);
        TrainingSessionRepository repo = readyToSchedule(horses, vitals, plans);
        LocalDateTime sessionTime = TODAY.plusDays(1).atTime(9, 0);
        when(repo.findAll(100, null, null, null, false)).thenReturn(List.of());
        when(repo.findAll(null, null, 5, null, false)).thenReturn(List.of());
        when(repo.insert(eq(10), eq(100), eq(5), eq(sessionTime), eq(1600))).thenReturn(501);
        TrainingSessionResponse createdSession = session(501, 100, 5, sessionTime, "SCHEDULED", 1600);
        when(repo.findById(501)).thenReturn(Optional.of(createdSession));

        NotificationService notifService = mock(NotificationService.class);
        TrainingSessionService service = new TrainingSessionService(repo, plans, horses, vitals,
                mock(UserRepository.class), notifService, mock(IncidentGuard.class), 1200);

        TrainingSessionResponse resp = service.create(
                new TrainingSessionRequest(10, 5, sessionTime, 1600, 60), TRAINER, "ip");
        assertNotNull(resp);
        assertEquals(501, resp.sessionId());
        verify(repo).insert(eq(10), eq(100), eq(5), eq(sessionTime), eq(1600));
        verify(notifService).notifyUser(eq(5), argThat(n -> "TRAINING_SESSION_ASSIGNED".equals(n.type())));
    }

    @Test
    void trainingSessionListActiveGroomsForHeadTrainerReturnsOnlyAssignedGrooms() {
        TrainingSessionRepository repo = mock(TrainingSessionRepository.class);
        TrainingSessionRepository.GroomOption g1 = new TrainingSessionRepository.GroomOption(5, "Groom 5");
        when(repo.findActiveGroomsByTrainer(2)).thenReturn(List.of(g1));

        TrainingSessionService service = sessionService(repo, mock(TrainingPlanRepository.class),
                mock(HorseRepository.class), mock(HorseVitalsRepository.class));

        List<TrainingSessionRepository.GroomOption> grooms = service.listActiveGrooms(TRAINER);
        assertEquals(1, grooms.size());
        assertEquals(5, grooms.get(0).userId());
        verify(repo).findActiveGroomsByTrainer(2);
        verify(repo, never()).findActiveGrooms();
    }

    @Test
    void trainingPlanListForHeadTrainerReturnsOnlyManagedHorsePlans() {
        TrainingPlanRepository plans = mock(TrainingPlanRepository.class);
        HorseRepository horses = mock(HorseRepository.class);
        when(horses.findHorseIdsByTrainer(2)).thenReturn(java.util.Set.of(100, 101));

        TrainingPlanResponse.Summary s1 = new TrainingPlanResponse.Summary(
                1, 100, "Horse 100", "Plan 1", TODAY, TODAY.plusDays(30), "ACTIVE", 2, null);
        TrainingPlanResponse.Summary s2 = new TrainingPlanResponse.Summary(
                2, 200, "Horse 200 (Other)", "Plan 2", TODAY, TODAY.plusDays(30), "ACTIVE", 3, null);
        TrainingPlanResponse.Summary s3 = new TrainingPlanResponse.Summary(
                3, 101, "Horse 101", "Plan 3", TODAY, TODAY.plusDays(30), "DRAFT", 2, null);
        when(plans.findSummaries(null, null, null)).thenReturn(List.of(s1, s2, s3));

        TrainingPlanService service = planService(plans, horses, mock(HorseVitalsRepository.class));

        List<TrainingPlanResponse.Summary> result = service.list(null, null, TRAINER);
        assertEquals(2, result.size());
        assertTrue(result.stream().anyMatch(p -> p.planId() == 1));
        assertTrue(result.stream().anyMatch(p -> p.planId() == 3));
        assertTrue(result.stream().noneMatch(p -> p.planId() == 2));
    }

    @Test
    void trainingPlanCreateRejectsHorseNotAssignedToHeadTrainer() {
        TrainingPlanRepository plans = mock(TrainingPlanRepository.class);
        HorseRepository horses = mock(HorseRepository.class);
        HorseResponse horseOfOther = horseWithTrainer(100, "ELIGIBLE", TODAY.minusYears(4), "COLT", 99, "Other Trainer");
        when(horses.findById(100)).thenReturn(Optional.of(horseOfOther));

        TrainingPlanService service = planService(plans, horses, mock(HorseVitalsRepository.class));
        TrainingPlanRequest req = new TrainingPlanRequest(100, "Speed Plan", TODAY.plusDays(1), TODAY.plusDays(30),
                List.of(new TrainingPlanRequest.PhaseRequest("Base", 1600, "Moderate", "TURF", 3, 180, 90, null, TODAY.plusDays(1), TODAY.plusDays(30))));

        ApiException ex = expect(ApiException.class, 400, () -> service.create(req, TRAINER, "ip"));
        assertTrue(ex.getFieldErrors().containsKey("horseId"), ex.getFieldErrors().toString());
        assertTrue(ex.getFieldErrors().get("horseId").contains("assigned to Head Trainer Other Trainer"), ex.getFieldErrors().toString());
        verify(plans, never()).insertPlan(anyInt(), anyInt(), any(), any(), any());
    }

    @Test
    void trainingPlanCreateRejectsUnassignedHorseForHeadTrainer() {
        TrainingPlanRepository plans = mock(TrainingPlanRepository.class);
        HorseRepository horses = mock(HorseRepository.class);
        HorseResponse unassignedHorse = horse(100, "ELIGIBLE", TODAY.minusYears(4), "COLT");
        when(horses.findById(100)).thenReturn(Optional.of(unassignedHorse));

        TrainingPlanService service = planService(plans, horses, mock(HorseVitalsRepository.class));
        TrainingPlanRequest req = new TrainingPlanRequest(100, "Speed Plan", TODAY.plusDays(1), TODAY.plusDays(30),
                List.of(new TrainingPlanRequest.PhaseRequest("Base", 1600, "Moderate", "TURF", 3, 180, 90, null, TODAY.plusDays(1), TODAY.plusDays(30))));

        ApiException ex = expect(ApiException.class, 400, () -> service.create(req, TRAINER, "ip"));
        assertTrue(ex.getFieldErrors().containsKey("horseId"), ex.getFieldErrors().toString());
        assertTrue(ex.getFieldErrors().get("horseId").contains("not been assigned to you by the Club Manager yet"), ex.getFieldErrors().toString());
        verify(plans, never()).insertPlan(anyInt(), anyInt(), any(), any(), any());
    }

    @Test
    void trainingPlanGetRejectsHorseNotAssignedToHeadTrainer() {
        TrainingPlanRepository plans = mock(TrainingPlanRepository.class);
        HorseRepository horses = mock(HorseRepository.class);
        TrainingPlanResponse planOfOther = new TrainingPlanResponse(1, 100, "Horse 100", "Plan 1",
                TODAY, TODAY.plusDays(30), "ACTIVE", 99, "Other Trainer", LocalDateTime.now(), null, null, List.of());
        when(plans.findById(1)).thenReturn(Optional.of(planOfOther));
        HorseResponse horseOfOther = horseWithTrainer(100, "ELIGIBLE", TODAY.minusYears(4), "COLT", 99, "Other Trainer");
        when(horses.findById(100)).thenReturn(Optional.of(horseOfOther));

        TrainingPlanService service = planService(plans, horses, mock(HorseVitalsRepository.class));
        ApiException ex = expect(ApiException.class, 403, () -> service.get(1, TRAINER));
        assertTrue(ex.getMessage().contains("not assigned to you"), ex.getMessage());
    }

    @Test
    void trainingPlanCreateRequiresAssignedGroomUnderHeadTrainer() {
        TrainingPlanRepository plans = mock(TrainingPlanRepository.class);
        HorseRepository horses = mock(HorseRepository.class);
        StaffRepository staff = mock(StaffRepository.class);
        HorseResponse myHorse = horseWithTrainer(100, "ELIGIBLE", TODAY.minusYears(4), "COLT", TRAINER.userId(), "Trainer");
        when(horses.findById(100)).thenReturn(Optional.of(myHorse));

        TrainingPlanService service = new TrainingPlanService(plans, horses, mock(HorseVitalsRepository.class),
                mock(UserRepository.class), mock(NotificationService.class), staff);

        // 1. groomId is null
        TrainingPlanRequest reqWithoutGroom = new TrainingPlanRequest(100, null, "Speed Plan", TODAY.plusDays(1), TODAY.plusDays(30),
                List.of(new TrainingPlanRequest.PhaseRequest("Base", 1600, "Moderate", "TURF", 3, 180, 90, null, TODAY.plusDays(1), TODAY.plusDays(30))));
        ApiException ex1 = expect(ApiException.class, 400, () -> service.create(reqWithoutGroom, TRAINER, "ip"));
        assertTrue(ex1.getFieldErrors().containsKey("groomId"));
        assertTrue(ex1.getFieldErrors().get("groomId").contains("Assigned groom is required"));

        // 2. groom is not under this trainer
        StaffRow otherGroom = new StaffRow(88, "groom88", "Groom 88", "g88@test.com", "0900", "GROOM", "Groom", "ACTIVE", null, LocalDateTime.now(), 0, 99, "Other Trainer", 0, 0, null, 0);
        when(staff.findRow(88)).thenReturn(Optional.of(otherGroom));
        TrainingPlanRequest reqOtherGroom = new TrainingPlanRequest(100, 88, "Speed Plan", TODAY.plusDays(1), TODAY.plusDays(30),
                List.of(new TrainingPlanRequest.PhaseRequest("Base", 1600, "Moderate", "TURF", 3, 180, 90, null, TODAY.plusDays(1), TODAY.plusDays(30))));
        ApiException ex2 = expect(ApiException.class, 400, () -> service.create(reqOtherGroom, TRAINER, "ip"));
        assertTrue(ex2.getFieldErrors().containsKey("groomId"));
        assertTrue(ex2.getFieldErrors().get("groomId").contains("not under your management"));
    }

    @Test
    void trainingPlanCreateRejectsGroomWithTwoActivePlans() {
        TrainingPlanRepository plans = mock(TrainingPlanRepository.class);
        HorseRepository horses = mock(HorseRepository.class);
        StaffRepository staff = mock(StaffRepository.class);
        HorseResponse myHorse = horseWithTrainer(100, "ELIGIBLE", TODAY.minusYears(4), "COLT", TRAINER.userId(), "Trainer");
        when(horses.findById(100)).thenReturn(Optional.of(myHorse));

        StaffRow myGroom = new StaffRow(5, "groom5", "Groom 5", "g5@test.com", "0900", "GROOM", "Groom", "ACTIVE", null, LocalDateTime.now(), 0, TRAINER.userId(), "Trainer", 0, 0, null, 2);
        when(staff.findRow(5)).thenReturn(Optional.of(myGroom));
        when(plans.countActivePlansByGroom(5)).thenReturn(2);

        TrainingPlanService service = new TrainingPlanService(plans, horses, mock(HorseVitalsRepository.class),
                mock(UserRepository.class), mock(NotificationService.class), staff);
        TrainingPlanRequest req = new TrainingPlanRequest(100, 5, "Speed Plan", TODAY.plusDays(1), TODAY.plusDays(30),
                List.of(new TrainingPlanRequest.PhaseRequest("Base", 1600, "Moderate", "TURF", 3, 180, 90, null, TODAY.plusDays(1), TODAY.plusDays(30))));

        ApiException ex = expect(ApiException.class, 400, () -> service.create(req, TRAINER, "ip"));
        assertTrue(ex.getFieldErrors().containsKey("groomId"));
        assertTrue(ex.getFieldErrors().get("groomId").contains("already assigned to 2 active training plans"));
        verify(plans, never()).insertPlan(anyInt(), anyInt(), any(), any(), any());
    }

    @Test
    void staffAssignTrainerRejectsTransferIfGroomHasActivePlans() {
        StaffRepository staffRepo = mock(StaffRepository.class);
        TrainingPlanRepository plans = mock(TrainingPlanRepository.class);
        StaffRow groom = new StaffRow(5, "groom5", "Groom 5", "g5@test.com", "0912345678", "GROOM", "Groom", "ACTIVE", null, LocalDateTime.now(), 0, 2, "Trainer 2", 0, 0, null, 1);
        when(staffRepo.findRow(5)).thenReturn(Optional.of(groom));
        when(plans.countActivePlansByGroom(5)).thenReturn(1);

        StaffService service = new StaffService(mock(UserRepository.class), staffRepo,
                mock(PasswordEncoder.class), mock(TokenBlacklist.class), mock(NotificationService.class), plans);

        ApiException ex = expect(ApiException.class, 409, () -> service.assignTrainer(5, 3, MANAGER, "ip"));
        assertTrue(ex.getMessage().contains("Cannot transfer Groom"));
        assertTrue(ex.getMessage().contains("currently assigned to 1 active training plan"));
        verify(staffRepo, never()).updateTrainer(anyInt(), any());
    }

    @Test
    void trainingPlanServiceListGroomsReturnsActiveGroomsWithActivePlanCount() {
        TrainingPlanRepository planRepo = mock(TrainingPlanRepository.class);
        StaffRepository staffRepo = mock(StaffRepository.class);
        when(planRepo.findActiveGroomsByTrainer(2)).thenReturn(List.of(
                new TrainingPlanRepository.GroomOption(10, "Lê Minh Hiếu"),
                new TrainingPlanRepository.GroomOption(11, "Lê Văn Bình"),
                new TrainingPlanRepository.GroomOption(12, "Trương Quang Nam")
        ));
        when(planRepo.countActivePlansByGroom(10)).thenReturn(0);
        when(planRepo.countActivePlansByGroom(11)).thenReturn(1);
        when(planRepo.countActivePlansByGroom(12)).thenReturn(2);

        TrainingPlanService service = new TrainingPlanService(planRepo, mock(HorseRepository.class),
                mock(HorseVitalsRepository.class), mock(UserRepository.class), mock(NotificationService.class), staffRepo);

        List<TrainingPlanService.TrainerGroomOption> grooms = service.listGroomsForPlan(TRAINER);
        assertEquals(3, grooms.size());
        assertEquals("Lê Minh Hiếu", grooms.get(0).fullName());
        assertEquals(0, grooms.get(0).activePlanCount());
        assertTrue(grooms.get(0).eligible());

        assertEquals("Lê Văn Bình", grooms.get(1).fullName());
        assertEquals(1, grooms.get(1).activePlanCount());
        assertTrue(grooms.get(1).eligible());

        assertEquals("Trương Quang Nam", grooms.get(2).fullName());
        assertEquals(2, grooms.get(2).activePlanCount());
        assertFalse(grooms.get(2).eligible()); // max 2 plans reached
    }

    @Test
    void trainingPlanServiceListGroomsFallbacksToStaffRepositoryIfPlanRepoFindEmpty() {
        TrainingPlanRepository planRepo = mock(TrainingPlanRepository.class);
        StaffRepository staffRepo = mock(StaffRepository.class);
        when(planRepo.findActiveGroomsByTrainer(2)).thenReturn(List.of());

        StaffRow m1 = new StaffRow(10, "hieu.lm", "Lê Minh Hiếu", "hieu@test.com", "0900", "GROOM", "Groom", "ACTIVE", null, LocalDateTime.now(), 0, 2, "Trainer 2", 0, 0, null, 0);
        StaffRow m2 = new StaffRow(11, "binh.lv", "Lê Văn Bình", "binh@test.com", "0900", "GROOM", "Groom", "ACTIVE", null, LocalDateTime.now(), 0, 2, "Trainer 2", 0, 0, null, 0);
        StaffRow other = new StaffRow(13, "other", "Other Groom", "o@test.com", "0900", "GROOM", "Groom", "ACTIVE", null, LocalDateTime.now(), 0, 99, "Other Trainer", 0, 0, null, 0);

        when(staffRepo.findAllGrouped()).thenReturn(List.of(
                new racehorse.backend.staff.StaffGroup("GROOM", "Groom", List.of(m1, m2, other))
        ));

        TrainingPlanService service = new TrainingPlanService(planRepo, mock(HorseRepository.class),
                mock(HorseVitalsRepository.class), mock(UserRepository.class), mock(NotificationService.class), staffRepo);

        List<TrainingPlanService.TrainerGroomOption> grooms = service.listGroomsForPlan(TRAINER);
        assertEquals(2, grooms.size());
        assertEquals("Lê Minh Hiếu", grooms.get(0).fullName());
        assertEquals("Lê Văn Bình", grooms.get(1).fullName());
    }

    @Test
    void trainingPlanServiceUpdatePlanUpdatesGroomAndPhasesAndNotifiesOwner() {
        TrainingPlanRepository planRepo = mock(TrainingPlanRepository.class);
        HorseRepository horseRepo = mock(HorseRepository.class);
        NotificationService notifications = mock(NotificationService.class);
        StaffRepository staffRepo = mock(StaffRepository.class);

        HorseResponse horse = horseWithTrainer(100, "ELIGIBLE", TODAY.minusYears(4), "COLT", 2, "Trainer 2");
        when(horseRepo.findById(100)).thenReturn(Optional.of(horse));

        TrainingPlanResponse existing = new TrainingPlanResponse(1, 100, "Air Groove", 11, "Lê Văn Bình",
                "Plan 1", TODAY, TODAY.plusDays(30), "ACTIVE", 2, "Trainer 2", LocalDateTime.now(), null, null,
                List.of(new TrainingPlanResponse.Phase(10, 1, "Phase 1", 1600, "Moderate", "TURF", 3, 180, TODAY, TODAY.plusDays(30), 90, null)));
        when(planRepo.findById(1)).thenReturn(Optional.of(existing));
        when(planRepo.findPhases(1)).thenReturn(existing.phases());

        StaffRow newGroom = new StaffRow(10, "hieu.lm", "Lê Minh Hiếu", "hieu@test.com", "0900", "GROOM", "Groom", "ACTIVE", null, LocalDateTime.now(), 0, 2, "Trainer 2", 0, 0, null, 0);
        when(staffRepo.findRow(10)).thenReturn(Optional.of(newGroom));
        when(planRepo.countActivePlansByGroom(10)).thenReturn(0);

        TrainingPlanService service = new TrainingPlanService(planRepo, horseRepo,
                mock(HorseVitalsRepository.class), mock(UserRepository.class), notifications, staffRepo);

        TrainingPlanRequest.Update updateReq = new TrainingPlanRequest.Update(
                10, "Plan 1 - Adjusted",
                List.of(new TrainingPlanRequest.PhaseRequest(10, "Phase 1 - Adjusted", 1800, "Heavy", "TURF", 4, 185, 90, null, TODAY, TODAY.plusDays(30)))
        );

        service.update(1, updateReq, TRAINER, "ip");

        verify(planRepo).updatePlanGroomAndName(eq(1), eq(10), eq("Plan 1 - Adjusted"));
        verify(planRepo).updatePhase(eq(10), eq(1), eq(1), any());
        verify(notifications).notifyUser(eq(7), argThat(n -> "TRAINING_PLAN_UPDATED".equals(n.type())));
    }

    @Test
    void trainingPlanServiceOwnerFeedbackRecordsFeedbackAndNotifiesTrainer() {
        TrainingPlanRepository planRepo = mock(TrainingPlanRepository.class);
        HorseRepository horseRepo = mock(HorseRepository.class);
        NotificationService notifications = mock(NotificationService.class);

        HorseResponse horse = horseWithTrainer(100, "ELIGIBLE", TODAY.minusYears(4), "COLT", 2, "Trainer 2");
        when(horseRepo.findById(100)).thenReturn(Optional.of(horse));

        TrainingPlanResponse plan = new TrainingPlanResponse(1, 100, "Air Groove", 11, "Lê Văn Bình",
                "Plan 1", TODAY, TODAY.plusDays(30), "ACTIVE", 2, "Trainer 2", LocalDateTime.now(), null, null, List.of());
        when(planRepo.findById(1)).thenReturn(Optional.of(plan));
        when(planRepo.recordOwnerFeedback(1, "Please reduce workload")).thenReturn(1);

        TrainingPlanService service = new TrainingPlanService(planRepo, horseRepo,
                mock(HorseVitalsRepository.class), mock(UserRepository.class), notifications, mock(StaffRepository.class));

        service.ownerFeedback(1, "Please reduce workload", OWNER, "ip");

        verify(planRepo).recordOwnerFeedback(1, "Please reduce workload");
        verify(notifications).notifyUser(eq(2), argThat(n -> "TRAINING_PLAN_OWNER_FEEDBACK".equals(n.type())));
    }

    @Test
    void horseServiceAssignTrainerEmitsTwoWayNotifications() {
        HorseRepository horseRepo = mock(HorseRepository.class);
        NotificationService notifications = mock(NotificationService.class);
        HorseResponse horseBefore = horseWithTrainer(105, "ELIGIBLE", TODAY.minusYears(4), "COLT", null, null);
        HorseResponse horseAfter = horseWithTrainer(105, "ELIGIBLE", TODAY.minusYears(4), "COLT", 2, "Trainer 2");
        when(horseRepo.findById(105)).thenReturn(Optional.of(horseBefore), Optional.of(horseAfter));
        when(horseRepo.findActiveTrainer(2)).thenReturn(Optional.of(
                new HorseRepository.TrainerSummary(2, "trainer2", "Trainer 2", 1, 10)));
        when(horseRepo.countManagedHorses(2, 105)).thenReturn(1);

        HorseService service = new HorseService(horseRepo, mock(HorseVitalsRepository.class),
                mock(UserRepository.class), notifications);

        service.assignTrainer(105, 2, MANAGER, "ip");
        verify(horseRepo).updateTrainer(105, 2);
        verify(notifications).notifyUser(eq(2), argThat(n ->
                "HORSE_ASSIGNED_TO_TRAINER".equals(n.type()) && "HORSE".equals(n.targetEntity()) && Integer.valueOf(105).equals(n.targetId())));
        verify(notifications).notifyUser(eq(7), argThat(n ->
                "HORSE_TRAINER_CHANGED".equals(n.type()) && "HORSE".equals(n.targetEntity()) && Integer.valueOf(105).equals(n.targetId())));
    }

    @Test
    void horseServiceUpdateIdentityWithTrainerUpdateEmitsTwoWayNotifications() {
        HorseRepository horseRepo = mock(HorseRepository.class);
        NotificationService notifications = mock(NotificationService.class);
        HorseResponse horseBefore = horseWithTrainer(105, "ELIGIBLE", TODAY.minusYears(4), "COLT", null, null);
        HorseResponse horseAfter = horseWithTrainer(105, "ELIGIBLE", TODAY.minusYears(4), "COLT", 2, "Trainer 2");
        when(horseRepo.findById(105)).thenReturn(Optional.of(horseBefore), Optional.of(horseAfter));
        when(horseRepo.existsByRegistrationCode("RC-999", 105)).thenReturn(false);
        when(horseRepo.existsByStallNo("B-01", 105)).thenReturn(false);
        when(horseRepo.findActiveTrainer(2)).thenReturn(Optional.of(
                new HorseRepository.TrainerSummary(2, "trainer2", "Trainer 2", 1, 10)));
        when(horseRepo.countManagedHorses(2, 105)).thenReturn(1);

        HorseService service = new HorseService(horseRepo, mock(HorseVitalsRepository.class),
                mock(UserRepository.class), notifications);

        service.updateIdentity(105, new HorseIdentityRequest("RC-999", "B-01", 2), MANAGER, "ip");
        verify(horseRepo).updateIdentity(105, "RC-999", "B-01");
        verify(horseRepo).updateTrainer(105, 2);
        verify(notifications).notifyUser(eq(2), argThat(n ->
                "HORSE_ASSIGNED_TO_TRAINER".equals(n.type()) && "HORSE".equals(n.targetEntity()) && Integer.valueOf(105).equals(n.targetId())));
        verify(notifications).notifyUser(eq(7), argThat(n ->
                "HORSE_TRAINER_CHANGED".equals(n.type()) && "HORSE".equals(n.targetEntity()) && Integer.valueOf(105).equals(n.targetId())));
    }

    @Test
    void staffServiceCreateGroomWithTrainerEmitsTwoWayNotifications() {
        StaffRepository staffRepo = mock(StaffRepository.class);
        UserRepository users = mock(UserRepository.class);
        PasswordEncoder encoder = mock(PasswordEncoder.class);
        NotificationService notifications = mock(NotificationService.class);
        StaffRow trainer = new StaffRow(2, "trainer2", "Trainer 2", "t2@test.com", "0912345679", "HEAD_TRAINER", "Head Trainer", "ACTIVE", null, LocalDateTime.now(), 0);
        when(users.findRoleId("GROOM")).thenReturn(Optional.of(4));
        when(users.existsByUsername("groom99")).thenReturn(false);
        when(users.existsByEmail("g99@test.com")).thenReturn(false);
        when(users.insert(eq(4), eq("groom99"), any(), eq("Groom 99"), eq("g99@test.com"), any())).thenReturn(99);
        when(staffRepo.findRow(2)).thenReturn(Optional.of(trainer));
        when(staffRepo.countActiveGroomsOfTrainer(2, null)).thenReturn(2);
        StaffRow createdGroom = new StaffRow(99, "groom99", "Groom 99", "g99@test.com", "0912345678", "GROOM", "Groom", "ACTIVE", null, LocalDateTime.now(), 0);
        when(staffRepo.findRow(99)).thenReturn(Optional.of(createdGroom));

        StaffService service = new StaffService(users, staffRepo, encoder, mock(TokenBlacklist.class), notifications);
        StaffController.CreateRequest req = new StaffController.CreateRequest(
                "groom99", "Password123", "Groom 99", "g99@test.com", "0912345678", "GROOM", 2, null);

        StaffRow result = service.create(req, MANAGER, "ip");
        assertNotNull(result);
        verify(staffRepo).updateTrainer(99, 2);
        verify(notifications).notifyUser(eq(2), argThat(n -> "GROOM_ASSIGNED".equals(n.type())));
        verify(notifications).notifyUser(eq(99), argThat(n -> "TRAINER_ASSIGNED".equals(n.type())));
    }

    @Test
    void sessionServiceListForHeadTrainerReturnsOnlyManagedHorseSessions() {
        SessionRepository sessionRepo = mock(SessionRepository.class);
        HorseRepository horseRepo = mock(HorseRepository.class);
        when(horseRepo.findHorseIdsByTrainer(2)).thenReturn(java.util.Set.of(100, 101));

        SessionResponse s1 = evaluationSession(1, 100, 5, TODAY.atTime(8, 0), "COMPLETED");
        SessionResponse s2 = evaluationSession(2, 200, 6, TODAY.atTime(9, 0), "COMPLETED");
        SessionResponse s3 = evaluationSession(3, 101, 5, TODAY.atTime(10, 0), "COMPLETED");
        when(sessionRepo.search(null, null, 200, null)).thenReturn(List.of(s1, s2, s3));

        SessionService service = new SessionService(sessionRepo, mock(UserRepository.class),
                mock(NotificationService.class), null, horseRepo);

        List<SessionResponse> result = service.list(null, null, 200, TRAINER);
        assertEquals(2, result.size());
        assertTrue(result.stream().anyMatch(s -> s.sessionId() == 1));
        assertTrue(result.stream().anyMatch(s -> s.sessionId() == 3));
        assertTrue(result.stream().noneMatch(s -> s.sessionId() == 2));
    }

    @Test
    void sessionServiceGetRejectsSessionOfHorseNotAssignedToHeadTrainer() {
        SessionRepository sessionRepo = mock(SessionRepository.class);
        HorseRepository horseRepo = mock(HorseRepository.class);
        SessionResponse sOther = evaluationSession(1, 200, 5, TODAY.atTime(8, 0), "COMPLETED");
        when(sessionRepo.findById(1)).thenReturn(Optional.of(sOther));
        when(horseRepo.findHorseIdsByTrainer(2)).thenReturn(java.util.Set.of(100));

        SessionService service = new SessionService(sessionRepo, mock(UserRepository.class),
                mock(NotificationService.class), null, horseRepo);

        ApiException ex = expect(ApiException.class, 403, () -> service.get(1, TRAINER));
        assertTrue(ex.getMessage().contains("not assigned to you"), ex.getMessage());
    }

    @Test
    void groomCannotEndSessionEarlyWithoutHeartRateEvenIfSpeedIsReported() {
        TrainingSessionRepository repo = mock(TrainingSessionRepository.class);
        TrainingSessionResponse runningOnlySpeed = new TrainingSessionResponse(
                1, 10, 1, "Base", 100, "Horse 100", 5, "Groom 5",
                LocalDateTime.now(), 1600, null, null, null, null, null, new BigDecimal("16.0"), null, null,
                "RUNNING", 180, 0, false, null);
        when(repo.findById(1)).thenReturn(Optional.of(runningOnlySpeed));
        TrainingSessionService service = sessionService(repo, mock(TrainingPlanRepository.class),
                mock(HorseRepository.class), mock(HorseVitalsRepository.class));

        ApiException ex = expect(ApiException.class, 409,
                () -> service.finish(1, new FinishSessionRequest(true, "TARGET_REACHED"), GROOM, "ip"));
        assertTrue(ex.getMessage().contains("heart rate and speed at least once"), ex.getMessage());
        verify(repo, never()).markStoppedEarly(anyInt(), any());
    }

    @Test
    void groomCannotStopSessionForInjuryWithoutBothMetrics() {
        IncidentRepository incidents = mock(IncidentRepository.class);
        IncidentRepository.SessionForStop runningNoSpeed = new IncidentRepository.SessionForStop(
                1, 100, "Horse 100", 7, 5, "RUNNING", 175, null);
        when(incidents.findSessionForStop(1)).thenReturn(Optional.of(runningNoSpeed));

        IncidentService service = new IncidentService(incidents, mock(UserRepository.class),
                mock(NotificationService.class), mock(RaceRiskNotifier.class));

        StopSessionRequest req = new StopSessionRequest("Limping on right front leg", "Sudden lameness", "HIGH");
        ApiException ex = expect(ApiException.class, 409, () -> service.stopSession(GROOM, 1, req, "ip"));
        assertTrue(ex.getMessage().contains("both heart rate and speed at least once"), ex.getMessage());
        verify(incidents, never()).markStopped(anyInt(), any());
    }

    @Test
    void groomStopsSessionForInjuryWithBothMetricsAndNotifiesVet() {
        IncidentRepository incidents = mock(IncidentRepository.class);
        UserRepository users = mock(UserRepository.class);
        NotificationService notifications = mock(NotificationService.class);
        IncidentRepository.SessionForStop runningWithBoth = new IncidentRepository.SessionForStop(
                1, 100, "Horse 100", 7, 5, "RUNNING", 175, new BigDecimal("15.4"));
        when(incidents.findSessionForStop(1)).thenReturn(Optional.of(runningWithBoth));
        when(incidents.markStopped(eq(1), any())).thenReturn(1);
        when(incidents.insertReport(eq(100), eq(5), eq(1), any(), any(), eq("HIGH"))).thenReturn(88);
        when(incidents.findById(88)).thenReturn(Optional.of(openReport(88, 100)));

        IncidentService service = new IncidentService(incidents, users, notifications, mock(RaceRiskNotifier.class));

        StopSessionRequest req = new StopSessionRequest("Limping on right front leg", "Sudden lameness", "HIGH");
        var res = service.stopSession(GROOM, 1, req, "ip");
        assertEquals("STOPPED_EARLY", res.sessionStatus());
        verify(incidents).markStopped(eq(1), argThat(r -> r.contains("Horse injury / Vet called")));
        verify(notifications).notifyRole(eq("VETERINARIAN"), any(), argThat(n -> "INCIDENT_REPORTED".equals(n.type())));
    }

    @Test
    void nonHeadTrainerCannotScheduleSession() {
        TrainingSessionService service = sessionService(mock(TrainingSessionRepository.class),
                mock(TrainingPlanRepository.class), mock(HorseRepository.class), mock(HorseVitalsRepository.class));

        ApiException ex = expect(ApiException.class, 403, () -> service.create(
                new TrainingSessionRequest(10, 5, TODAY.plusDays(1).atTime(9, 0), 1600, 60), GROOM, "ip"));
        assertTrue(ex.getMessage().contains("Only Head Trainer can schedule training sessions"), ex.getMessage());
    }

    @Test
    void nonHeadTrainerCannotRescheduleSession() {
        TrainingSessionService service = sessionService(mock(TrainingSessionRepository.class),
                mock(TrainingPlanRepository.class), mock(HorseRepository.class), mock(HorseVitalsRepository.class));

        ApiException ex = expect(ApiException.class, 403, () -> service.reschedule(
                1, new TrainingSessionScheduleRequest(5, TODAY.plusDays(1).atTime(9, 0), 60), GROOM, "ip"));
        assertTrue(ex.getMessage().contains("Only Head Trainer can reschedule training sessions"), ex.getMessage());
    }

    @Test
    void nonHeadTrainerOrManagerCannotCancelSession() {
        TrainingSessionService service = sessionService(mock(TrainingSessionRepository.class),
                mock(TrainingPlanRepository.class), mock(HorseRepository.class), mock(HorseVitalsRepository.class));

        ApiException ex = expect(ApiException.class, 403, () -> service.cancel(1, GROOM, "ip"));
        assertTrue(ex.getMessage().contains("Only Head Trainer or Club Manager can cancel"), ex.getMessage());
    }

    @Test
    void nonHeadTrainerCannotEvaluateSession() {
        SessionRepository repo = mock(SessionRepository.class);
        SessionService service = new SessionService(repo, mock(UserRepository.class), mock(NotificationService.class), null);

        EvaluateSessionRequest evalReq = new EvaluateSessionRequest(1600, 60, 140, 175, 110, new BigDecimal("16.4"), "EXCELLENT", "Groom evaluation", "COMPLETED");
        ApiException ex = expect(ApiException.class, 403, () -> service.evaluate(GROOM, 1, evalReq, "ip"));
        assertTrue(ex.getMessage().contains("Only Head Trainer can evaluate training sessions"), ex.getMessage());
        verify(repo, never()).saveEvaluation(anyInt(), any(), any(), any(), anyInt());
    }

    @Test
    void nonGroomOrTrainerCannotStopSession() {
        IncidentRepository incidents = mock(IncidentRepository.class);
        IncidentService service = new IncidentService(incidents, mock(UserRepository.class), mock(NotificationService.class), mock(RaceRiskNotifier.class));

        StopSessionRequest req = new StopSessionRequest("Limping", null, "HIGH");
        ApiException ex = expect(ApiException.class, 403, () -> service.stopSession(OWNER, 1, req, "ip"));
        assertTrue(ex.getMessage().contains("Only Groom or Head Trainer can stop"), ex.getMessage());
        verify(incidents, never()).markStopped(anyInt(), any());
    }

    @Test
    void periodicTrainingChecksTriggerOverdueSessionsAndReviews() {
        TrainingSessionService trainingSessionService = mock(TrainingSessionService.class);
        SessionService sessionService = mock(SessionService.class);
        MissedTaskJob job = new MissedTaskJob(mock(CareService.class), mock(TrainingPlanService.class),
                mock(TrainingSessionRepository.class), trainingSessionService, sessionService, true);

        job.runPeriodicTrainingChecks();
        verify(trainingSessionService).processOverdueSessions();
        verify(sessionService).processOverdueReviews();
    }

    // ---------------------------------------------------------------- Flow 2: cảnh báo

    private static AlertService alertService(AlertRepository alerts, NotificationService notifications) {
        return alertService(alerts, notifications, mock(IncidentService.class));
    }

    private static AlertService alertService(AlertRepository alerts, NotificationService notifications,
                                             IncidentService incidents) {
        return new AlertService(alerts, mock(UserRepository.class), notifications, incidents,
                new BigDecimal("18.0"));
    }

    @Test
    void stoppingASessionFromAnAlertOpensAnIncidentForTheVet() {
        AlertRepository alerts = mock(AlertRepository.class);
        IncidentService incidents = mock(IncidentService.class);
        AlertResponse alert = new AlertResponse(9, 1, 100, "Horse 100", "HEART_RATE", new BigDecimal("180"),
                new BigDecimal("205"), "HIGH", Instant.now(), null, null, null, false);
        when(alerts.findById(9)).thenReturn(Optional.of(alert));
        when(alerts.resolve(9, "STOP_SESSION", 2)).thenReturn(true);

        alertService(alerts, mock(NotificationService.class), incidents)
                .resolve(TRAINER, 9, new ResolveAlertRequest("STOP_SESSION"), "ip");

        // Mức HIGH của cảnh báo thành mức khẩn của báo cáo; IncidentGuard chặn ngựa tới khi bác sĩ khám.
        verify(incidents).stopFromAlert(eq(TRAINER), eq(1), any(), eq("HIGH"), eq("ip"));
    }

    private static AlertResponse highHeartRateAlert() {
        return new AlertResponse(9, 1, 100, "Horse 100", "HEART_RATE", new BigDecimal("180"),
                new BigDecimal("205"), "HIGH", Instant.now(), null, null, null, false);
    }

    @Test
    void reduceIntensityReachesTheGroomOnTheTrack() {
        AlertRepository alerts = mock(AlertRepository.class);
        NotificationService notifications = mock(NotificationService.class);
        when(alerts.findById(9)).thenReturn(Optional.of(highHeartRateAlert()));
        when(alerts.resolve(9, "REDUCE_INTENSITY", 2)).thenReturn(true);
        when(alerts.findSessionContext(1)).thenReturn(Optional.of(new SessionContext(1, 5, "RUNNING", 180)));

        alertService(alerts, notifications, mock(IncidentService.class))
                .resolve(TRAINER, 9, new ResolveAlertRequest("REDUCE_INTENSITY"), "ip");

        verify(notifications).notifyUser(eq(5), argThat(n -> "TRAINING_INTENSITY_REDUCED".equals(n.type())));
    }

    @Test
    void continueDoesNotDisturbTheGroom() {
        AlertRepository alerts = mock(AlertRepository.class);
        NotificationService notifications = mock(NotificationService.class);
        when(alerts.findById(9)).thenReturn(Optional.of(highHeartRateAlert()));
        when(alerts.resolve(9, "CONTINUE", 2)).thenReturn(true);
        when(alerts.findSessionContext(1)).thenReturn(Optional.of(new SessionContext(1, 5, "RUNNING", 180)));

        alertService(alerts, notifications, mock(IncidentService.class))
                .resolve(TRAINER, 9, new ResolveAlertRequest("CONTINUE"), "ip");

        verify(notifications, never()).notifyUser(anyInt(), any());
    }

    @Test
    void heartRateTenPercentOverCeilingIsHighAndReachesTheVet() {
        AlertRepository alerts = mock(AlertRepository.class);
        NotificationService notifications = mock(NotificationService.class);
        when(alerts.findSessionContext(1)).thenReturn(Optional.of(new SessionContext(1, 5, "RUNNING", 180)));
        when(alerts.latestOpenSeverity(eq(1), eq("HEART_RATE"), anyInt())).thenReturn(Optional.empty());
        when(alerts.insertAlert(eq(1), eq("HEART_RATE"), any(), any(), eq("HIGH"))).thenReturn(9);

        VitalsResult r = alertService(alerts, notifications).recordVitals(GROOM, 1, new VitalsRequest(205, null));

        assertTrue(r.alertRaised());
        assertEquals("HIGH", r.severity());
        verify(notifications).notifyRole(eq("VETERINARIAN"), eq(GROOM), any());
    }

    @Test
    void speedOverTheLimitRaisesAVelocityAlert() {
        AlertRepository alerts = mock(AlertRepository.class);
        when(alerts.findSessionContext(1)).thenReturn(Optional.of(new SessionContext(1, 5, "RUNNING", 180)));
        when(alerts.latestOpenSeverity(eq(1), eq("VELOCITY"), anyInt())).thenReturn(Optional.empty());
        when(alerts.insertAlert(eq(1), eq("VELOCITY"), any(), any(), any())).thenReturn(11);

        VitalsResult r = alertService(alerts, mock(NotificationService.class))
                .recordVitals(GROOM, 1, new VitalsRequest(150, new BigDecimal("19.5")));

        assertTrue(r.alertRaised());
        assertEquals(11, r.alertId());
        verify(alerts).insertAlert(eq(1), eq("VELOCITY"), any(), any(), any());
    }

    // ---------------------------------------------------------------- Flow 3: y tế

    @Test
    void injuredHorseWithAnOpenInjuryCannotBeMovedToMonitor() {
        HorseRepository horses = mock(HorseRepository.class);
        InjuryRepository injuries = mock(InjuryRepository.class);
        when(horses.findById(100)).thenReturn(Optional.of(horse(100, "INJURED", TODAY.minusYears(4), "COLT")));
        when(injuries.findOpenInjuries(100)).thenReturn(List.of(
                new InjuryRepository.OpenInjury(1, "Left fore", "GRADE_2", "TREATING")));
        MedicalRecordService service = new MedicalRecordService(mock(MedicalRecordRepository.class), horses,
                mock(UserRepository.class), mock(NotificationService.class), mock(RaceRiskNotifier.class), injuries);

        ApiException ex = expect(ApiException.class, 400, () -> service.log(new MedicalExamRequest(
                100, null, "EXAM", LocalDateTime.now(), "Better", "MONITOR", false, null, null, null, null), VET, "ip"));
        assertTrue(ex.getFieldErrors().containsKey("healthStatus"), ex.getFieldErrors().toString());
    }

    private static IncidentReportResponse openReport(int reportId, int horseId) {
        return new IncidentReportResponse(reportId, horseId, "Horse " + horseId, "A-" + horseId, "INJURED", 7,
                30, 5, "Groom 5", "Stopped early, looked lame", null, null, "MEDIUM", Instant.now(),
                "PENDING", null, null, null, null, null);
    }

    private static IncidentService incidentService(IncidentRepository incidents) {
        return new IncidentService(incidents, mock(UserRepository.class), mock(NotificationService.class),
                mock(RaceRiskNotifier.class));
    }

    @Test
    void headTrainerDismissesAFalseAlarmBeforeTheVetTakesIt() {
        IncidentRepository incidents = mock(IncidentRepository.class);
        when(incidents.findById(8)).thenReturn(Optional.of(openReport(8, 100)));
        when(incidents.markDismissed(8, 2, "Tripped on a pebble, walking sound")).thenReturn(1);
        IncidentService service = incidentService(incidents);

        service.dismiss(TRAINER, 8, "  Tripped on a pebble, walking sound ", "ip");
        verify(incidents).markDismissed(8, 2, "Tripped on a pebble, walking sound");
    }

    @Test
    void headTrainerCannotDismissACaseTheVetHasTaken() {
        IncidentRepository incidents = mock(IncidentRepository.class);
        IncidentReportResponse open = openReport(8, 100);
        IncidentReportResponse received = new IncidentReportResponse(8, 100, open.horseName(), open.stallNo(),
                open.horseStatus(), 7, 30, 5, "Groom 5", open.symptoms(), null, null, "MEDIUM", Instant.now(),
                "RECEIVED", 3, "Vet 3", Instant.now(), null, null);
        when(incidents.findById(8)).thenReturn(Optional.of(received));
        IncidentService service = incidentService(incidents);

        expect(ApiException.class, 409, () -> service.dismiss(TRAINER, 8, "False alarm", "ip"));
        verify(incidents, never()).markDismissed(anyInt(), anyInt(), any());
    }

    private record Clearance(HealthClearanceService service, MedicalRecordRepository records,
                             HealthClearanceRepository clearance, IncidentRepository incidents) {
    }

    private static Clearance injuredHorseWithHealedInjury() {
        MedicalRecordRepository records = mock(MedicalRecordRepository.class);
        HealthClearanceRepository clearance = mock(HealthClearanceRepository.class);
        InjuryRepository injuries = mock(InjuryRepository.class);
        TrainingLockRepository locks = mock(TrainingLockRepository.class);
        HorseRepository horses = mock(HorseRepository.class);
        IncidentRepository incidents = mock(IncidentRepository.class);
        when(horses.findById(100)).thenReturn(Optional.of(horse(100, "INJURED", TODAY.minusYears(4), "COLT")));
        when(injuries.findHorseOfInjury(1)).thenReturn(Optional.of(100));
        when(injuries.markRecovered(1)).thenReturn(1);
        when(injuries.findOpenInjuries(100)).thenReturn(List.of());
        when(records.findByHorse(100)).thenReturn(List.of());
        when(locks.findActiveLockIds(100)).thenReturn(List.of(3));
        when(records.insertClearance(any(), eq(VET.userId()))).thenReturn(50);
        when(records.findById(50)).thenReturn(Optional.of(new MedicalRecordRow(50, 100, "Horse 100", VET.userId(),
                "Vet", null, "EXAM", LocalDateTime.now(), "Healed", "ELIGIBLE", false, null, null, null)));
        when(clearance.clearHorse(100)).thenReturn(1);
        HealthClearanceService service = new HealthClearanceService(records, clearance, injuries, locks, horses,
                mock(UserRepository.class), mock(NotificationService.class), incidents);
        return new Clearance(service, records, clearance, incidents);
    }

    private static RecoveryConfirmationRequest recovery(List<Integer> answeredReportIds) {
        return new RecoveryConfirmationRequest(100, List.of(1), answeredReportIds, LocalDateTime.now(),
                "Healed", null);
    }

    @Test
    void recoveryIsRefusedWhileAnotherIncidentReportIsStillOpen() {
        Clearance c = injuredHorseWithHealedInjury();
        when(c.incidents().findOpenByHorse(100)).thenReturn(List.of(openReport(8, 100)));

        ApiException ex = expect(ApiException.class, 400, () -> c.service().confirm(recovery(null), VET, "ip"));
        assertTrue(ex.getFieldErrors().containsKey("answeredReportIds"), ex.getFieldErrors().toString());
        assertTrue(ex.getMessage().contains("#8"), ex.getMessage());
        verify(c.clearance(), never()).clearHorse(anyInt());
    }

    @Test
    void recoveryClosesTheIncidentReportsItAnswers() {
        Clearance c = injuredHorseWithHealedInjury();
        when(c.records().findReportHead(8)).thenReturn(Optional.of(new MedicalRecordRepository.ReportHead(100, "PENDING")));
        when(c.records().markReportExamined(8)).thenReturn(1);
        when(c.incidents().findOpenByHorse(100)).thenReturn(List.of());

        RecoveryConfirmationResponse res = c.service().confirm(recovery(List.of(8)), VET, "ip");

        assertEquals(List.of(8), res.reportsClosed());
        assertEquals(List.of(1), res.injuriesClosed());
        verify(c.records()).markReportExamined(8);
        verify(c.clearance()).clearHorse(100);
    }

    @Test
    void recoveryCannotCloseAnotherHorsesIncidentReport() {
        Clearance c = injuredHorseWithHealedInjury();
        when(c.records().findReportHead(9)).thenReturn(Optional.of(new MedicalRecordRepository.ReportHead(200, "PENDING")));

        ApiException ex = expect(ApiException.class, 400, () -> c.service().confirm(recovery(List.of(9)), VET, "ip"));
        assertTrue(ex.getFieldErrors().containsKey("answeredReportIds"), ex.getFieldErrors().toString());
        verify(c.records(), never()).markReportExamined(anyInt());
        verify(c.clearance(), never()).clearHorse(anyInt());
    }

    @Test
    void treatmentThatHasNotStartedCannotEndToday() {
        TreatmentRepository treatments = mock(TreatmentRepository.class);
        when(treatments.findById(1)).thenReturn(Optional.of(new TreatmentRow(1, 1, 100, "Horse 100", "Bute", null,
                null, null, TODAY.plusDays(5), TODAY.plusDays(10), false)));
        TreatmentService service = new TreatmentService(treatments, mock(MedicalRecordRepository.class),
                mock(HorseRepository.class), mock(UserRepository.class));

        expect(ApiException.class, 409, () -> service.end(1, VET, "ip"));
        verify(treatments, never()).endToday(anyInt());
    }

    // ---------------------------------------------------------------- Flow 1: phả hệ

    @Test
    void parentMustBeBornAtLeastTwoYearsBeforeTheFoal() {
        HorseRepository horses = mock(HorseRepository.class);
        HorseVitalsRepository vitals = mock(HorseVitalsRepository.class);
        when(horses.findById(100)).thenReturn(Optional.of(horse(100, "ELIGIBLE", TODAY.minusYears(10), "STALLION")));
        when(horses.findById(200)).thenReturn(Optional.of(horse(200, "ELIGIBLE", TODAY.minusYears(2), "COLT")));
        when(horses.findAll()).thenReturn(List.of());
        when(vitals.findLatestSession(100)).thenReturn(Optional.empty());
        HorseService service = new HorseService(horses, vitals, mock(UserRepository.class));

        ApiException ex = expect(ApiException.class, 400, () -> service.updateByOwner(100, new HorseUpdateRequest(
                "Horse 100", "TB", "STALLION", TODAY.minusYears(10), "Bay", 200, null), OWNER, "ip"));
        assertTrue(ex.getFieldErrors().containsKey("sireId"), ex.getFieldErrors().toString());
    }

    // ---------------------------------------------------------------- Flow 5: đua

    private static RaceModels.RaceEvent raceEvent(String status) {
        return new RaceModels.RaceEvent(50, "Japan Cup", TODAY.plusDays(20), 2400, "Tokyo",
                TODAY.plusDays(13), new BigDecimal("50000000"), 18, status, null, 3);
    }

    private static RaceModels.Entry entry(int id, String state, String approval, LocalDate raceDate) {
        return new RaceModels.Entry(id, 100, "Horse 100", "A-100", 7, "Owner", "Japan Cup", raceDate, 2, "Trainer",
                null, null, null, state, 50, 2400, "Tokyo", "OPEN", null, null, null, approval, null);
    }

    private static RaceService raceService(RaceRepository races, HorseRepository horses) {
        return raceService(races, horses, mock(ExpenseRepository.class));
    }

    private static RaceService raceService(RaceRepository races, HorseRepository horses, ExpenseRepository expenses) {
        return new RaceService(races, horses, mock(HorseVitalsRepository.class), mock(IncidentRepository.class),
                mock(UserRepository.class), mock(NotificationService.class), mock(PreventiveCareService.class),
                expenses, 14, 30, 7);
    }

    private static RaceModels.RaceEvent raceWithDeadline(LocalDate deadline) {
        return new RaceModels.RaceEvent(50, "Japan Cup", TODAY.plusDays(20), 2400, "Tokyo",
                deadline, new BigDecimal("50000000"), 18, "OPEN", null, 3);
    }

    @Test
    void approvingAnEntryChargesTheFeeToTheHorse() {
        RaceRepository races = mock(RaceRepository.class);
        HorseRepository horses = mock(HorseRepository.class);
        ExpenseRepository expenses = mock(ExpenseRepository.class);
        when(races.findById(1)).thenReturn(Optional.of(entry(1, "PENDING_APPROVAL", "PENDING_OWNER", TODAY.plusDays(20))));
        when(races.findRace(50)).thenReturn(Optional.of(raceEvent("OPEN")));
        when(horses.findById(100)).thenReturn(Optional.of(horse(100, "ELIGIBLE", TODAY.minusYears(4), "COLT")));
        when(races.updateApproval(1, "APPROVED", null)).thenReturn(1);
        when(expenses.insert(any(), eq(2))).thenReturn(77);

        raceService(races, horses, expenses).approveEntry(1, OWNER, "ip");

        verify(expenses).insert(argThat(r -> "OTHER".equals(r.category()) && r.horseId() == 100
                && r.amount().compareTo(new BigDecimal("50000000")) == 0), eq(2));
        verify(races).setFeeExpense(1, 77);
    }

    @Test
    void withdrawingBeforeTheDeadlineRefundsTheFee() {
        RaceRepository races = mock(RaceRepository.class);
        ExpenseRepository expenses = mock(ExpenseRepository.class);
        when(races.findById(1)).thenReturn(Optional.of(entry(1, "UPCOMING", "APPROVED", TODAY.plusDays(20))));
        when(races.findRace(50)).thenReturn(Optional.of(raceWithDeadline(TODAY.plusDays(13))));
        when(races.scratch(eq(1), any())).thenReturn(1);
        when(races.findFeeExpense(1)).thenReturn(Optional.of(77));

        raceService(races, mock(HorseRepository.class), expenses).scratch(1, "Sore hoof", TRAINER, "ip");
        verify(expenses).cancel(77, 2, "Withdrawn before entries closed");
    }

    @Test
    void withdrawingAfterTheDeadlineKeepsTheFee() {
        RaceRepository races = mock(RaceRepository.class);
        ExpenseRepository expenses = mock(ExpenseRepository.class);
        when(races.findById(1)).thenReturn(Optional.of(entry(1, "UPCOMING", "APPROVED", TODAY.plusDays(20))));
        when(races.findRace(50)).thenReturn(Optional.of(raceWithDeadline(TODAY.minusDays(1))));
        when(races.scratch(eq(1), any())).thenReturn(1);
        when(races.findFeeExpense(1)).thenReturn(Optional.of(77));

        raceService(races, mock(HorseRepository.class), expenses).scratch(1, "Sore hoof", TRAINER, "ip");
        verify(expenses, never()).cancel(anyInt(), anyInt(), any());
    }

    @Test
    void cancelledRaceRefundsTheFeeEvenAfterTheDeadline() {
        RaceRepository races = mock(RaceRepository.class);
        ExpenseRepository expenses = mock(ExpenseRepository.class);
        when(races.findRace(50)).thenReturn(Optional.of(raceWithDeadline(TODAY.minusDays(1))));
        when(races.cancelRace(eq(50), any())).thenReturn(1);
        when(races.openEntries(50)).thenReturn(List.of(entry(1, "UPCOMING", "APPROVED", TODAY.plusDays(20))));
        when(races.scratch(eq(1), any())).thenReturn(1);
        when(races.findFeeExpense(1)).thenReturn(Optional.of(77));

        raceService(races, mock(HorseRepository.class), expenses).cancelRace(50, "Track flooded", MANAGER, "ip");
        verify(expenses).cancel(77, 1, "Race cancelled by the club");
    }

    @Test
    void resultCannotBeRecordedForAnEntryTheOwnerNeverApproved() {
        RaceRepository races = mock(RaceRepository.class);
        when(races.findById(1)).thenReturn(Optional.of(entry(1, "PENDING_APPROVAL", "PENDING_OWNER", TODAY.minusDays(1))));
        RaceService service = raceService(races, mock(HorseRepository.class));

        expect(ApiException.class, 409, () -> service.recordResult(1,
                new RaceModels.ResultRequest("WON", new BigDecimal("100000000"), null, 1, null, null), TRAINER, "ip"));
        verify(races, never()).saveResult(anyInt(), any(), any(), any(), any(), any());
    }

    @Test
    void ownerCannotApproveAnEntryForACancelledRace() {
        RaceRepository races = mock(RaceRepository.class);
        when(races.findById(1)).thenReturn(Optional.of(entry(1, "PENDING_APPROVAL", "PENDING_OWNER", TODAY.plusDays(20))));
        when(races.findRace(50)).thenReturn(Optional.of(raceEvent("CANCELLED")));
        RaceService service = raceService(races, mock(HorseRepository.class));

        expect(ApiException.class, 409, () -> service.approveEntry(1, OWNER, "ip"));
        verify(races, never()).updateApproval(anyInt(), any(), any());
    }

    @Test
    void declinedEntryCanBeProposedAgain() {
        RaceRepository races = mock(RaceRepository.class);
        HorseRepository horses = mock(HorseRepository.class);
        when(horses.findById(100)).thenReturn(Optional.of(horse(100, "ELIGIBLE", TODAY.minusYears(4), "COLT")));
        when(races.findRace(50)).thenReturn(Optional.of(raceEvent("OPEN")));
        when(races.findEntry(100, 50)).thenReturn(Optional.of(new RaceRepository.ExistingEntry(9, null, "REJECTED")));
        when(races.reinstate(9, 2, null)).thenReturn(1);
        when(races.findById(9)).thenReturn(Optional.of(entry(9, "PENDING_APPROVAL", "PENDING_OWNER", TODAY.plusDays(20))));
        RaceService service = raceService(races, horses);

        service.register(new RaceModels.RegisterRequest(100, 50, null), TRAINER, "ip");
        verify(races).reinstate(9, 2, null);
        verify(races, never()).insert(anyInt(), any(), anyInt(), any());
    }

    // ---------------------------------------------------------------- Flow 4: phân công chăm sóc

    private static CareService careService(CareRepository care, HorseRepository horses,
                                           TrainingSessionRepository sessions, NotificationService notifications) {
        return new CareService(care, horses, sessions, mock(UserRepository.class), notifications,
                mock(SupplyService.class), 1, 12);
    }

    @Test
    void bulkAssignCoversEveryHorseAndDayAndSkipsWhatIsPlanned() {
        CareRepository care = mock(CareRepository.class);
        HorseRepository horses = mock(HorseRepository.class);
        TrainingSessionRepository sessions = mock(TrainingSessionRepository.class);
        NotificationService notifications = mock(NotificationService.class);
        when(sessions.isActiveGroom(5)).thenReturn(true);
        when(horses.findById(100)).thenReturn(Optional.of(horse(100, "ELIGIBLE", TODAY.minusYears(4), "COLT")));
        when(horses.findById(101)).thenReturn(Optional.of(horse(101, "INJURED", TODAY.minusYears(5), "FILLY")));
        when(care.taskExists(100, "CLEANING", TODAY)).thenReturn(true);

        CareModels.BulkAssignResult r = careService(care, horses, sessions, notifications).assignBulk(
                new CareModels.BulkAssignRequest(List.of(100, 101), 5, TODAY, TODAY.plusDays(1),
                        List.of("CLEANING"), null), TRAINER, "ip");

        assertEquals(3, r.created());
        assertEquals(1, r.skipped());
        verify(notifications).notifyUser(eq(5), any());
    }

    @Test
    void bulkAssignIsLimitedToTwoWeeks() {
        TrainingSessionRepository sessions = mock(TrainingSessionRepository.class);
        when(sessions.isActiveGroom(5)).thenReturn(true);
        CareRepository care = mock(CareRepository.class);
        CareService service = careService(care, mock(HorseRepository.class), sessions,
                mock(NotificationService.class));

        ApiException ex = expect(ApiException.class, 400, () -> service.assignBulk(
                new CareModels.BulkAssignRequest(List.of(100), 5, TODAY, TODAY.plusDays(14),
                        List.of("CLEANING"), null), TRAINER, "ip"));
        assertTrue(ex.getFieldErrors().containsKey("toDate"), ex.getFieldErrors().toString());
        verify(care, never()).insertTask(anyInt(), any(), anyInt(), any(), any());
    }

    @Test
    void missedTaskOfAnEarlierDayCannotBeDeleted() {
        CareRepository care = mock(CareRepository.class);
        when(care.findTask(1)).thenReturn(Optional.of(new CareModels.Task(1, 100, "Horse 100", "A-100", "CLEANING",
                5, "Groom 5", TODAY.minusDays(1), false, null, null, null, null, "MISSED")));
        CareService service = careService(care, mock(HorseRepository.class), mock(TrainingSessionRepository.class),
                mock(NotificationService.class));

        expect(ApiException.class, 409, () -> service.delete(1, TRAINER, "ip"));
        verify(care, never()).deleteOpenTask(anyInt());
    }
}
