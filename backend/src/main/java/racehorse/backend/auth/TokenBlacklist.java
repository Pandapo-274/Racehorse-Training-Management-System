package racehorse.backend.auth;

import java.time.Instant;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.stereotype.Component;

/**
 * Sổ đen các token đã logout, lưu trong RAM.
 * Chỉ cần nhớ token tới lúc nó hết hạn tự nhiên (hết hạn thì JwtService đã tự từ chối).
 * Hạn chế: restart server là sổ đen mất; chạy nhiều server thì không chia sẻ.
 * Muốn bền hơn thì chuyển sang bảng DB hoặc Redis.
 */
@Component
public class TokenBlacklist {

    private final Map<String, Instant> revoked = new ConcurrentHashMap<>();

    public void revoke(String tokenId, Instant expiresAt) {
        Instant now = Instant.now();
        revoked.entrySet().removeIf(e -> e.getValue().isBefore(now)); // dọn token đã hết hạn
        revoked.put(tokenId, expiresAt);
    }

    public boolean isRevoked(String tokenId) {
        return revoked.containsKey(tokenId);
    }
}
