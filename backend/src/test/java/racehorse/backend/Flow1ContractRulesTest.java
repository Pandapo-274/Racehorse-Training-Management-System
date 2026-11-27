package racehorse.backend;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
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
import racehorse.backend.dto.HorseResponse;
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
import racehorse.backend.service.HorseAdminService;
import racehorse.backend.service.HorsePhotoService;
import racehorse.backend.service.HorsePolicy;
import racehorse.backend.service.HorseRequestService;
import racehorse.backend.service.HorseService;
import racehorse.backend.service.HorseStandards;
import racehorse.backend.service.NotificationService;
import racehorse.backend.service.payment.PayOsGateway;
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

    @Test
    void registrationCodeCannotBeChangedOnceAssigned() {
        HorseRepository horses = mock(HorseRepository.class);
        when(horses.findById(10)).thenReturn(Optional.of(horse(10, 7)));
        HorseService service = new HorseService(horses, mock(HorseVitalsRepository.class), mock(UserRepository.class));

        ApiException ex = expect(400, () -> service.updateIdentity(10,
                new racehorse.backend.dto.HorseIdentityRequest("TM-0999", "A-08", null), MANAGER, "ip"));
        assertEquals("The registration code cannot be changed once assigned",
                ex.getFieldErrors().get("registrationCode"));
        verify(horses, never()).updateIdentity(anyInt(), any(), any());
    }

    // ------------------------------------------------------------ stalls: the stable plan (V60)

    @Test
    void stallOffThePlanIsRefusedWhenAHorseIsCreated() {
        HorseRepository horses = mock(HorseRepository.class);
        when(horses.isHorseOwner(7)).thenReturn(true);
        when(horses.isStallOffPlan("B-300")).thenReturn(true);
        HorseService service = new HorseService(horses, mock(HorseVitalsRepository.class), mock(UserRepository.class));

        ApiException ex = expect(400, () -> service.create(new HorseRequest(7, null, null, "Sakura", "TM-0751",
                "Thoroughbred", "COLT", LocalDate.now().minusYears(2), "Bay", null, "B-300", "ACADEMY"),
                MANAGER, "ip"));
        assertEquals("Stall B-300 is not on the stable plan", ex.getFieldErrors().get("stallNo"));
        verify(horses, never()).insert(any());
    }

    @Test
    void stallCheckNamesTheHorseInItOrSaysItIsFree() {
        HorseRepository horses = mock(HorseRepository.class);
        when(horses.existsByStallNo("Z-02", 0)).thenReturn(true);
        when(horses.findNameByStallNo("Z-02")).thenReturn(Optional.of("River Song"));
        when(horses.isStallOffPlan("Z-99")).thenReturn(true);
        HorseService service = new HorseService(horses, mock(HorseVitalsRepository.class), mock(UserRepository.class));

        HorseService.StallCheck taken = service.checkStall("z-02 ", null);
        assertFalse(taken.available());
        assertEquals("Taken by River Song", taken.message());
        assertEquals("Stall Z-99 is not on the stable plan", service.checkStall("Z-99", null).message());
        HorseService.StallCheck free = service.checkStall("A-08", null);
        assertTrue(free.available());
        assertEquals("A-08", free.stall());
    }

    // ------------------------------------------------------------ clause 8 contacts from lists (V63)

    @Test
    void typedInsurerOrStrangerIsRefusedOnARegistration() {
        HorseRequestRepository requests = mock(HorseRequestRepository.class);
        racehorse.backend.repository.ContactRepository book = mock(racehorse.backend.repository.ContactRepository.class);
        when(book.isListedInsurer("Bao Viet Insurance", "1900 558899")).thenReturn(true);
        HorseRequestService service = new HorseRequestService(requests, mock(HorseService.class),
                mock(UserRepository.class), mock(NotificationService.class), mock(ContractService.class), book);

        ApiException ex = expect(400, () -> service.submit(new SubmitRequest("Sakura", "Thoroughbred", "COLT",
                LocalDate.now().minusYears(2), "Bay", null, "ACADEMY", null, null,
                new BigDecimal("450"), new BigDecimal("155"), "TURF", "MEDIUM", "STALKER",
                LocalDate.now().plusDays(3), null, 1, null,
                LocalDate.now().plusDays(3), LocalDate.now().plusDays(31),
                "rdhdfh", "342534543", "Tran Van An", "0912345678"), OWNER_7, "ip"));
        assertEquals("Choose the insurer from the list", ex.getFieldErrors().get("insurerName"));
        assertEquals("Choose a person from your contacts", ex.getFieldErrors().get("emergencyName"));
        verify(requests, never()).insert(anyInt(), any());
    }

    // ------------------------------------------------------------ change of owner (V61)

    private static final AuthenticatedUser OWNER_8 =
            new AuthenticatedUser(8, "owner8", "HORSE_OWNER", "jti", Instant.now().plusSeconds(3600), Instant.now());
    private static final ContractService.Contacts BUYER_CONTACTS =
            ContractService.Contacts.of("PVI Insurance", "1900 545458", "Le Thi Hoa", "0913000111");

    @Test
    void horseWhoseStayEndedCannotBeTransferred() {
        ContractRepository contracts = mock(ContractRepository.class);
        when(contracts.findRunning(10)).thenReturn(Optional.of(stay(40, 7, "Tran Duc Minh", "EXPIRED",
                LocalDate.now().minusDays(2), null)));
        ContractService service = contractService(contracts, mock(HorseRequestRepository.class),
                mock(HorseService.class), mock(PaymentGateway.class), mock(UserRepository.class));

        ApiException ex = expect(409, () -> service.transferSource(10, "Sakura"));
        assertTrue(ex.getMessage().contains("stay ended"), ex.getMessage());
    }

    @Test
    void openRenewalMustBeSettledBeforeATransfer() {
        ContractRepository contracts = mock(ContractRepository.class);
        when(contracts.findOpenRenewal(10)).thenReturn(Optional.of(stay(41, 7, "Tran Duc Minh", "SENT", null, null)));
        ContractService service = contractService(contracts, mock(HorseRequestRepository.class),
                mock(HorseService.class), mock(PaymentGateway.class), mock(UserRepository.class));

        ApiException ex = expect(409, () -> service.transferSource(10, "Sakura"));
        assertTrue(ex.getMessage().contains("Cancel it before the transfer"), ex.getMessage());
    }

    @Test
    void transferOfAHorseWithAStayNeedsTheBuyersContacts() {
        HorseRepository horses = mock(HorseRepository.class);
        when(horses.findById(10)).thenReturn(Optional.of(horse(10, 7)));
        when(horses.isActiveHorseOwner(8)).thenReturn(true);
        when(horses.pendingOnOwner(10)).thenReturn(new HorseRepository.PendingOnOwner(0, 0, 0));
        ContractService contracts = mock(ContractService.class);
        when(contracts.transferSource(10, "Sakura")).thenReturn(stay(40, 7, "Tran Duc Minh", "ACTIVE",
                LocalDate.now().plusDays(20), null));
        HorseAdminService service = new HorseAdminService(horses, mock(UserRepository.class),
                mock(NotificationService.class), contracts, mock(racehorse.backend.repository.ContactRepository.class));

        ApiException ex = expect(400, () -> service.transferOwner(10, 8, "Sold",
                ContractService.Contacts.of(null, null, " ", null), MANAGER, "ip"));
        assertTrue(ex.getFieldErrors().containsKey("insurerName"), ex.getFieldErrors().toString());
        assertTrue(ex.getFieldErrors().containsKey("emergencyName"), ex.getFieldErrors().toString());
        verify(horses, never()).updateOwner(anyInt(), anyInt());
    }

    @Test
    void handOverClosesTheSellersContractAndSendsTheBuyerTheirOwn() {
        ContractRepository contracts = mock(ContractRepository.class);
        LocalDate end = LocalDate.now().plusDays(20);
        ContractRow seller = stay(40, 7, "Tran Duc Minh", "ACTIVE", end, null);
        when(contracts.markEnded(eq(40), anyString())).thenReturn(1);
        HorseRequestRepository requests = mock(HorseRequestRepository.class);
        when(requests.findById(5)).thenReturn(Optional.of(request(5, "ACADEMY", "/p.webp", 1, null)));
        ContractService service = contractService(contracts, requests,
                mock(HorseService.class), mock(PaymentGateway.class), mock(UserRepository.class));

        service.handOver(seller, horse(10, 8), 8, "Le Van Binh", BUYER_CONTACTS, MANAGER, "ip");

        ArgumentCaptor<ContractRepository.NewContract> sent = ArgumentCaptor.forClass(ContractRepository.NewContract.class);
        verify(contracts).insert(sent.capture());
        ContractRepository.NewContract c = sent.getValue();
        assertEquals(8, c.ownerId());
        assertEquals(0L, c.totalAmount());
        assertEquals(Integer.valueOf(40), c.takesOverContractId());
        assertEquals(end, c.plannedEnd());
        assertTrue(c.terms().contains("CHANGE OF OWNER"));
        assertTrue(c.terms().contains("Second contact: Le Thi Hoa, 0913000111"), c.terms());
        assertFalse(c.terms().contains("Tran Van An"), "the seller's second contact must not carry over");
    }

    @Test
    void signingAChangeOfOwnerContractStartsTheStayWithoutPayment() {
        ContractRepository contracts = mock(ContractRepository.class);
        LocalDate end = LocalDate.now().plusDays(20);
        ContractRow takeover = stay(41, 8, "Le Van Binh", "SENT", end, 40);
        when(contracts.findById(41)).thenReturn(Optional.of(takeover));
        when(contracts.markSigned(eq(41), eq(8), anyString(), anyString(), anyString())).thenReturn(1);
        when(contracts.activateTakeover(41, takeover.plannedStart(), end, false)).thenReturn(1);
        UserRepository users = mock(UserRepository.class);
        when(users.findById(8)).thenReturn(Optional.of(new UserRecord(8, "owner8", "$2a$hash", "Le Van Binh",
                "binh@club.test", "ACTIVE", "HORSE_OWNER", null, false, null)));
        PaymentGateway gateway = mock(PaymentGateway.class);
        ContractService service = contractService(contracts, mock(HorseRequestRepository.class),
                mock(HorseService.class), gateway, users);

        service.sign(41, new SignContractRequest("Le Van Binh", true, ContractTerms.sha256("terms")), OWNER_8, "ip");
        verify(contracts).activateTakeover(41, takeover.plannedStart(), end, false);
        verify(gateway, never()).createLink(anyLong(), anyLong(), any(), any(), any(), any(), any(), anyLong());
    }

    @Test
    void changeOfOwnerContractCannotBeDeclined() {
        ContractRepository contracts = mock(ContractRepository.class);
        when(contracts.findById(41)).thenReturn(Optional.of(stay(41, 8, "Le Van Binh", "SENT",
                LocalDate.now().plusDays(20), 40)));
        ContractService service = contractService(contracts, mock(HorseRequestRepository.class),
                mock(HorseService.class), mock(PaymentGateway.class), mock(UserRepository.class));

        expect(409, () -> service.cancel(41, "Changed my mind", OWNER_8, "ip"));
        verify(contracts, never()).markVoid(anyInt(), any());
    }

    // ------------------------------------------------------------ leaving the club (V62, clause 9)

    @Test
    void monthsThatStartAfterTheNoticeAreRefunded() {
        LocalDate start = LocalDate.of(2026, 1, 10);
        // Asked on 20 Jan to leave on 25 Jan: notice runs to 4 Feb, so February and March are refunded.
        LocalDate notice = HorsePolicy.noticeUntil(LocalDate.of(2026, 1, 20), LocalDate.of(2026, 1, 25));
        assertEquals(LocalDate.of(2026, 2, 4), notice);
        HorsePolicy.Refund early = HorsePolicy.refundFor(start, 3, 3000, notice);
        assertEquals(2, early.monthsNotStarted());
        assertEquals(2000L, early.amount());
        // Leaving on 20 Feb: February has started, only March is refunded.
        HorsePolicy.Refund later = HorsePolicy.refundFor(start, 3, 3000,
                HorsePolicy.noticeUntil(LocalDate.of(2026, 1, 20), LocalDate.of(2026, 2, 20)));
        assertEquals(1, later.monthsNotStarted());
        assertEquals(1000L, later.amount());
        assertEquals("Choose today or a later day",
                HorsePolicy.leaveOnProblem(LocalDate.now().minusDays(1), LocalDate.now()));
    }

    @Test
    void withdrawalIsNotApprovedBeforeTheCollectionDay() {
        WithdrawalMocks m = new WithdrawalMocks();
        m.pending(30, LocalDate.now().plusDays(5));

        ApiException ex = expect(409, () -> m.service().approve(30, null, MANAGER, "ip"));
        assertTrue(ex.getMessage().contains("keeps training"), ex.getMessage());
        verify(m.horses, never()).markWithdrawn(anyInt());
    }

    @Test
    void approvedWithdrawalRecordsTheRefundAndCancelsAnOpenRaceFee() {
        WithdrawalMocks m = new WithdrawalMocks();
        m.pending(30, LocalDate.now());
        // 3-month stay that started 10 days ago, 3,000 VND paid. Asked yesterday, so the notice runs to
        // today + 14: the 2nd and 3rd months start after it and are refunded.
        when(m.contracts.findRunning(10)).thenReturn(Optional.of(
                stay(40, 7, "Tran Duc Minh", "ACTIVE", LocalDate.now().plusMonths(3), null, null, 3, 3000)));
        when(m.withdrawals.findUpcomingEntries(eq(10), any())).thenReturn(List.of(
                new racehorse.backend.repository.WithdrawalRepository.UpcomingEntry(1, "Autumn Mile",
                        LocalDate.now().plusDays(21), LocalDate.now().plusDays(14), 99, false)));
        when(m.expenses.cancel(eq(99), eq(1), anyString())).thenReturn(1);

        m.service().approve(30, null, MANAGER, "ip");

        verify(m.expenses).cancel(eq(99), eq(1), anyString());
        verify(m.withdrawals).markApproved(eq(30), eq(1), any(), any(), anyInt(), anyInt(),
                eq(LocalDate.now(java.time.ZoneId.of("Asia/Ho_Chi_Minh")).plusDays(14)), eq(2), eq(2000L), eq(40));
    }

    @Test
    void clubWithdrawsAHorseItselfOnlyWhenItsStayEnded() {
        WithdrawalMocks m = new WithdrawalMocks();
        when(m.horses.findById(10)).thenReturn(Optional.of(horse(10, 7)));
        when(m.horses.hasExpiredContract(10)).thenReturn(false);

        expect(409, () -> m.service().withdrawByClub(
                new racehorse.backend.dto.WithdrawalByClubRequest(10, "Owner unreachable"), MANAGER, "ip"));
        verify(m.withdrawals, never()).insert(anyInt(), anyInt(), any(), any(), any(Boolean.class));
    }

    @Test
    void renewalPaidAfterTheHorseLeftDoesNotStart() {
        ContractRepository contracts = mock(ContractRepository.class);
        when(contracts.findIdByOrderCode(4_100_123L)).thenReturn(Optional.of(41));
        when(contracts.findById(41)).thenReturn(Optional.of(
                stay(41, 7, "Tran Duc Minh", "VOID", null, null, 40, 1, 1000)));
        when(contracts.markPaid(41, 1000, "FT9")).thenReturn(1);
        PaymentGateway gateway = mock(PaymentGateway.class);
        when(gateway.verifyWebhook("{late}")).thenReturn(new PaymentGateway.Payment(4_100_123L, 1000, "FT9", true, null));
        HorseRepository horses = mock(HorseRepository.class);
        when(horses.findById(10)).thenReturn(Optional.of(new HorseResponse(10, 7, "Owner 7", null, null, "Sakura",
                "TM-0751", "Thoroughbred", "COLT", LocalDate.now().minusYears(2), "Bay", null, null, "HEALTHY",
                LocalDateTime.now(), LocalDateTime.now(), "ACADEMY", null, "WITHDRAWN", LocalDateTime.now())));
        ContractService service = new ContractService(contracts, mock(HorseRequestRepository.class),
                mock(HorseService.class), horses, mock(HorsePhotoService.class), mock(UserRepository.class),
                mock(NotificationService.class), new ContractTerms(100_000, "3:5", 7, 5_000_000, "Tracen Club"),
                gateway, mock(racehorse.backend.service.CostPaymentService.class), "http://localhost:3000");

        assertTrue(service.handleWebhook("{late}"));
        verify(contracts).markPaid(41, 1000, "FT9");
        verify(contracts, never()).markActive(anyInt(), anyInt(), any(), any(), any());
        verify(contracts, never()).markEnded(anyInt(), any());
    }

    /** WithdrawalService with every collaborator mocked; horse 10 belongs to owner 7. */
    private static final class WithdrawalMocks {
        final racehorse.backend.repository.WithdrawalRepository withdrawals =
                mock(racehorse.backend.repository.WithdrawalRepository.class);
        final HorseRepository horses = mock(HorseRepository.class);
        final racehorse.backend.repository.TrainingSessionRepository sessions =
                mock(racehorse.backend.repository.TrainingSessionRepository.class);
        final ContractRepository contracts = mock(ContractRepository.class);
        final racehorse.backend.repository.ExpenseRepository expenses =
                mock(racehorse.backend.repository.ExpenseRepository.class);

        WithdrawalMocks() {
            when(withdrawals.unpaidCosts(anyInt())).thenReturn(new racehorse.backend.repository.WithdrawalRepository.Costs(0, 0, 0));
            when(withdrawals.activity(anyInt())).thenReturn(new racehorse.backend.repository.WithdrawalRepository.Activity(0, 0));
            when(horses.markWithdrawn(10)).thenReturn(1);
        }

        /** A request sent yesterday by owner 7 to collect horse 10 on leaveOn. */
        void pending(int id, LocalDate leaveOn) {
            when(horses.findById(10)).thenReturn(Optional.of(horse(10, 7)));
            when(withdrawals.findById(id)).thenReturn(Optional.of(new racehorse.backend.entity.WithdrawalRow(
                    id, 10, "Sakura", "TM-0751", "A-08", "ACTIVE", 7, "Tran Duc Minh", "Moving to another stable",
                    "PENDING", LocalDateTime.now(java.time.ZoneOffset.UTC).minusDays(1), null, null, null, null,
                    null, null, null, 0, 0, leaveOn, null, null, null, null, false)));
            when(withdrawals.markApproved(anyInt(), anyInt(), any(), any(), anyInt(), anyInt(), any(), anyInt(),
                    anyLong(), any())).thenReturn(1);
        }

        racehorse.backend.service.WithdrawalService service() {
            return new racehorse.backend.service.WithdrawalService(withdrawals, horses, sessions,
                    mock(racehorse.backend.repository.TrainingPlanRepository.class), mock(UserRepository.class),
                    mock(NotificationService.class), contracts, expenses);
        }
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
        assertEquals(HorsePolicy.GROWTH_WITHIN, HorsePolicy.growthFor("Thoroughbred", today.minusYears(3),
                new BigDecimal("480"), new BigDecimal("160"), today));
        assertNull(HorsePolicy.growthFor("Thoroughbred", today.minusYears(6),
                new BigDecimal("480"), new BigDecimal("160"), today)); // fully grown: BCS only
        assertEquals(HorsePolicy.GROWTH_WITHIN, HorsePolicy.growthFor("Arabian", sixMonthsOld,
                new BigDecimal("200"), new BigDecimal("130"), today));
        assertNull(HorsePolicy.growthFor("Mustang", sixMonthsOld,
                new BigDecimal("200"), new BigDecimal("135"), today)); // no reference for this breed
        assertEquals("IDEAL", HorsePolicy.bcsGroup(5));
        assertEquals("THIN", HorsePolicy.bcsGroup(3));
    }

    // ------------------------------------------------------------ standards by breed and age

    @Test
    void speedLimitFollowsBreedAndAgeInMonths() {
        assertEquals(new BigDecimal("0.0"), HorseStandards.speedLimit("Thoroughbred", 17));
        assertEquals(new BigDecimal("52.8"), HorseStandards.speedLimit("Thoroughbred", 20));
        assertEquals(new BigDecimal("59.4"), HorseStandards.speedLimit("Thoroughbred", 30));
        assertEquals(new BigDecimal("66.0"), HorseStandards.speedLimit("Thoroughbred", 48));
        assertEquals(new BigDecimal("59.4"), HorseStandards.speedLimit("Thoroughbred", 140));
        assertEquals(new BigDecimal("58.0"), HorseStandards.speedLimit("Arabian", 48));
        assertEquals(new BigDecimal("70.0"), HorseStandards.speedLimit("Quarter Horse", 48));
        assertEquals(new BigDecimal("58.0"), HorseStandards.speedLimit("Mustang", 48));
    }

    @Test
    void standardsSayWhichFiguresArePublished() {
        assertEquals(HorseStandards.PUBLISHED, HorseStandards.expected("Thoroughbred", 12).basis());
        assertEquals(HorseStandards.CLUB_REFERENCE, HorseStandards.expected("Thoroughbred", 24).basis());
        assertEquals(HorseStandards.CLUB_REFERENCE, HorseStandards.expected("Arabian", 12).basis());
        assertNull(HorseStandards.expected("Arabian", 3));
        assertEquals(3, HorseStandards.catalog().breeds().size());
    }

    // ------------------------------------------------------------ training commitment

    @Test
    void commitmentCountsDeliveredSessionsAndExcusesVetLocks() {
        HorsePolicy.Delivery atRisk = HorsePolicy.delivery(12, 7, 2, false);  // 7 of 10 counted
        assertEquals("AT_RISK", atRisk.status());
        assertEquals(70, atRisk.deliveredPct());
        assertEquals(1, atRisk.sessionsShort());

        assertEquals("NOT_MET", HorsePolicy.delivery(12, 7, 2, true).status());
        assertEquals("MET", HorsePolicy.delivery(12, 8, 2, true).status());
        assertEquals("ON_TRACK", HorsePolicy.delivery(5, 4, 0, false).status());
        assertEquals("NOT_STARTED", HorsePolicy.delivery(0, 0, 0, false).status());
    }

    @Test
    void renewalSaysTheHorseIsAlreadyAtTheClub() {
        ContractTerms terms = new ContractTerms(2000, "3:5", 7, 5_000_000, "Tracen Club");
        String text = terms.text(new ContractTerms.Parties("Owner", "Sakura", "Thoroughbred", "FILLY",
                        LocalDate.now().minusYears(2), "Bay", null, null, null, null, "Manager", true, LocalDate.now(),
                        null, null, "PVI", "1900", "Le Thi Hoa", "0913", "TM-0751", "Z-07"),
                terms.priceFor(1), LocalDate.now().plusDays(7));
        assertTrue(text.contains("Already at the club as TM-0751, stall Z-07."), text);
        assertFalse(text.contains("when the horse arrives"));
    }

    @Test
    void contractStatesTheCommitmentAndTheContacts() {
        ContractTerms terms = new ContractTerms(2000, "3:5", 7, 5_000_000, "Tracen Club");
        String text = terms.text(new ContractTerms.Parties("Owner", "Sakura", "Thoroughbred", "COLT",
                        LocalDate.now().minusYears(2), "Bay", null, null, 4, LocalDate.now(), "Manager", false, null,
                        LocalDate.now(), LocalDate.now().plusDays(28),
                        "Bao Viet", "0901234567", "Tran Van An", "0912345678", null, null),
                terms.priceFor(1), LocalDate.now().plusDays(7));
        assertTrue(text.contains(ContractTerms.COMMITMENT_HEADING));
        assertTrue(text.contains("does not promise a race result"));
        assertTrue(text.contains("12. If the commitment is not met"));
        assertTrue(text.contains("Bao Viet, 0901234567"));
        assertTrue(text.contains("Tran Van An, 0912345678"));
    }

    // ------------------------------------------------------------ payOS

    @Test
    void paymentDescriptionFitsThePayOsLimit() {
        ContractRepository contracts = mock(ContractRepository.class);
        when(contracts.findById(1_234_567)).thenReturn(Optional.of(contract(1_234_567, "SIGNED", 100_000)));
        when(contracts.setPaymentLink(anyInt(), anyLong(), any(), any())).thenReturn(1);
        PaymentGateway gateway = mock(PaymentGateway.class);
        when(gateway.createLink(anyLong(), anyLong(), anyString(), anyString(), any(), anyString(), anyString(), anyLong()))
                .thenReturn(new PaymentGateway.Link(1L, "link", "https://pay.example/checkout"));
        ContractService service = contractService(contracts, mock(HorseRequestRepository.class),
                mock(HorseService.class), gateway, mock(UserRepository.class));

        service.startPayment(1_234_567, OWNER_7);

        ArgumentCaptor<String> description = ArgumentCaptor.forClass(String.class);
        verify(gateway).createLink(anyLong(), anyLong(), description.capture(), anyString(), any(),
                anyString(), anyString(), anyLong());
        assertTrue(description.getValue().length() <= 9, description.getValue());
    }

    /** The real SDK and a real signature, no network: the webhook body arrives as a raw JSON string. */
    @Test
    void realSignedWebhookBodyIsAcceptedAndATamperedOneIsNot() throws Exception {
        String checksum = "test-checksum-key";
        java.util.Map<String, Object> data = new java.util.LinkedHashMap<>();
        data.put("orderCode", 4000123);
        data.put("amount", 2000);
        data.put("description", "HD40");
        data.put("accountNumber", "12345678");
        data.put("reference", "FT123");
        data.put("transactionDateTime", "2026-11-24 10:00:00");
        data.put("currency", "VND");
        data.put("paymentLinkId", "abc123");
        data.put("code", "00");
        data.put("desc", "success");
        data.put("counterAccountBankId", "");
        data.put("counterAccountBankName", "");
        data.put("counterAccountName", "");
        data.put("counterAccountNumber", "");
        data.put("virtualAccountName", "");
        data.put("virtualAccountNumber", "");
        String signature = new vn.payos.PayOS("client", "api", checksum).getCrypto()
                .createSignatureFromObj(data, checksum);
        java.util.Map<String, Object> body = new java.util.LinkedHashMap<>();
        body.put("code", "00");
        body.put("desc", "success");
        body.put("success", true);
        body.put("data", data);
        body.put("signature", signature);
        com.fasterxml.jackson.databind.ObjectMapper json = new com.fasterxml.jackson.databind.ObjectMapper();
        String raw = json.writeValueAsString(body);

        PayOsGateway gateway = new PayOsGateway("client", "api", checksum);
        PaymentGateway.Payment payment = gateway.verifyWebhook(raw);
        assertEquals(4000123L, payment.orderCode());
        assertEquals(2000L, payment.amount());
        assertTrue(payment.paid());

        String tampered = raw.replace("\"amount\":2000", "\"amount\":9000");
        assertThrows(IllegalArgumentException.class, () -> gateway.verifyWebhook(tampered));
    }

    // ------------------------------------------------------------ separately billed costs (V57)

    private static final long COST_ORDER = 50_000_300_123L;

    private static racehorse.backend.entity.CostPaymentModels.Item cost(int id, String amount) {
        return new racehorse.backend.entity.CostPaymentModels.Item(id, 100, "Sakura", "MEDICAL",
                new BigDecimal(amount), LocalDate.now(), null);
    }

    private static racehorse.backend.entity.CostPaymentModels.Payment costPayment(int id, long total) {
        return new racehorse.backend.entity.CostPaymentModels.Payment(id, 7, total, "PENDING", COST_ORDER,
                "https://pay.example/c", LocalDateTime.now(), null, null, null, 1);
    }

    private static racehorse.backend.service.CostPaymentService costService(
            racehorse.backend.repository.CostPaymentRepository repo, PaymentGateway gateway) {
        return new racehorse.backend.service.CostPaymentService(repo, mock(UserRepository.class),
                mock(NotificationService.class), gateway, "http://localhost:3000");
    }

    private static PaymentGateway linkingGateway() {
        PaymentGateway gateway = mock(PaymentGateway.class);
        when(gateway.createLink(anyLong(), anyLong(), anyString(), anyString(), any(), anyString(), anyString(), anyLong()))
                .thenReturn(new PaymentGateway.Link(COST_ORDER, "link", "https://pay.example/new"));
        return gateway;
    }

    @Test
    void payingCostsChargesTheRoundedTotalWithAShortDescription() {
        var repo = mock(racehorse.backend.repository.CostPaymentRepository.class);
        when(repo.outstanding(7)).thenReturn(List.of(cost(11, "1500.00"), cost(12, "500.50")));
        when(repo.insert(7, 2001)).thenReturn(3);
        when(repo.setLink(eq(3), anyLong(), any(), any())).thenReturn(1);
        PaymentGateway gateway = linkingGateway();

        costService(repo, gateway).start(OWNER_7);

        verify(repo).insertItem(3, 11, new BigDecimal("1500.00"));
        verify(repo).insertItem(3, 12, new BigDecimal("500.50"));
        verify(gateway).createLink(anyLong(), eq(2001L), eq("CP3"), anyString(), any(), anyString(), anyString(), anyLong());
    }

    @Test
    void nothingToPayIsRefused() {
        var repo = mock(racehorse.backend.repository.CostPaymentRepository.class);
        when(repo.outstanding(7)).thenReturn(List.of());
        PaymentGateway gateway = linkingGateway();

        expect(409, () -> costService(repo, gateway).start(OWNER_7));
        verify(gateway, never()).createLink(anyLong(), anyLong(), anyString(), anyString(), any(), anyString(), anyString(), anyLong());
    }

    @Test
    void anOpenLinkForTheSameCostsIsReused() {
        var repo = mock(racehorse.backend.repository.CostPaymentRepository.class);
        when(repo.outstanding(7)).thenReturn(List.of(cost(11, "2000")));
        when(repo.findOpen(7)).thenReturn(Optional.of(costPayment(3, 2000)));
        when(repo.itemIds(3)).thenReturn(List.of(11));
        PaymentGateway gateway = linkingGateway();
        when(gateway.fetch(COST_ORDER)).thenReturn(new PaymentGateway.Payment(COST_ORDER, 0, null, false, "PENDING"));

        var start = costService(repo, gateway).start(OWNER_7);

        assertEquals("https://pay.example/c", start.checkoutUrl());
        verify(gateway, never()).cancel(anyLong(), any());
        verify(gateway, never()).createLink(anyLong(), anyLong(), anyString(), anyString(), any(), anyString(), anyString(), anyLong());
    }

    @Test
    void changedCostsCancelTheOldLinkBeforeMakingANewOne() {
        var repo = mock(racehorse.backend.repository.CostPaymentRepository.class);
        when(repo.outstanding(7)).thenReturn(List.of(cost(11, "2000"), cost(12, "1000")));
        when(repo.findOpen(7)).thenReturn(Optional.of(costPayment(3, 2000)));
        when(repo.itemIds(3)).thenReturn(List.of(11));
        when(repo.insert(7, 3000)).thenReturn(4);
        when(repo.setLink(eq(4), anyLong(), any(), any())).thenReturn(1);
        PaymentGateway gateway = linkingGateway();
        when(gateway.fetch(COST_ORDER)).thenReturn(new PaymentGateway.Payment(COST_ORDER, 0, null, false, "PENDING"));

        costService(repo, gateway).start(OWNER_7);

        verify(gateway).cancel(eq(COST_ORDER), any());
        verify(repo).markCancelled(3);
        verify(gateway).createLink(anyLong(), eq(3000L), eq("CP4"), anyString(), any(), anyString(), anyString(), anyLong());
    }

    @Test
    void webhookForACostPaymentMarksTheCostsPaid() {
        var repo = mock(racehorse.backend.repository.CostPaymentRepository.class);
        when(repo.findIdByOrderCode(COST_ORDER)).thenReturn(Optional.of(4));
        when(repo.findById(4)).thenReturn(Optional.of(costPayment(4, 3000)));
        when(repo.markPaid(4, 3000, "FT1")).thenReturn(1);
        when(repo.markExpensesPaid(4)).thenReturn(2);
        when(repo.unappliedAmount(4)).thenReturn(BigDecimal.ZERO);
        PaymentGateway gateway = mock(PaymentGateway.class);
        when(gateway.verifyWebhook("{body}")).thenReturn(new PaymentGateway.Payment(COST_ORDER, 3000, "FT1", true, null));
        ContractService contracts = new ContractService(mock(ContractRepository.class), mock(HorseRequestRepository.class),
                mock(HorseService.class), mock(HorseRepository.class), mock(HorsePhotoService.class),
                mock(UserRepository.class), mock(NotificationService.class),
                new ContractTerms(100_000, "3:5", 7, 5_000_000, "Tracen Club"), gateway,
                costService(repo, gateway), "http://localhost:3000");

        assertTrue(contracts.handleWebhook("{body}"));
        verify(repo).markExpensesPaid(4);
    }

    @Test
    void shortCostTransferIsNotCountedAsPaid() {
        var repo = mock(racehorse.backend.repository.CostPaymentRepository.class);
        when(repo.findIdByOrderCode(COST_ORDER)).thenReturn(Optional.of(4));
        when(repo.findById(4)).thenReturn(Optional.of(costPayment(4, 3000)));

        assertTrue(costService(repo, mock(PaymentGateway.class))
                .applyWebhook(new PaymentGateway.Payment(COST_ORDER, 1000, "FT1", true, null)));
        verify(repo, never()).markPaid(anyInt(), anyLong(), any());
        verify(repo, never()).markExpensesPaid(anyInt());
    }

    // ------------------------------------------------------------ helpers

    private static HorseRequestService requestService(HorseRequestRepository requests, HorseService horses,
                                                      ContractService contracts) {
        return new HorseRequestService(requests, horses, mock(UserRepository.class),
                mock(NotificationService.class), contracts, mock(racehorse.backend.repository.ContactRepository.class));
    }

    private static ContractService contractService(ContractRepository contracts, HorseRequestRepository requests,
                                                   HorseService horses, PaymentGateway gateway, UserRepository users) {
        return new ContractService(contracts, requests, horses, mock(HorseRepository.class),
                mock(HorsePhotoService.class), users, mock(NotificationService.class),
                new ContractTerms(100_000, "3:5", 7, 5_000_000, "Tracen Club"), gateway,
                mock(racehorse.backend.service.CostPaymentService.class), "http://localhost:3000");
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
                LocalDate.now().plusDays(3), LocalDate.now().plusDays(31),
                "Bao Viet", "0901234567", "Tran Van An", "0912345678");
    }

    private static ContractRow contract(int id, String status, long total) {
        String terms = "terms";
        return new ContractRow(id, 5, null, 7, "Tran Duc Minh", null,
                "Sakura", "ACADEMY", "Thoroughbred", "COLT", LocalDate.now().minusYears(2), "Bay", null,
                new BigDecimal("450"), new BigDecimal("155"), "TURF", "MEDIUM", "MIDDLE", null, null,
                1, 4, total, BigDecimal.ZERO, total,
                null, null, null, status, "Manager", LocalDateTime.now(), LocalDate.now().plusDays(7),
                null, null, null, null, null, null, null, null, null, null,
                terms, ContractTerms.sha256(terms), null, null, null, null,
                null, null, null, null, null, null);
    }

    /** A stay of horse 10 from request 5; takesOver set for a change-of-owner contract. */
    private static ContractRow stay(int id, int ownerId, String ownerName, String status, LocalDate end,
                                    Integer takesOver) {
        return stay(id, ownerId, ownerName, status, end, takesOver, null, 1, 1000);
    }

    private static ContractRow stay(int id, int ownerId, String ownerName, String status, LocalDate end,
                                    Integer takesOver, Integer renews, int months, long total) {
        String terms = "terms";
        LocalDate start = LocalDate.now().minusDays(10);
        return new ContractRow(id, 5, 10, ownerId, ownerName, renews,
                "Sakura", "ACADEMY", "Thoroughbred", "COLT", LocalDate.now().minusYears(2), "Bay", null,
                new BigDecimal("450"), new BigDecimal("155"), "TURF", "MEDIUM", "STALKER", null, null,
                months, 4, total / months, BigDecimal.ZERO, takesOver == null ? total : 0,
                takesOver == null ? start : LocalDate.now(), takesOver == null ? start : null, end, status,
                "Manager", LocalDateTime.now(), end == null ? LocalDate.now().plusDays(7) : end,
                null, null, null, null, null, null, null, null, "TM-0751", "A-08",
                terms, ContractTerms.sha256(terms), null, null, null, null,
                takesOver, takesOver == null ? null : "Tran Duc Minh",
                "Bao Viet", "0901234567", "Tran Van An", "0912345678");
    }

    private static HorseResponse horse(int id, int ownerId) {
        return new HorseResponse(id, ownerId, "Owner " + ownerId, null, null, "Sakura", "TM-0751", "Thoroughbred",
                "COLT", LocalDate.now().minusYears(2), "Bay", new BigDecimal("450"), "A-08", "HEALTHY",
                LocalDateTime.now(), LocalDateTime.now(), "ACADEMY", null, "ACTIVE", null);
    }

    private static ApiException expect(int status, org.junit.jupiter.api.function.Executable run) {
        ApiException ex = assertThrows(ApiException.class, run);
        assertEquals(status, ex.getStatus().value(), ex.getMessage());
        return ex;
    }
}
