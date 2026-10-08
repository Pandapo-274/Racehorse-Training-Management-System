package racehorse.backend.profile;

import java.time.Instant;

/** Hồ sơ cá nhân trả cho frontend. Không có password_hash. */
public record ProfileResponse(
        int userId,
        String username,
        String fullName,
        String email,
        String phone,
        String avatarUrl,
        String role,
        String status,
        Instant createdAt
) {}
