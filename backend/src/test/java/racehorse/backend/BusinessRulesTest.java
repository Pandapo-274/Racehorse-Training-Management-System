package racehorse.backend;

import static org.junit.jupiter.api.Assertions.assertEquals;
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
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
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
import racehorse.backend.auth.ApiException;
import racehorse.backend.auth.AuthenticatedUser;
import racehorse.backend.auth.UserRepository;
import racehorse.backend.expense.ExpenseRepository;
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
import racehorse.backend.training.TrainingPlanRepository;
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

    private static TrainingSessionResponse session(int id, int horseId, int groomId, LocalDateTime when,
                                                   String status, Integer distance) {
        return new TrainingSessionResponse(id, 10, 1, "Base", horseId, "Horse " + horseId, groomId, "Groom " + groomId,
                when, distance, null, null, null, null, null, null, null, null, status, 180, 0, false, null);
    }

    private static TrainingPlanResponse plan(String status, LocalDate end) {
        return new TrainingPlanResponse(1, 100, "Horse 100", "Plan", end.minusDays(30), end, status, 2, "Trainer",
                LocalDateTime.now(), null, null, List.of());
    }

    private static TrainingPlanService planService(TrainingPlanRepository plans, HorseRepository horses,
                                                   HorseVitalsRepository vitals, NotificationService notifications) {
        return new TrainingPlanService(plans, horses, vitals, mock(UserRepository.class), notifications);
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
        when(horses.findById(100)).thenReturn(Optional.of(horse(100, "ELIGIBLE", TODAY.minusYears(4), "COLT")));
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
        when(horses.findById(100)).thenReturn(Optional.of(horse(100, "ELIGIBLE", TODAY.minusYears(4), "COLT")));
        TrainingPlanService service = planService(plans, horses, mock(HorseVitalsRepository.class), notifications);

        service.changeStatus(1, "PENDING_APPROVAL", TRAINER, "ip");
        verify(notifications).notifyUser(eq(7), any());   // chủ của ngựa 100
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
        when(horses.findById(100)).thenReturn(Optional.of(horse(100, "ELIGIBLE", TODAY.minusYears(4), "COLT")));
        when(vitals.findActiveLock(100)).thenReturn(Optional.empty());
        when(plans.findPhases(1)).thenReturn(List.of(new TrainingPlanResponse.Phase(
                10, 1, "Base", 1600, "Moderate", "TURF", 3, 180, TODAY, TODAY.plusDays(30), minGap, null)));
        return repo;
    }

    @Test
    void phaseRestGapReplacesTheFixedNinetyMinutes() {
        HorseRepository horses = mock(HorseRepository.class);
        HorseVitalsRepository vitals = mock(HorseVitalsRepository.class);
        TrainingPlanRepository plans = mock(TrainingPlanRepository.class);
        // Giai đoạn cho nghỉ 20 phút: đi bộ khởi động 09:00 rồi nước rút 09:30, cùng Groom.
        TrainingSessionRepository repo = readyToSchedule(horses, vitals, plans, 20);
        LocalDateTime nine = TODAY.plusDays(1).atTime(9, 0);
        List<TrainingSessionResponse> warmUp = List.of(session(1, 100, 5, nine, "SCHEDULED", 400));
        when(repo.findAll(100, null, null, null, false)).thenReturn(warmUp);
        when(repo.findAll(null, null, 5, null, false)).thenReturn(warmUp);
        when(repo.insert(10, 100, 5, nine.plusMinutes(30), 1600)).thenReturn(2);
        when(repo.findById(2)).thenReturn(Optional.of(session(2, 100, 5, nine.plusMinutes(30), "SCHEDULED", 1600)));
        TrainingSessionService service = sessionService(repo, plans, horses, vitals);

        service.create(new TrainingSessionRequest(10, 5, nine.plusMinutes(30), null), TRAINER, "ip");
        verify(repo).insert(10, 100, 5, nine.plusMinutes(30), 1600);
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
        LocalDateTime nine = TODAY.plusDays(1).atTime(9, 0);
        // Giai đoạn cho 3 buổi/tuần; tuần đó đã có 3 buổi (cách nhau xa, không trùng giờ).
        when(repo.findAll(100, null, null, null, false)).thenReturn(List.of(
                session(1, 100, 5, nine.minusHours(4), "COMPLETED", 1600),
                session(2, 100, 5, nine.plusHours(4), "SCHEDULED", 1600),
                session(3, 100, 5, nine.plusHours(8), "SCHEDULED", 1600)));
        when(repo.findAll(null, null, 5, null, false)).thenReturn(List.of());
        TrainingSessionService service = sessionService(repo, plans, horses, vitals);

        ApiException ex = expect(ApiException.class, 409,
                () -> service.create(new TrainingSessionRequest(10, 5, nine, null), TRAINER, "ip"));
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
        when(repo.updateSchedule(1, 6, earlyToday)).thenReturn(1);
        TrainingSessionService service = sessionService(repo, plans, horses, vitals);

        service.reschedule(1, new TrainingSessionScheduleRequest(6, earlyToday), TRAINER, "ip");
        verify(repo).updateSchedule(1, 6, earlyToday);
    }

    @Test
    void groomFinishesTheirOwnRunningSession() {
        TrainingSessionRepository repo = mock(TrainingSessionRepository.class);
        when(repo.findById(1)).thenReturn(Optional.of(session(1, 100, 5, LocalDateTime.now(), "RUNNING", 1600)));
        when(repo.markFinished(1)).thenReturn(1);
        TrainingSessionService service = sessionService(repo, mock(TrainingPlanRepository.class),
                mock(HorseRepository.class), mock(HorseVitalsRepository.class));

        service.finish(1, GROOM, "ip");
        verify(repo).markFinished(1);
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

    /* Ngựa 100 có hai báo cáo: #7 là chấn thương thật (đã khám kèm reportId nên
       đã đóng), #8 là lần Groom dừng buổi tập mà hoá ra bình thường, chưa ai
       khám kèm. Chấn thương #1 đã lành. */

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
        // Không được báo "cleared to train" khi IncidentGuard vẫn chặn tập.
        verify(c.clearance(), never()).clearHorse(anyInt());
    }

    @Test
    void recoveryClosesTheIncidentReportsItAnswers() {
        Clearance c = injuredHorseWithHealedInjury();
        when(c.records().findReportHead(8)).thenReturn(Optional.of(new MedicalRecordRepository.ReportHead(100, "PENDING")));
        when(c.records().markReportExamined(8)).thenReturn(1);
        when(c.incidents().findOpenByHorse(100)).thenReturn(List.of()); // #8 vừa đóng trong cùng giao dịch

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

    /** Một đăng ký của ngựa 100 (chủ 7) vào giải #50. */
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

    /** Giải #50, lệ phí 50 triệu, hạn đăng ký = deadline. */
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
        // Chủ đã từ chối lần trước: dòng cũ còn đó, chưa bị rút.
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
        // Ngựa 100 hôm nay đã có người dọn chuồng.
        when(care.taskExists(100, "CLEANING", TODAY)).thenReturn(true);

        CareModels.BulkAssignResult r = careService(care, horses, sessions, notifications).assignBulk(
                new CareModels.BulkAssignRequest(List.of(100, 101), 5, TODAY, TODAY.plusDays(1),
                        List.of("CLEANING"), null), TRAINER, "ip");

        assertEquals(3, r.created());   // 2 ngựa x 2 ngày - 1 việc đã có
        assertEquals(1, r.skipped());
        verify(notifications).notifyUser(eq(5), any());   // một thông báo tóm tắt, không phải 3
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
