package racehorse.backend.auth;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.stereotype.Component;

/**
 * Sổ đen trong RAM, có 2 loại:
 *  1) revoked: từng token cụ thể đã logout (theo jti), nhớ tới lúc token hết hạn tự nhiên.
 *  2) sessionsRevokedBefore: "mọi token của user X phát hành TRƯỚC mốc này đều chết" (dùng khi đổi/reset mật khẩu).
 * Hạn chế: restart server là mất sổ; chạy nhiều server thì không chia sẻ.
 * Muốn bền hơn thì chuyển sang bảng DB hoặc Redis.
 */
@Component
public class TokenBlacklist {

    private final Map<String, Instant> revoked = new ConcurrentHashMap<>();
    private final Map<Integer, Instant> sessionsRevokedBefore = new ConcurrentHashMap<>();

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
        sessionsRevokedBefore.put(userId, Instant.now().truncatedTo(ChronoUnit.SECONDS));
    }

    public boolean isIssuedBeforeCutoff(int userId, Instant issuedAt) {
        Instant cutoff = sessionsRevokedBefore.get(userId);
        return cutoff != null && issuedAt.isBefore(cutoff);
    }
}
