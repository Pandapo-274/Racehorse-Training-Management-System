package racehorse.backend;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import racehorse.backend.dto.HorseRequest;
import racehorse.backend.dto.ReceiveRequest;
import racehorse.backend.dto.ReviewRequest;
import racehorse.backend.dto.SignContractRequest;
import racehorse.backend.dto.SubmitRequest;
import racehorse.backend.entity.ContractRow;
import racehorse.backend.entity.HorseRequestRow;
import racehorse.backend.entity.UserRecord;
import racehorse.backend.exception.ApiException;
import racehorse.backend.repository.ContractRepository;
import racehorse.backend.repository.HorseRepository;
import racehorse.backend.repository.HorseRequestRepository;
import racehorse.backend.repository.HorseVitalsRepository;
import racehorse.backend.repository.UserRepository;
import racehorse.backend.security.AuthenticatedUser;
import racehorse.backend.service.ContractService;
import racehorse.backend.service.ContractTerms;
import racehorse.backend.service.HorsePhotoService;
import racehorse.backend.service.HorsePolicy;
import racehorse.backend.service.HorseRequestService;
import racehorse.backend.service.HorseService;
import racehorse.backend.service.NotificationService;
import racehorse.backend.service.payment.PaymentGateway;

/*
 * Flow 1, sau buổi review: mỗi góp ý của giảng viên có ít nhất một test cho trường hợp bị chặn.
 *   1. Ảnh trước khi duyệt                 -> clubHorseCannotBeApprovedWithoutAPhoto
 *   2. Duyệt dựa vào đâu                   -> approvalNeedsBothChecks, approvingAClubHorseSendsAContract...
 *   3. Mã đăng ký unique                   -> codeWithWrongFormatIsRefused, duplicateCodeRace...
 *   4. Thời hạn ngựa ở CLB                 -> trainingShorterThanThreeWeeks..., horseIsNotCreatedBeforePayment...
 *   5. Chỉ hiện HLV chọn được              -> fullTrainerIsNotListedAsAvailable
 * Cùng kỹ thuật với Flow1RulesTest: Mockito, không cần database.
 */
class Flow1ContractRulesTest {

    private static final AuthenticatedUser MANAGER =
            new AuthenticatedUser(1, "manager", "CLUB_MANAGER", "jti", Instant.now().plusSeconds(3600), Instant.now());
    private static final AuthenticatedUser OWNER_7 =
            new AuthenticatedUser(7, "owner7", "HORSE_OWNER", "jti", Instant.now().plusSeconds(3600), Instant.now());

    // ------------------------------------------------------------ 1 + 2. approving a registration

    @Test
    void clubHorseCannotBeApprovedWithoutAPhoto() {
        HorseRequestRepository requests = mock(HorseRequestRepository.class);
        when(requests.findById(5)).thenReturn(Optional.of(request(5, "ACADEMY", null, 3, null)));
        ContractService contracts = mock(ContractService.class);
        HorseRequestService service = requestService(requests, mock(HorseService.class), contracts);

        ApiException ex = expect(400, () -> service.approve(5, checks(true, true), MANAGER, "ip"));
        assertTrue(ex.getFieldErrors().containsKey("photo"), ex.getFieldErrors().toString());
        verify(requests, never()).markReviewed(anyInt(), anyString(), anyInt(), any(), any());
        verify(contracts, never()).offerForRequest(any(), any());
    }

    @Test
    void approvalNeedsBothChecks() {
        HorseRequestRepository requests = mock(HorseRequestRepository.class);
        when(requests.findById(5)).thenReturn(Optional.of(request(5, "ACADEMY", "/uploads/horses/req5.jpg", 3, null)));
        HorseRequestService service = requestService(requests, mock(HorseService.class), mock(ContractService.class));

        ApiException ex = expect(400, () -> service.approve(5, checks(true, false), MANAGER, "ip"));
        assertTrue(ex.getFieldErrors().containsKey("papersChecked"));
        assertFalse(ex.getFieldErrors().containsKey("photoChecked"));
        verify(requests, never()).markReviewed(anyInt(), anyString(), anyInt(), any(), any());
    }

    @Test
    void approvingAClubHorseSendsAContractAndCreatesNoHorse() {
        HorseRequestRepository requests = mock(HorseRequestRepository.class);
        when(requests.findById(5)).thenReturn(Optional.of(request(5, "ACADEMY", "/uploads/horses/req5.jpg", 3, null)));
        when(requests.markReviewed(5, "APPROVED", 1, null, null)).thenReturn(1);
        HorseService horses = mock(HorseService.class);
        ContractService contracts = mock(ContractService.class);
        when(contracts.offerForRequest(any(), eq(MANAGER))).thenReturn(40);
        HorseRequestService service = requestService(requests, horses, contracts);

        service.approve(5, checks(true, true), MANAGER, "ip");

        verify(contracts).offerForRequest(any(), eq(MANAGER));
        verify(horses, never()).create(any(), any(), any()); // the horse is born on arrival, not here
    }

    // ------------------------------------------------------------ 3. registration code

    @Test
    void codeWithWrongFormatIsRefused() {
        assertTrue(HorsePolicy.codeProblem("tm 01") != null);
        assertTrue(HorsePolicy.codeProblem("TM0751") != null);
        assertNull(HorsePolicy.codeProblem("TM-0751"));
        assertNull(HorsePolicy.codeProblem("TM-A001")); // the seeded pedigree records keep working
    }

    @Test
    void duplicateCodeRaceIsReportedOnTheCodeField() {
        HorseRepository horses = mock(HorseRepository.class);
        // Free when checked, taken by the time of the INSERT: the other manager was faster.
        when(horses.existsByRegistrationCode("TM-0751", 0)).thenReturn(false, true);
        when(horses.isHorseOwner(7)).thenReturn(true);
        when(horses.insert(any())).thenThrow(new DataIntegrityViolationException("UNIQUE KEY"));
        HorseService service = new HorseService(horses, mock(HorseVitalsRepository.class), mock(UserRepository.class));

        ApiException ex = expect(409, () -> service.create(new HorseRequest(7, null, null, "Sakura", "TM-0751",
                "Thoroughbred", "COLT", LocalDate.now().minusYears(2), "Bay", null, "Z-07", "ACADEMY"),
                MANAGER, "ip"));
        assertTrue(ex.getFieldErrors().containsKey("registrationCode"), ex.getFieldErrors().toString());
    }

    // ------------------------------------------------------------ 4. how long the horse stays

    @Test
    void trainingShorterThanThreeWeeksIsRefused() {
        LocalDate today = LocalDate.of(2026, 11, 23);
        LocalDate arrive = today.plusDays(3);
        // 20 days is one day short of 3 weeks; 21 days is enough.
        assertTrue(HorsePolicy.stayProblems(arrive, arrive, arrive.plusDays(20), 1, today).containsKey("trainingEnd"));
        assertTrue(HorsePolicy.stayProblems(arrive, arrive, arrive.plusDays(21), 1, today).isEmpty());
        assertEquals(Integer.valueOf(3), HorsePolicy.trainingWeeks(arrive, arrive.plusDays(21)));
    }

    @Test
    void trainingWindowMustFitTheStay() {
        LocalDate today = LocalDate.of(2026, 11, 23);
        LocalDate arrive = LocalDate.of(2026, 12, 1);
        // Training before the horse arrives.
        assertTrue(HorsePolicy.stayProblems(arrive, arrive.minusDays(1), arrive.plusDays(30), 1, today)
                .containsKey("trainingStart"));
        // A 1-month stay from 1 Dec ends on 1 Jan: training until 2 Jan does not fit, until 1 Jan does.
        assertTrue(HorsePolicy.stayProblems(arrive, arrive, LocalDate.of(2027, 1, 2), 1, today)
                .containsKey("trainingEnd"));
        assertTrue(HorsePolicy.stayProblems(arrive, arrive, LocalDate.of(2027, 1, 1), 1, today).isEmpty());
        // Arrival in the past, stay of 0 months.
        assertTrue(HorsePolicy.stayProblems(today.minusDays(1), today, today.plusDays(30), 1, today)
                .containsKey("preferredStart"));
        assertTrue(HorsePolicy.stayProblems(arrive, arrive, arrive.plusDays(21), 0, today)
                .containsKey("contractMonths"));
    }

    @Test
    void clubHorseRequestWithoutMeasuresOrTermIsRefused() {
        HorseRequestRepository requests = mock(HorseRequestRepository.class);
        HorseService horses = mock(HorseService.class);
        HorseRequestService service = requestService(requests, horses, mock(ContractService.class));

        ApiException ex = expect(400, () -> service.submit(new SubmitRequest("Sakura", "Thoroughbred", "COLT",
                LocalDate.now().minusYears(2), "Bay", null, "ACADEMY", null, null), OWNER_7, "ip"));
        assertTrue(ex.getFieldErrors().keySet().containsAll(
                List.of("weight", "heightCm", "preferredStart", "trainingStart", "trainingEnd", "contractMonths")),
                ex.getFieldErrors().toString());
        verify(requests, never()).insert(anyInt(), any());
    }

    @Test
    void horseIsNotCreatedBeforePayment() {
        ContractRepository contracts = mock(ContractRepository.class);
        when(contracts.findById(40)).thenReturn(Optional.of(contract(40, "SIGNED", 100_000)));
        HorseService horses = mock(HorseService.class);
        ContractService service = contractService(contracts, mock(HorseRequestRepository.class), horses,
                mock(PaymentGateway.class), mock(UserRepository.class));

        expect(409, () -> service.receive(40, new ReceiveRequest("TM-0751", "Z-07", null, 5, null, null, null),
                MANAGER, "ip"));
        verify(horses, never()).create(any(), any(), any());
        verify(contracts, never()).markActive(anyInt(), anyInt(), any(), any(), any());
    }

    @Test
    void ownerMustTypeTheirOwnNameToSign() {
        ContractRepository contracts = mock(ContractRepository.class);
        when(contracts.findById(40)).thenReturn(Optional.of(contract(40, "SENT", 100_000)));
        UserRepository users = mock(UserRepository.class);
        when(users.findById(7)).thenReturn(Optional.of(new UserRecord(7, "owner7", "$2a$hash", "Tran Duc Minh",
                "minh@club.test", "ACTIVE", "HORSE_OWNER", null, false, null)));
        ContractService service = contractService(contracts, mock(HorseRequestRepository.class),
                mock(HorseService.class), mock(PaymentGateway.class), users);

        String sha = ContractTerms.sha256("terms");
        ApiException ex = expect(400, () -> service.sign(40, new SignContractRequest("Minh", true, sha), OWNER_7, "ip"));
        assertTrue(ex.getFieldErrors().containsKey("fullName"));
        verify(contracts, never()).markSigned(anyInt(), anyInt(), anyString(), anyString(), anyString());
    }

    @Test
    void webhookWithABadSignatureChangesNothing() {
        ContractRepository contracts = mock(ContractRepository.class);
        PaymentGateway gateway = mock(PaymentGateway.class);
        when(gateway.verifyWebhook("{forged}")).thenThrow(new IllegalArgumentException("Data not integrity"));
        ContractService service = contractService(contracts, mock(HorseRequestRepository.class),
                mock(HorseService.class), gateway, mock(UserRepository.class));

        assertFalse(service.handleWebhook("{forged}"));
        verify(contracts, never()).markPaid(anyInt(), anyLong(), any());
    }

    @Test
    void transferBelowTheContractAmountIsNotCountedAsPaid() {
        ContractRepository contracts = mock(ContractRepository.class);
        when(contracts.findIdByOrderCode(4_000_123L)).thenReturn(Optional.of(40));
        when(contracts.findById(40)).thenReturn(Optional.of(contract(40, "SIGNED", 100_000)));
        PaymentGateway gateway = mock(PaymentGateway.class);
        when(gateway.verifyWebhook("{body}")).thenReturn(
                new PaymentGateway.Payment(4_000_123L, 50_000, "FT123", true, null));
        ContractService service = contractService(contracts, mock(HorseRequestRepository.class),
                mock(HorseService.class), gateway, mock(UserRepository.class));

        assertTrue(service.handleWebhook("{body}"));
        verify(contracts, never()).markPaid(anyInt(), anyLong(), any());
    }

    @Test
    void contractPriceAppliesThePackageDiscount() {
        ContractTerms terms = new ContractTerms(2000, "3:5,6:8,12:12", 7, 5_000_000, "Tracen Club");
        assertEquals(2000, terms.priceFor(1).total());
        assertEquals(5700, terms.priceFor(3).total());  // 6000 less 5%
        assertEquals(7600, terms.priceFor(4).total());  // 4 months is still the 3-month tier
        assertEquals(new BigDecimal("12"), terms.priceFor(12).discountPct());
    }

    // ------------------------------------------------------------ 5. Head Trainers that can be chosen

    @Test
    void fullTrainerIsNotListedAsAvailable() {
        HorseRepository horses = mock(HorseRepository.class);
        when(horses.listActiveTrainers()).thenReturn(List.of(
                new HorseRepository.TrainerSummary(2, "ha.vt", "Vu Thi Ha", 3, 10),
                new HorseRepository.TrainerSummary(3, "quang.hd", "Hoang Duc Quang", 2, 2)));
        HorseService service = new HorseService(horses, mock(HorseVitalsRepository.class), mock(UserRepository.class));

        List<HorseRepository.TrainerSummary> available = service.listAvailableTrainers();
        assertEquals(1, available.size());
        assertEquals(2, available.get(0).userId());
    }

    // ------------------------------------------------------------ body condition, growth for age

    @Test
    void growthIsComparedByAgeInMonths() {
        LocalDate today = LocalDate.of(2026, 11, 21);
        LocalDate sixMonthsOld = today.minusMonths(6);
        // Mean at 6 months: 247.1 kg, 135.0 cm (Kentucky Equine Research, world average).
        assertEquals(HorsePolicy.GROWTH_WITHIN, HorsePolicy.growthFor("Thoroughbred", sixMonthsOld,
                new BigDecimal("250"), new BigDecimal("135"), today));
        assertEquals(HorsePolicy.GROWTH_BELOW, HorsePolicy.growthFor("Thoroughbred", sixMonthsOld,
                new BigDecimal("200"), new BigDecimal("135"), today));
        assertNull(HorsePolicy.growthFor("Thoroughbred", today.minusYears(3),
                new BigDecimal("480"), new BigDecimal("160"), today)); // past 18 months: no reference
        assertNull(HorsePolicy.growthFor("Arabian", sixMonthsOld,
                new BigDecimal("200"), new BigDecimal("135"), today));
        assertEquals("IDEAL", HorsePolicy.bcsGroup(5));
        assertEquals("THIN", HorsePolicy.bcsGroup(3));
    }

    // ------------------------------------------------------------ helpers

    private static HorseRequestService requestService(HorseRequestRepository requests, HorseService horses,
                                                      ContractService contracts) {
        return new HorseRequestService(requests, horses, mock(UserRepository.class),
                mock(NotificationService.class), contracts);
    }

    private static ContractService contractService(ContractRepository contracts, HorseRequestRepository requests,
                                                   HorseService horses, PaymentGateway gateway, UserRepository users) {
        return new ContractService(contracts, requests, horses, mock(HorseRepository.class),
                mock(HorsePhotoService.class), users, mock(NotificationService.class),
                new ContractTerms(100_000, "3:5", 7, 5_000_000, "Tracen Club"), gateway, "http://localhost:3000");
    }

    private static ReviewRequest checks(boolean photo, boolean papers) {
        return new ReviewRequest(null, null, null, null, photo, papers);
    }

    private static HorseRequestRow request(int id, String type, String photoUrl, Integer months, Integer trainerId) {
        return new HorseRequestRow(id, 7, "Owner 7", "Sakura", "Thoroughbred", "COLT", type,
                LocalDate.now().minusYears(2), "Bay", null, "PENDING", LocalDateTime.now(),
                null, null, null, null, null, null, null, null, null,
                photoUrl, new BigDecimal("450"), new BigDecimal("155"), "TURF", "MEDIUM", "MIDDLE",
                null, 4, months, trainerId, null, null, null, 0, null, null, null,
                LocalDate.now().plusDays(3), LocalDate.now().plusDays(31));
    }

    private static ContractRow contract(int id, String status, long total) {
        String terms = "terms";
        return new ContractRow(id, 5, null, 7, "Tran Duc Minh", null,
                "Sakura", "ACADEMY", "Thoroughbred", "COLT", LocalDate.now().minusYears(2), "Bay", null,
                new BigDecimal("450"), new BigDecimal("155"), "TURF", "MEDIUM", "MIDDLE", null, null,
                1, 4, total, BigDecimal.ZERO, total,
                null, null, null, status, "Manager", LocalDateTime.now(), LocalDate.now().plusDays(7),
                null, null, null, null, null, null, null, null, null, null,
                terms, ContractTerms.sha256(terms), null, null, null, null);
    }

    private static ApiException expect(int status, org.junit.jupiter.api.function.Executable run) {
        ApiException ex = assertThrows(ApiException.class, run);
        assertEquals(status, ex.getStatus().value(), ex.getMessage());
        return ex;
    }
}
