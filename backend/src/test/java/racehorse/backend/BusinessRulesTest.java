package racehorse.backend;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
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
import racehorse.backend.alert.AlertService;
import racehorse.backend.alert.SessionContext;
import racehorse.backend.alert.VitalsRequest;
import racehorse.backend.alert.VitalsResult;
import racehorse.backend.auth.ApiException;
import racehorse.backend.auth.AuthenticatedUser;
import racehorse.backend.auth.UserRepository;
import racehorse.backend.horse.HorseRepository;
import racehorse.backend.horse.HorseResponse;
import racehorse.backend.horse.HorseService;
import racehorse.backend.horse.HorseUpdateRequest;
import racehorse.backend.horse.HorseVitalsRepository;
import racehorse.backend.horse.HorseVitalsResponse;
import racehorse.backend.incident.IncidentGuard;
import racehorse.backend.medical.InjuryRepository;
import racehorse.backend.medical.MedicalExamRequest;
import racehorse.backend.medical.MedicalRecordRepository;
import racehorse.backend.medical.MedicalRecordService;
import racehorse.backend.medical.TreatmentRepository;
import racehorse.backend.medical.TreatmentRow;
import racehorse.backend.medical.TreatmentService;
import racehorse.backend.notification.NotificationService;
import racehorse.backend.race.RaceRiskNotifier;
import racehorse.backend.training.TrainingPlanRepository;
import racehorse.backend.training.TrainingPlanResponse;
import racehorse.backend.training.TrainingPlanService;
import racehorse.backend.training.TrainingSessionRepository;
import racehorse.backend.training.TrainingSessionRequest;
import racehorse.backend.training.TrainingSessionResponse;
import racehorse.backend.training.TrainingSessionService;

/**
 * Luật nghiệp vụ của 3 flow bắt buộc, kiểm bằng repository giả (Mockito):
 * chạy trong vài giây, không cần database. Mỗi test là một luật hội đồng có
 * thể hỏi tới; tên test nói luật đó.
 */
class BusinessRulesTest {

    private static final LocalDate TODAY = LocalDate.now();
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
                LocalDateTime.now(), List.of());
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
        TrainingPlanService service = new TrainingPlanService(plans, mock(HorseRepository.class),
                mock(HorseVitalsRepository.class), mock(UserRepository.class));

        expect(ApiException.class, 409, () -> service.changeStatus(1, "ACTIVE", TRAINER, "ip"));
        verify(plans, never()).updateStatus(anyInt(), any(), any());
    }

    @Test
    void planPastItsEndDateCannotBeActivated() {
        TrainingPlanRepository plans = mock(TrainingPlanRepository.class);
        HorseRepository horses = mock(HorseRepository.class);
        HorseVitalsRepository vitals = mock(HorseVitalsRepository.class);
        when(plans.findById(1)).thenReturn(Optional.of(plan("DRAFT", TODAY.minusDays(1))));
        when(horses.findById(100)).thenReturn(Optional.of(horse(100, "ELIGIBLE", TODAY.minusYears(4), "COLT")));
        when(vitals.findActiveLock(100)).thenReturn(Optional.empty());
        TrainingPlanService service = new TrainingPlanService(plans, horses, vitals, mock(UserRepository.class));

        ApiException ex = expect(ApiException.class, 409, () -> service.changeStatus(1, "ACTIVE", TRAINER, "ip"));
        assertTrue(ex.getMessage().contains("ended"), ex.getMessage());
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
        TrainingSessionRepository repo = mock(TrainingSessionRepository.class);
        when(repo.findPhaseContext(10)).thenReturn(Optional.of(new TrainingSessionRepository.PhaseContext(
                10, 1, "ACTIVE", 100, 1600, TODAY, TODAY.plusDays(30))));
        when(repo.isActiveGroom(5)).thenReturn(true);
        when(horses.findById(100)).thenReturn(Optional.of(horse(100, "ELIGIBLE", TODAY.minusYears(4), "COLT")));
        when(vitals.findActiveLock(100)).thenReturn(Optional.empty());
        when(plans.findPhases(1)).thenReturn(List.of(new TrainingPlanResponse.Phase(
                10, 1, "Base", 1600, "Moderate", "TURF", 3, 180, TODAY, TODAY.plusDays(30))));
        return repo;
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

    // ---------------------------------------------------------------- Flow 2: cảnh báo

    private static AlertService alertService(AlertRepository alerts, NotificationService notifications) {
        return new AlertService(alerts, mock(UserRepository.class), notifications, new BigDecimal("18.0"));
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
}
