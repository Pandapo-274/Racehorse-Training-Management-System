package racehorse.backend.security;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.stereotype.Component;

import racehorse.backend.repository.UserRepository;

/**
 * Sổ đen trong RAM, có 2 loại:
 *  1) revoked: từng token cụ thể đã logout (theo jti), nhớ tới lúc token hết hạn tự nhiên.
 *  2) sessionsRevokedBefore: "mọi token của user X phát hành TRƯỚC mốc này đều chết" (dùng khi đổi/reset mật khẩu).
 * Loại 2 nay được ghi cả vào APP_USER.tokens_valid_after (V39), nên restart không
 * làm sống lại token cũ sau khi đổi mật khẩu hay bị khoá. Loại 1 (đăng xuất một
 * thiết bị) vẫn chỉ ở RAM: lưu bền cần một bảng jti riêng, ngoài ERD hiện tại.
 */
@Component
public class TokenBlacklist {

    private final Map<String, Instant> revoked = new ConcurrentHashMap<>();
    private final Map<Integer, Instant> sessionsRevokedBefore = new ConcurrentHashMap<>();
    private final UserRepository users;

    public TokenBlacklist(UserRepository users) {
        this.users = users;
    }

    public void revoke(String tokenId, Instant expiresAt) {
        Instant now = Instant.now();
        revoked.entrySet().removeIf(e -> e.getValue().isBefore(now)); // dọn token đã hết hạn
        revoked.put(tokenId, expiresAt);
    }

    public boolean isRevoked(String tokenId) {
        return revoked.containsKey(tokenId);
    }

    /** Đá văng mọi thiết bị đang đăng nhập của user này. */
    public void revokeAllSessions(int userId) {
        // JWT chỉ lưu thời điểm phát hành theo GIÂY nên mốc cũng cắt về giây để so sánh công bằng.
        Instant cutoff = Instant.now().truncatedTo(ChronoUnit.SECONDS);
        sessionsRevokedBefore.put(userId, cutoff);
        /* V39: also in APP_USER.tokens_valid_after, which AuthInterceptor reads on
           every request - the map above is lost when the backend restarts. */
        users.setTokensValidAfter(userId, cutoff);
    }

    public boolean isIssuedBeforeCutoff(int userId, Instant issuedAt) {
        Instant cutoff = sessionsRevokedBefore.get(userId);
        return cutoff != null && issuedAt.isBefore(cutoff);
    }
}
