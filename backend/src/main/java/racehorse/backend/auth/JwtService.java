package racehorse.backend.auth;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.JwtException;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Date;
import java.util.Optional;
import javax.crypto.SecretKey;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

/** Phát hành và kiểm tra JWT. Token được KÝ (chống sửa), không MÃ HOÁ: đừng nhét dữ liệu nhạy cảm vào. */
@Service
public class JwtService {

    private final SecretKey key;
    private final long expirationMinutes;

    public JwtService(@Value("${app.jwt.secret}") String secret,
                      @Value("${app.jwt.expiration-minutes:480}") long expirationMinutes) {
        byte[] bytes = secret.getBytes(StandardCharsets.UTF_8);
        if (bytes.length < 32) {
            throw new IllegalStateException("JWT_SECRET trong .env phải dài ít nhất 32 ký tự");
        }
        this.key = Keys.hmacShaKeyFor(bytes);
        this.expirationMinutes = expirationMinutes;
    }

    public String generate(AuthenticatedUser user) {
        Instant now = Instant.now();
        return Jwts.builder()
                .subject(String.valueOf(user.userId()))
                .claim("username", user.username())
                .claim("role", user.role())
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
                    c.get("role", String.class)));
        } catch (JwtException | IllegalArgumentException e) {
            return Optional.empty();
        }
    }

    public long expiresInSeconds() {
        return expirationMinutes * 60;
    }
}
