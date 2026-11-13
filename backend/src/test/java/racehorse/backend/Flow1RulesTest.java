package racehorse.backend;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.Optional;

import org.junit.jupiter.api.Test;
import org.springframework.security.crypto.password.PasswordEncoder;

import racehorse.backend.auth.ApiException;
import racehorse.backend.auth.AuthService;
import racehorse.backend.auth.AuthenticatedUser;
import racehorse.backend.auth.JwtService;
import racehorse.backend.auth.LoginRequest;
import racehorse.backend.auth.TokenBlacklist;
import racehorse.backend.auth.UserRecord;
import racehorse.backend.auth.UserRepository;
import racehorse.backend.horse.HorseAdminService;
import racehorse.backend.horse.HorseRepository;
import racehorse.backend.horse.HorseResponse;
import racehorse.backend.horse.HorseService;
import racehorse.backend.horse.HorseUpdateRequest;
import racehorse.backend.horse.HorseVitalsRepository;
import racehorse.backend.notification.NotificationService;
import racehorse.backend.profile.ProfileRepository;
import racehorse.backend.profile.ProfileService;
import racehorse.backend.profile.UpdateProfileRequest;
import racehorse.backend.staff.StaffRepository;
import racehorse.backend.staff.StaffRow;
import racehorse.backend.staff.StaffService;

/**
 * Flow 1 - horses and user accounts. One test per business rule added or
 * tightened on branch fix/flow1-gaps, so the demo's "exception" step has a
 * test behind each refusal it shows.
 */
class Flow1RulesTest {

    private static final AuthenticatedUser MANAGER =
            new AuthenticatedUser(1, "manager", "CLUB_MANAGER", "jti", Instant.now().plusSeconds(3600), Instant.now());
    private static final AuthenticatedUser OWNER_7 =
            new AuthenticatedUser(7, "owner7", "HORSE_OWNER", "jti", Instant.now().plusSeconds(3600), Instant.now());

    // ------------------------------------------------------------ sign-in lock

    @Test
    void lockedAccountIsRefusedEvenWithTheRightPassword() {
        UserRepository users = mock(UserRepository.class);
        PasswordEncoder encoder = mock(PasswordEncoder.class);
        JwtService jwt = mock(JwtService.class);
        when(users.findByLogin("groom1")).thenReturn(Optional.of(user(5, "GROOM", Instant.now().plusSeconds(600))));
        when(encoder.matches(any(), any())).thenReturn(true);

        AuthService auth = new AuthService(users, encoder, jwt, mock(TokenBlacklist.class));
        expect(429, () -> auth.login(new LoginRequest("groom1", "Right123"), "ip"));
        verify(jwt, never()).generate(anyInt(), anyString(), anyString());
        verify(users, never()).recordFailedLogin(anyInt(), anyInt(), anyInt());
    }

    @Test
    void fifthWrongPasswordLocksTheAccount() {
        UserRepository users = mock(UserRepository.class);
        PasswordEncoder encoder = mock(PasswordEncoder.class);
        when(users.findByLogin("groom1")).thenReturn(Optional.of(user(5, "GROOM", null)));
        when(encoder.matches(any(), any())).thenReturn(false);
        when(users.recordFailedLogin(5, 5, 15)).thenReturn(Instant.now().plusSeconds(900));

        AuthService auth = new AuthService(users, encoder, mock(JwtService.class), mock(TokenBlacklist.class));
        ApiException ex = expect(429, () -> auth.login(new LoginRequest("groom1", "wrong"), "ip"));
        assertTrue(ex.getMessage().contains("locked until"), ex.getMessage());
        verify(users).writeAudit(eq(5), eq("LOGIN_LOCKED"), eq("APP_USER"), eq(5), eq("SUCCESS"), eq("ip"), anyString());
    }

    @Test
    void wrongPasswordBeforeTheLimitIsAPlain401() {
        UserRepository users = mock(UserRepository.class);
        PasswordEncoder encoder = mock(PasswordEncoder.class);
        when(users.findByLogin("groom1")).thenReturn(Optional.of(user(5, "GROOM", null)));
        when(encoder.matches(any(), any())).thenReturn(false);
        when(users.recordFailedLogin(5, 5, 15)).thenReturn(null);

        AuthService auth = new AuthService(users, encoder, mock(JwtService.class), mock(TokenBlacklist.class));
        expect(401, () -> auth.login(new LoginRequest("groom1", "wrong"), "ip"));
    }

    @Test
    void successfulSignInClearsTheCounter() {
        UserRepository users = mock(UserRepository.class);
        PasswordEncoder encoder = mock(PasswordEncoder.class);
        JwtService jwt = mock(JwtService.class);
        when(users.findByLogin("groom1")).thenReturn(Optional.of(user(5, "GROOM", null)));
        when(encoder.matches(any(), any())).thenReturn(true);
        when(jwt.generate(anyInt(), anyString(), anyString())).thenReturn("token");

        new AuthService(users, encoder, jwt, mock(TokenBlacklist.class)).login(new LoginRequest("groom1", "Right123"), "ip");
        verify(users).clearLoginLock(5);
    }

    // ------------------------------------------------------------ profile email

    @Test
    void changingEmailNeedsTheCurrentPassword() {
        UserRepository users = mock(UserRepository.class);
        ProfileRepository profiles = mock(ProfileRepository.class);
        when(users.findById(5)).thenReturn(Optional.of(user(5, "GROOM", null)));

        ProfileService service = new ProfileService(profiles, users, mock(PasswordEncoder.class), "uploads");
        ApiException ex = expect(400, () -> service.updateProfile(5,
                new UpdateProfileRequest("Groom Five", "new@club.test", null, null), "ip"));
        assertTrue(ex.getFieldErrors().containsKey("currentPassword"));
        verify(profiles, never()).updateBasic(anyInt(), anyString(), anyString(), any());
    }

    @Test
    void changingEmailWithAWrongPasswordIsRefused() {
        UserRepository users = mock(UserRepository.class);
        ProfileRepository profiles = mock(ProfileRepository.class);
        PasswordEncoder encoder = mock(PasswordEncoder.class);
        when(users.findById(5)).thenReturn(Optional.of(user(5, "GROOM", null)));
        when(encoder.matches(any(), any())).thenReturn(false);

        ProfileService service = new ProfileService(profiles, users, encoder, "uploads");
        expect(400, () -> service.updateProfile(5,
                new UpdateProfileRequest("Groom Five", "new@club.test", null, "Wrong123"), "ip"));
        verify(profiles, never()).updateBasic(anyInt(), anyString(), anyString(), any());
    }

    // ------------------------------------------------------------ temporary password

    @Test
    void managerCannotResetAnotherManagersPassword() {
        StaffRepository staff = mock(StaffRepository.class);
        when(staff.findRow(2)).thenReturn(Optional.of(staffRow(2, "CLUB_MANAGER")));
        StaffService service = staffService(staff, mock(TokenBlacklist.class));

        expect(403, () -> service.setTemporaryPassword(2, "Temp1234", MANAGER, "ip"));
        verify(staff, never()).setTemporaryPassword(anyInt(), any());
    }

    @Test
    void managerResetsOwnPasswordOnProfileNotHere() {
        StaffRepository staff = mock(StaffRepository.class);
        when(staff.findRow(1)).thenReturn(Optional.of(staffRow(1, "CLUB_MANAGER")));
        StaffService service = staffService(staff, mock(TokenBlacklist.class));

        expect(400, () -> service.setTemporaryPassword(1, "Temp1234", MANAGER, "ip"));
    }

    @Test
    void temporaryPasswordEndsEverySessionOfThatAccount() {
        StaffRepository staff = mock(StaffRepository.class);
        TokenBlacklist blacklist = mock(TokenBlacklist.class);
        when(staff.findRow(5)).thenReturn(Optional.of(staffRow(5, "GROOM")));
        StaffService service = staffService(staff, blacklist);

        service.setTemporaryPassword(5, "Temp1234", MANAGER, "ip");
        verify(staff).setTemporaryPassword(eq(5), any());
        verify(blacklist).revokeAllSessions(5);
    }

    // ------------------------------------------------------------ desk accounts

    @Test
    void managerCanCreateAHorseOwnerAtTheDesk() {
        UserRepository users = mock(UserRepository.class);
        StaffRepository staff = mock(StaffRepository.class);
        when(users.findRoleId("HORSE_OWNER")).thenReturn(Optional.of(5));
        when(users.insert(anyInt(), anyString(), any(), anyString(), anyString(), any())).thenReturn(40);
        when(staff.findRow(40)).thenReturn(Optional.of(staffRow(40, "HORSE_OWNER")));
        StaffService service = new StaffService(users, staff, mock(PasswordEncoder.class), mock(TokenBlacklist.class),
                mock(NotificationService.class));

        service.create(new racehorse.backend.staff.StaffController.CreateRequest(
                "desk.owner", "Temp1234", "Desk Owner", "desk.owner@club.test", null, "HORSE_OWNER"), MANAGER, "ip");
        verify(users).setMustChangePassword(40, true);
    }

    @Test
    void managerCannotCreateAnotherClubManager() {
        StaffService service = staffService(mock(StaffRepository.class), mock(TokenBlacklist.class));
        expect(400, () -> service.create(new racehorse.backend.staff.StaffController.CreateRequest(
                "boss2", "Temp1234", "Second Boss", "boss2@club.test", null, "CLUB_MANAGER"), MANAGER, "ip"));
    }

    // ------------------------------------------------------------ leaving a role

    @Test
    void roleChangeNeedsSomeoneToTakeOverTheWork() {
        UserRepository users = mock(UserRepository.class);
        StaffRepository staff = mock(StaffRepository.class);
        when(staff.findRow(2)).thenReturn(Optional.of(trainer(2, 2, 1)));
        when(users.findRoleId("VETERINARIAN")).thenReturn(Optional.of(3));
        when(staff.horsesOfTrainer(2)).thenReturn(java.util.List.of(
                new StaffRepository.TrainerHorse(30, "River Song", 7), new StaffRepository.TrainerHorse(31, "Thunder Bay", 7)));
        when(staff.groomIdsOfTrainer(2)).thenReturn(java.util.List.of(5));
        StaffService service = new StaffService(users, staff, mock(PasswordEncoder.class), mock(TokenBlacklist.class),
                mock(NotificationService.class));

        // No other Head Trainer at all: the role cannot change yet (a suspension still could).
        ApiException none = expect(409, () -> service.changeRole(2, "VETERINARIAN", MANAGER, "ip"));
        assertTrue(none.getMessage().contains("No other Head Trainer has room"), none.getMessage());

        // Someone has room, but nobody was chosen: "Nobody" is not an option for a role change.
        when(staff.handoverCandidates("HEAD_TRAINER", 2, null)).thenReturn(java.util.List.of(trainer(4, 3, 1)));
        ApiException unchosen = expect(400, () -> service.changeRole(2, "VETERINARIAN", MANAGER, "ip"));
        assertTrue(unchosen.getFieldErrors().containsKey("handoverTo"), unchosen.getFieldErrors().toString());
        verify(staff, never()).updateRole(anyInt(), anyInt());
    }

    @Test
    void suspendedHeadTrainerReleasesHorsesToo() {
        StaffRepository staff = mock(StaffRepository.class);
        when(staff.findRow(2)).thenReturn(Optional.of(staffRow(2, "HEAD_TRAINER")));
        when(staff.horsesOfTrainer(2)).thenReturn(java.util.List.of(new StaffRepository.TrainerHorse(30, "Golden Hour", 7)));
        when(staff.groomIdsOfTrainer(2)).thenReturn(java.util.List.of());
        when(staff.runningSessionsOfTrainer(2)).thenReturn(java.util.List.of(new StaffRepository.RunningSession(40, 5, "Golden Hour")));
        NotificationService notifications = mock(NotificationService.class);
        StaffService service = new StaffService(mock(UserRepository.class), staff, mock(PasswordEncoder.class),
                mock(TokenBlacklist.class), notifications);

        StaffService.StaffUpdate result = service.setStatus(2, "SUSPENDED", MANAGER, "ip");
        verify(staff).releaseHorsesOfTrainer(2);
        assertTrue(result.notice().contains("Golden Hour"), result.notice());
        // The owner hears that Golden Hour has no Head Trainer now.
        verify(notifications).notifyUser(eq(7), org.mockito.ArgumentMatchers.argThat(
                n -> "HORSE_TRAINER_CHANGED".equals(n.type()) && n.message().contains("Golden Hour")));
        // Nobody supervises Golden Hour's running session now: its groom is asked to end it.
        verify(notifications).notifyUser(eq(5), org.mockito.ArgumentMatchers.argThat(
                n -> "SESSION_STOP_REQUESTED".equals(n.type()) && Integer.valueOf(40).equals(n.targetId())
                        && n.message().contains("No Head Trainer to supervise")));
        assertTrue(result.notice().contains("Asked the grooms to stop 1 running session (Golden Hour)"), result.notice());
    }

    @Test
    void groomWhoLeavesIsReportedToTheirOwnHeadTrainerOnly() {
        StaffRepository staff = mock(StaffRepository.class);
        NotificationService notifications = mock(NotificationService.class);
        StaffRow groom = new StaffRow(5, "khoa.lv", "Le Van Khoa", "k@club.test", null, "GROOM", "Groom",
                "ACTIVE", null, LocalDateTime.now(), 0, 2, "Ngo Anh Tuan", 0, 0, null);
        when(staff.findRow(5)).thenReturn(Optional.of(groom));
        when(staff.findRow(2)).thenReturn(Optional.of(staffRow(2, "HEAD_TRAINER")));
        when(staff.countOpenAssignments(5)).thenReturn(3);
        when(staff.countRunningSessions(5)).thenReturn(1);
        StaffService service = new StaffService(mock(UserRepository.class), staff, mock(PasswordEncoder.class),
                mock(TokenBlacklist.class), notifications);

        when(staff.stopRunningSessions(eq(5), anyString())).thenReturn(1);
        StaffService.StaffUpdate result = service.setStatus(5, "SUSPENDED", MANAGER, "ip");
        verify(notifications).notifyUser(eq(2), any());
        verify(notifications, never()).notifyRole(eq("HEAD_TRAINER"), any(), any());
        // The running session cannot wait for a groom who is gone: stopped and sent to review.
        verify(staff).stopRunningSessions(5, "Stopped: the groom was suspended");
        assertTrue(result.notice().contains("Stopped 1 running session"), result.notice());
    }

    @Test
    void groomWorkCanBeHandedToAGroomOfTheSameTeam() {
        StaffRepository staff = mock(StaffRepository.class);
        NotificationService notifications = mock(NotificationService.class);
        when(staff.findRow(5)).thenReturn(Optional.of(groom(5, 2)));
        when(staff.findRow(6)).thenReturn(Optional.of(groom(6, 2)));
        when(staff.handOverSessions(5, 6)).thenReturn(2);
        when(staff.handOverCareTasks(5, 6)).thenReturn(4);
        StaffService service = new StaffService(mock(UserRepository.class), staff, mock(PasswordEncoder.class),
                mock(TokenBlacklist.class), notifications);

        StaffService.StaffUpdate result = service.setStatus(5, "SUSPENDED", 6, MANAGER, "ip");
        verify(staff).handOverSessions(5, 6);
        verify(staff).handOverCareTasks(5, 6);
        verify(notifications).notifyUser(eq(6), any());
        assertTrue(result.notice().contains("2 sessions and 4 care tasks"), result.notice());
    }

    @Test
    void groomWorkCannotGoToAnotherTeam() {
        StaffRepository staff = mock(StaffRepository.class);
        when(staff.findRow(5)).thenReturn(Optional.of(groom(5, 2)));
        when(staff.findRow(6)).thenReturn(Optional.of(groom(6, 3)));
        StaffService service = staffService(staff, mock(TokenBlacklist.class));

        expect(400, () -> service.setStatus(5, "SUSPENDED", 6, MANAGER, "ip"));
        verify(staff, never()).updateStatus(anyInt(), anyString());
    }

    @Test
    void headTrainerHandOverNeedsRoomForAllTheHorses() {
        StaffRepository staff = mock(StaffRepository.class);
        when(staff.findRow(2)).thenReturn(Optional.of(trainer(2, 3, 1)));
        when(staff.findRow(4)).thenReturn(Optional.of(trainer(4, 8, 1)));   // 8 + 3 > 10
        StaffService service = staffService(staff, mock(TokenBlacklist.class));

        ApiException ex = expect(409, () -> service.setStatus(2, "SUSPENDED", 4, MANAGER, "ip"));
        assertTrue(ex.getMessage().contains("8 of 10 horses"), ex.getMessage());
        verify(staff, never()).updateStatus(anyInt(), anyString());
    }

    @Test
    void headTrainerHandOverMovesHorsesAndGroomsTogether() {
        StaffRepository staff = mock(StaffRepository.class);
        NotificationService notifications = mock(NotificationService.class);
        when(staff.findRow(2)).thenReturn(Optional.of(trainer(2, 2, 1)));
        when(staff.findRow(4)).thenReturn(Optional.of(trainer(4, 5, 1)));
        when(staff.horsesOfTrainer(2)).thenReturn(java.util.List.of(
                new StaffRepository.TrainerHorse(30, "River Song", 7), new StaffRepository.TrainerHorse(31, "Thunder Bay", 8)));
        when(staff.groomIdsOfTrainer(2)).thenReturn(java.util.List.of(5));
        when(staff.countSessionsToReviewOfTrainer(2)).thenReturn(3);
        StaffService service = new StaffService(mock(UserRepository.class), staff, mock(PasswordEncoder.class),
                mock(TokenBlacklist.class), notifications);

        StaffService.StaffUpdate result = service.setStatus(2, "SUSPENDED", 4, MANAGER, "ip");
        verify(staff).moveHorsesToTrainer(2, 4);
        verify(staff).moveGroomsToTrainer(2, 4);
        verify(staff, never()).releaseHorsesOfTrainer(anyInt());
        verify(notifications).notifyUser(eq(4), any());
        verify(notifications).notifyUser(eq(5), any());
        // Each owner is told who trains their horse now.
        verify(notifications).notifyUser(eq(7), org.mockito.ArgumentMatchers.argThat(n -> n.message().contains("River Song")));
        verify(notifications).notifyUser(eq(8), org.mockito.ArgumentMatchers.argThat(n -> n.message().contains("Thunder Bay")));
        assertTrue(result.notice().contains("3 sessions to review"), result.notice());
    }

    @Test
    void vetWhoLeavesPutsTakenCasesBackInTheQueue() {
        StaffRepository staff = mock(StaffRepository.class);
        NotificationService notifications = mock(NotificationService.class);
        UserRepository users = mock(UserRepository.class);
        when(staff.findRow(3)).thenReturn(Optional.of(staffRow(3, "VETERINARIAN")));
        when(users.findRoleId("GROOM")).thenReturn(Optional.of(4));
        when(staff.returnCasesToQueue(3)).thenReturn(2);
        when(staff.countActiveOfRole("VETERINARIAN", 3)).thenReturn(0);
        when(staff.countPendingVetCalls()).thenReturn(1);
        StaffService service = new StaffService(users, staff, mock(PasswordEncoder.class),
                mock(TokenBlacklist.class), notifications);

        StaffService.StaffUpdate result = service.changeRole(3, "GROOM", MANAGER, "ip");
        verify(staff).returnCasesToQueue(3);
        verify(notifications).notifyRole(eq("VETERINARIAN"), any(), any());
        assertTrue(result.notice().contains("2 incident cases went back to the open queue"), result.notice());
        assertTrue(result.notice().contains("no other active Veterinarian"), result.notice());
    }

    @Test
    void vetCasesCanBeHandedToAnotherVet() {
        StaffRepository staff = mock(StaffRepository.class);
        NotificationService notifications = mock(NotificationService.class);
        when(staff.findRow(3)).thenReturn(Optional.of(staffRow(3, "VETERINARIAN")));
        when(staff.findRow(7)).thenReturn(Optional.of(staffRow(7, "VETERINARIAN")));
        when(staff.handOverCases(3, 7)).thenReturn(1);
        when(staff.countActiveOfRole("VETERINARIAN", 3)).thenReturn(1);
        StaffService service = new StaffService(mock(UserRepository.class), staff, mock(PasswordEncoder.class),
                mock(TokenBlacklist.class), notifications);

        StaffService.StaffUpdate result = service.setStatus(3, "SUSPENDED", 7, MANAGER, "ip");
        verify(staff).handOverCases(3, 7);
        verify(staff, never()).returnCasesToQueue(anyInt());
        verify(notifications).notifyUser(eq(7), any());
        assertTrue(result.notice().contains("Handed 1 incident case to User 7"), result.notice());
    }

    // ------------------------------------------------------------ transfer / readmit

    @Test
    void transferIsRefusedWhileTheOldOwnerStillHasADecision() {
        HorseRepository horses = mock(HorseRepository.class);
        when(horses.findById(10)).thenReturn(Optional.of(horse(10, 7, "ACADEMY", "ACTIVE", null, null)));
        when(horses.isActiveHorseOwner(8)).thenReturn(true);
        when(horses.pendingOnOwner(10)).thenReturn(new HorseRepository.PendingOnOwner(1, 0, 0));

        HorseAdminService service = new HorseAdminService(horses, mock(UserRepository.class), mock(NotificationService.class));
        ApiException ex = expect(409, () -> service.transferOwner(10, 8, "Sold", MANAGER, "ip"));
        assertTrue(ex.getMessage().contains("training plan"), ex.getMessage());
        verify(horses, never()).updateOwner(anyInt(), anyInt());
    }

    @Test
    void transferNeedsAnActiveHorseOwner() {
        HorseRepository horses = mock(HorseRepository.class);
        when(horses.findById(10)).thenReturn(Optional.of(horse(10, 7, "ACADEMY", "ACTIVE", null, null)));
        when(horses.isActiveHorseOwner(8)).thenReturn(false);

        HorseAdminService service = new HorseAdminService(horses, mock(UserRepository.class), mock(NotificationService.class));
        expect(400, () -> service.transferOwner(10, 8, "Sold", MANAGER, "ip"));
        verify(horses, never()).updateOwner(anyInt(), anyInt());
    }

    @Test
    void transferMovesTheHorseAndTellsBothOwners() {
        HorseRepository horses = mock(HorseRepository.class);
        NotificationService notifications = mock(NotificationService.class);
        when(horses.findById(10)).thenReturn(Optional.of(horse(10, 7, "ACADEMY", "ACTIVE", null, null)));
        when(horses.isActiveHorseOwner(8)).thenReturn(true);
        when(horses.pendingOnOwner(10)).thenReturn(new HorseRepository.PendingOnOwner(0, 0, 0));

        new HorseAdminService(horses, mock(UserRepository.class), notifications)
                .transferOwner(10, 8, "Sold", MANAGER, "ip");
        verify(horses).updateOwner(10, 8);
        verify(notifications).notifyUser(eq(7), any());
        verify(notifications).notifyUser(eq(8), any());
    }

    @Test
    void readmitNeedsAFreeStall() {
        HorseRepository horses = mock(HorseRepository.class);
        when(horses.findById(10)).thenReturn(Optional.of(horse(10, 7, "ACADEMY", "WITHDRAWN", null, null)));
        when(horses.isActiveHorseOwner(7)).thenReturn(true);
        when(horses.existsByStallNo("A-01", 10)).thenReturn(true);

        HorseAdminService service = new HorseAdminService(horses, mock(UserRepository.class), mock(NotificationService.class));
        expect(400, () -> service.readmit(10, "A-01", MANAGER, "ip"));
        verify(horses, never()).readmit(anyInt(), any());
    }

    @Test
    void readmitIsOnlyForAWithdrawnHorse() {
        HorseRepository horses = mock(HorseRepository.class);
        when(horses.findById(10)).thenReturn(Optional.of(horse(10, 7, "ACADEMY", "ACTIVE", null, null)));

        HorseAdminService service = new HorseAdminService(horses, mock(UserRepository.class), mock(NotificationService.class));
        expect(409, () -> service.readmit(10, "A-01", MANAGER, "ip"));
    }

    // ------------------------------------------------------------ pedigree reach

    @Test
    void ownerCannotPickAnotherOwnersClubHorseAsParent() {
        HorseRepository horses = mock(HorseRepository.class);
        HorseVitalsRepository vitals = mock(HorseVitalsRepository.class);
        LocalDate foalDob = LocalDate.now().minusYears(2);
        when(horses.findById(10)).thenReturn(Optional.of(horse(10, 7, "ACADEMY", "ACTIVE", null, foalDob)));
        // #20: a stallion of owner 9, old enough to be the sire.
        HorseResponse other = horse(20, 9, "ACADEMY", "ACTIVE", "STALLION", foalDob.minusYears(8));
        when(horses.findById(20)).thenReturn(Optional.of(other));
        when(vitals.findLatestSession(10)).thenReturn(Optional.empty());

        HorseService service = new HorseService(horses, vitals, mock(UserRepository.class));
        ApiException ex = expect(400, () -> service.updateByOwner(10,
                new HorseUpdateRequest("Horse 10", "TB", "COLT", foalDob, "Bay", 20, null), OWNER_7, "ip"));
        assertEquals("Choose a pedigree record or one of your own horses", ex.getFieldErrors().get("sireId"));
    }

    // ------------------------------------------------------------ parents are a fact (V41)

    @Test
    void ownerCannotChangeAParentAlreadyOnRecord() {
        HorseRepository horses = mock(HorseRepository.class);
        HorseVitalsRepository vitals = mock(HorseVitalsRepository.class);
        LocalDate foalDob = LocalDate.now().minusYears(3);
        when(horses.findById(10)).thenReturn(Optional.of(withParents(horse(10, 7, "ACADEMY", "ACTIVE", null, foalDob), 20, null)));
        when(horses.findById(21)).thenReturn(Optional.of(horse(21, 9, "PEDIGREE", "ACTIVE", "STALLION", foalDob.minusYears(9))));
        when(vitals.findLatestSession(10)).thenReturn(Optional.empty());

        HorseService service = new HorseService(horses, vitals, mock(UserRepository.class));
        ApiException ex = expect(400, () -> service.updateByOwner(10,
                new HorseUpdateRequest("Horse 10", "TB", "COLT", foalDob, "Bay", 21, null), OWNER_7, "ip"));
        assertTrue(ex.getFieldErrors().get("sireId").contains("already on record"), ex.getFieldErrors().toString());
        verify(horses, never()).updateBasics(anyInt(), any());
    }

    @Test
    void ownerCannotRemoveAParentAlreadyOnRecordEither() {
        HorseRepository horses = mock(HorseRepository.class);
        HorseVitalsRepository vitals = mock(HorseVitalsRepository.class);
        LocalDate foalDob = LocalDate.now().minusYears(3);
        when(horses.findById(10)).thenReturn(Optional.of(withParents(horse(10, 7, "ACADEMY", "ACTIVE", null, foalDob), null, 30)));
        when(vitals.findLatestSession(10)).thenReturn(Optional.empty());

        HorseService service = new HorseService(horses, vitals, mock(UserRepository.class));
        ApiException ex = expect(400, () -> service.updateByOwner(10,
                new HorseUpdateRequest("Horse 10", "TB", "COLT", foalDob, "Bay", null, null), OWNER_7, "ip"));
        assertTrue(ex.getFieldErrors().containsKey("damId"), ex.getFieldErrors().toString());
    }

    @Test
    void ownerCanDeclareAParentThatIsStillUnknown() {
        HorseRepository horses = mock(HorseRepository.class);
        HorseVitalsRepository vitals = mock(HorseVitalsRepository.class);
        LocalDate foalDob = LocalDate.now().minusYears(3);
        when(horses.findById(10)).thenReturn(Optional.of(horse(10, 7, "ACADEMY", "ACTIVE", null, foalDob)));
        when(horses.findById(21)).thenReturn(Optional.of(horse(21, 9, "PEDIGREE", "ACTIVE", "STALLION", foalDob.minusYears(9))));
        when(vitals.findLatestSession(10)).thenReturn(Optional.of(new racehorse.backend.horse.HorseVitalsResponse.LatestSession(
                java.time.LocalDateTime.now(), "FINISHED", 120, 160, 90, new java.math.BigDecimal("14.5"), "GOOD", 180)));

        HorseService service = new HorseService(horses, vitals, mock(UserRepository.class));
        // Even a horse that has trained: declaring an unknown parent is not changing its sex or age.
        service.updateByOwner(10, new HorseUpdateRequest("Horse 10", "TB", "COLT", foalDob, "Bay", 21, null), OWNER_7, "ip");
        verify(horses).updateBasics(eq(10), any());
    }

    @Test
    void parentsOnARegistrationRequestFollowTheSameRules() {
        HorseRepository horses = mock(HorseRepository.class);
        LocalDate foalDob = LocalDate.now().minusYears(2);
        // #20: owner 9's club stallion - real, but not Lan's to declare.
        when(horses.findById(20)).thenReturn(Optional.of(horse(20, 9, "ACADEMY", "ACTIVE", "STALLION", foalDob.minusYears(8))));
        // #31: a pedigree mare born only one year before the foal.
        when(horses.findById(31)).thenReturn(Optional.of(horse(31, 9, "PEDIGREE", "ACTIVE", "MARE", foalDob.minusYears(1))));

        HorseService service = new HorseService(horses, mock(HorseVitalsRepository.class), mock(UserRepository.class));
        ApiException ex = expect(400, () -> service.checkDeclaredParents(20, 31, foalDob, 7));
        assertEquals("Choose a pedigree record or one of your own horses", ex.getFieldErrors().get("sireId"));
        assertTrue(ex.getFieldErrors().get("damId").contains("at least 2 years"), ex.getFieldErrors().toString());
    }

    @Test
    void clubCorrectsParentsWithAReasonThatIsAuditedAndSentToTheOwner() {
        HorseRepository horses = mock(HorseRepository.class);
        UserRepository users = mock(UserRepository.class);
        NotificationService notifications = mock(NotificationService.class);
        LocalDate foalDob = LocalDate.now().minusYears(3);
        when(horses.findById(10)).thenReturn(Optional.of(withParents(horse(10, 7, "ACADEMY", "ACTIVE", null, foalDob), 20, null)));
        when(horses.findById(20)).thenReturn(Optional.of(horse(20, 9, "PEDIGREE", "ACTIVE", "STALLION", foalDob.minusYears(9))));
        // #21 belongs to another owner: the club may still name it, it is what the papers say.
        when(horses.findById(21)).thenReturn(Optional.of(horse(21, 9, "ACADEMY", "ACTIVE", "STALLION", foalDob.minusYears(7))));
        HorseService service = new HorseService(horses, mock(HorseVitalsRepository.class), users, notifications);

        expect(400, () -> service.correctParents(10, 21, null, "  ", MANAGER, "ip"));
        verify(horses, never()).updateParents(anyInt(), any(), any());

        service.correctParents(10, 21, null, "Papers from the breeder", MANAGER, "ip");
        verify(horses).updateParents(10, 21, null);
        verify(users).writeAudit(eq(1), eq("HORSE_PARENTS_CORRECT"), eq("HORSE"), eq(10), eq("SUCCESS"), eq("ip"),
                eq("Sire: Horse 20 → Horse 21. Reason: Papers from the breeder"));
        verify(notifications).notifyUser(eq(7), any());
    }

    @Test
    void onlyTheClubManagerCorrectsParents() {
        HorseService service = new HorseService(mock(HorseRepository.class), mock(HorseVitalsRepository.class),
                mock(UserRepository.class));
        expect(403, () -> service.correctParents(10, 21, null, "papers", OWNER_7, "ip"));
    }

    private static HorseResponse withParents(HorseResponse h, Integer sireId, Integer damId) {
        return new HorseResponse(h.horseId(), h.ownerId(), h.ownerName(), sireId, damId, h.horseName(),
                h.registrationCode(), h.breed(), h.horseGender(), h.dateOfBirth(), h.color(), h.weight(),
                h.stallNo(), h.status(), h.createdAt(), h.updatedAt(), h.recordType(), h.photoUrl(),
                h.activityStatus(), h.withdrawnAt());
    }

    // ------------------------------------------------------------ helpers

    private static StaffService staffService(StaffRepository staff, TokenBlacklist blacklist) {
        return new StaffService(mock(UserRepository.class), staff, mock(PasswordEncoder.class), blacklist,
                mock(NotificationService.class));
    }

    private static UserRecord user(int id, String role, Instant lockedUntil) {
        return new UserRecord(id, "user" + id, "$2a$hash", "User " + id, "user" + id + "@club.test",
                "ACTIVE", role, null, false, lockedUntil);
    }

    private static StaffRow groom(int id, int trainerId) {
        return new StaffRow(id, "groom" + id, "User " + id, "g" + id + "@club.test", null, "GROOM", "Groom",
                "ACTIVE", null, LocalDateTime.now(), 0, trainerId, "User " + trainerId, 0, 0, null);
    }

    /** Head Trainer with this many active horses and grooms, max 10 horses. */
    private static StaffRow trainer(int id, int horses, int grooms) {
        return new StaffRow(id, "ht" + id, "User " + id, "t" + id + "@club.test", null, "HEAD_TRAINER", "Head Trainer",
                "ACTIVE", null, LocalDateTime.now(), 0, null, null, grooms, horses, 10);
    }

    private static StaffRow staffRow(int id, String role) {
        return new StaffRow(id, "user" + id, "User " + id, "user" + id + "@club.test", null, role, role,
                "ACTIVE", null, LocalDateTime.now(), 0);
    }

    /** gender null = COLT; dob null = 3 years old. */
    private static HorseResponse horse(int id, int ownerId, String type, String activity, String gender, LocalDate dob) {
        return new HorseResponse(id, ownerId, "Owner " + ownerId, null, null, "Horse " + id, "TM-" + id, "TB",
                gender == null ? "COLT" : gender, dob == null ? LocalDate.now().minusYears(3) : dob,
                "Bay", null, "A-" + id, "HEALTHY", LocalDateTime.now(), null, type, null, activity,
                "WITHDRAWN".equals(activity) ? LocalDateTime.now().minusDays(30) : null);
    }

    private static ApiException expect(int status, org.junit.jupiter.api.function.Executable run) {
        ApiException ex = assertThrows(ApiException.class, run);
        assertEquals(status, ex.getStatus().value(), ex.getMessage());
        return ex;
    }
}
