package racehorse.backend.password;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.HexFormat;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import racehorse.backend.auth.ApiException;
import racehorse.backend.auth.AuthenticatedUser;
import racehorse.backend.auth.TokenBlacklist;
import racehorse.backend.auth.UserRecord;
import racehorse.backend.auth.UserRepository;

@Service
public class PasswordService {

    private static final int RESET_TOKEN_MINUTES = 30;

    private final UserRepository users;
    private final PasswordRepository passwords;
    private final PasswordEncoder encoder;
    private final TokenBlacklist blacklist;
    private final ResetMailSender mailSender;
    private final String frontendBaseUrl;
    private final SecureRandom random = new SecureRandom();

    public PasswordService(UserRepository users, PasswordRepository passwords, PasswordEncoder encoder,
                           TokenBlacklist blacklist, ResetMailSender mailSender,
                           @Value("${app.frontend-base-url:http://localhost:3000}") String frontendBaseUrl) {
        this.users = users;
        this.passwords = passwords;
        this.encoder = encoder;
        this.blacklist = blacklist;
        this.mailSender = mailSender;
        this.frontendBaseUrl = frontendBaseUrl;
    }

    /**
     * Không gắn @Transactional có chủ đích: khi nhập sai mật khẩu hiện tại ta ghi audit DENIED rồi ném lỗi,
     * có transaction thì dòng audit bị rollback mất.
     */
    public void changePassword(AuthenticatedUser me, ChangePasswordRequest req, String ip) {
        UserRecord u = users.findById(me.userId())
                .orElseThrow(() -> new ApiException(HttpStatus.UNAUTHORIZED, "Account no longer exists"));

        if (!encoder.matches(req.currentPassword(), u.passwordHash())) {
            users.writeAudit(u.userId(), "CHANGE_PASSWORD", "APP_USER", u.userId(), "DENIED", ip);
            // 400 chứ không phải 401: 401 sẽ làm frontend tưởng hết phiên rồi đá user ra trang login.
            throw fieldError("currentPassword", "Current password is incorrect");
        }
        if (encoder.matches(req.newPassword(), u.passwordHash())) {
            throw fieldError("newPassword", "New password must be different from the current password");
        }

        passwords.updatePasswordHash(u.userId(), encoder.encode(req.newPassword()));
        blacklist.revoke(me.tokenId(), me.expiresAt()); // token đang dùng chết ngay
        blacklist.revokeAllSessions(u.userId());        // các thiết bị khác cũng bị đăng xuất
        users.writeAudit(u.userId(), "CHANGE_PASSWORD", "APP_USER", u.userId(), "SUCCESS", ip);
    }

    /**
     * Luôn trả cùng một kết quả dù email có tồn tại hay không, để kẻ xấu không dò được
     * "email này có tài khoản không". @Transactional: lỡ gửi mail lỗi thì token cũng rollback.
     */
    @Transactional
    public void forgotPassword(ForgotPasswordRequest req, String ip) {
        String email = req.email().trim().toLowerCase(Locale.ROOT);
        Optional<UserRecord> found = users.findByLogin(email);
        if (found.isEmpty() || !"ACTIVE".equals(found.get().status())) {
            return;
        }
        UserRecord u = found.get();

        // Token gốc chỉ tồn tại trong link gửi cho user. DB chỉ giữ hash: lộ DB cũng không dùng được link.
        byte[] buf = new byte[32];
        random.nextBytes(buf);
        String token = Base64.getUrlEncoder().withoutPadding().encodeToString(buf);

        passwords.invalidateOpenTokens(u.userId());
        passwords.insertToken(u.userId(), sha256Hex(token), RESET_TOKEN_MINUTES);
        users.writeAudit(u.userId(), "FORGOT_PASSWORD", "APP_USER", u.userId(), "SUCCESS", ip);

        mailSender.sendResetLink(u.email(), u.fullName(), frontendBaseUrl + "/reset-password?token=" + token);
    }

    @Transactional
    public void resetPassword(ResetPasswordRequest req, String ip) {
        Integer userId = passwords.consumeValidToken(sha256Hex(req.token().trim()))
                .orElseThrow(() -> fieldError("token", "Reset link is invalid or has expired"));

        UserRecord u = users.findById(userId)
                .orElseThrow(() -> fieldError("token", "Reset link is invalid or has expired"));
        if (!"ACTIVE".equals(u.status())) {
            throw new ApiException(HttpStatus.FORBIDDEN, "Account is suspended. Please contact the club manager");
        }

        passwords.updatePasswordHash(userId, encoder.encode(req.newPassword()));
        blacklist.revokeAllSessions(userId); // lỡ mật khẩu bị lộ thì kẻ đang đăng nhập cũng bị đá ra
        users.writeAudit(userId, "RESET_PASSWORD", "APP_USER", userId, "SUCCESS", ip);
    }

    private static ApiException fieldError(String field, String msg) {
        return new ApiException(HttpStatus.BAD_REQUEST, msg, Map.of(field, msg));
    }

    private static String sha256Hex(String s) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(md.digest(s.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
