package racehorse.backend.security;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.JwtException;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Date;
import java.util.Optional;
import java.util.UUID;
import javax.crypto.SecretKey;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

/** Phát hành và kiểm tra JWT. Token được KÝ (chống sửa), không MÃ HOÁ: đừng nhét dữ liệu nhạy cảm vào. */
/*
 * JWT (JSON Web Token) gồm 3 phần base64url nối bằng dấu chấm: header.payload.signature.
 * Payload hệ thống này phát ra, ví dụ:
 *   { "jti": "6f1c...", "sub": "12", "username": "anh.dq", "role": "HEAD_TRAINER",
 *     "iat": 1763350000, "exp": 1763378800 }
 * Chữ ký HS256 = HMAC-SHA256(header.payload, JWT_SECRET). Ai không có JWT_SECRET thì
 * không thể tạo/sửa token hợp lệ, nên backend tin được role trong token mà không cần
 * lưu "phiên đăng nhập" phía server (stateless). Nhược điểm của stateless: muốn thu
 * hồi token trước hạn phải có danh sách đen (TokenBlacklist + APP_USER.tokens_valid_after).
 */
@Service
public class JwtService {

    private final SecretKey key;
    private final long expirationMinutes;

    /*
     * JWT_SECRET đọc từ backend/.env (thiếu biến này backend không khởi động được vì
     * ${app.jwt.secret} không có giá trị mặc định). HS256 đòi khoá tối thiểu 256 bit
     * = 32 byte, nên kiểm tra độ dài ngay khi khởi động để báo lỗi rõ ràng.
     */
    public JwtService(@Value("${app.jwt.secret}") String secret,
                      @Value("${app.jwt.expiration-minutes:480}") long expirationMinutes) {
        byte[] bytes = secret.getBytes(StandardCharsets.UTF_8);
        if (bytes.length < 32) {
            throw new IllegalStateException("JWT_SECRET in .env must be at least 32 characters long");
        }
        this.key = Keys.hmacShaKeyFor(bytes);
        this.expirationMinutes = expirationMinutes;
    }

    // Gọi khi đăng nhập thành công (AuthService.login, GoogleAuthService). Kết quả gửi về
    // frontend, frontend lưu vào localStorage "token" và gắn vào mọi request sau đó.
    public String generate(int userId, String username, String role) {
        Instant now = Instant.now();
        return Jwts.builder()
                .id(UUID.randomUUID().toString()) // jti: "số seri" của vé, để logout thu hồi đúng vé này
                .subject(String.valueOf(userId))
                .claim("username", username)
                .claim("role", role)
                .issuedAt(Date.from(now))
                .expiration(Date.from(now.plus(expirationMinutes, ChronoUnit.MINUTES)))
                .signWith(key, Jwts.SIG.HS256)
                .compact();
    }

    /** Token sai chữ ký, hết hạn hoặc rác thì trả empty, không ném lỗi. */
    public Optional<AuthenticatedUser> parse(String token) {
        try {
            Claims c = Jwts.parser().verifyWith(key).build()
                    .parseSignedClaims(token).getPayload();
            return Optional.of(new AuthenticatedUser(
                    Integer.parseInt(c.getSubject()),
                    c.get("username", String.class),
                    c.get("role", String.class),
                    c.getId(),
                    c.getExpiration().toInstant(),
                    c.getIssuedAt().toInstant()));
        } catch (JwtException | IllegalArgumentException e) {
            return Optional.empty();
        }
    }

    // Trả về cho frontend trong LoginResponse (expiresIn) để biết token sống bao lâu.
    public long expiresInSeconds() {
        return expirationMinutes * 60;
    }
}
