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

    // ------------------------------------------------------------ helpers

    private static StaffService staffService(StaffRepository staff, TokenBlacklist blacklist) {
        return new StaffService(mock(UserRepository.class), staff, mock(PasswordEncoder.class), blacklist,
                mock(NotificationService.class));
    }

    private static UserRecord user(int id, String role, Instant lockedUntil) {
        return new UserRecord(id, "user" + id, "$2a$hash", "User " + id, "user" + id + "@club.test",
                "ACTIVE", role, null, false, lockedUntil);
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
