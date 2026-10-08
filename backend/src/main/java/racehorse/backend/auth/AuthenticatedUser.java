package racehorse.backend.auth;

import java.time.Instant;

/** Người đang gọi API, lấy ra từ JWT. Controller nhận qua @RequestAttribute("authUser"). */
public record AuthenticatedUser(int userId, String username, String role,
                                String tokenId, Instant expiresAt, Instant issuedAt) {}
