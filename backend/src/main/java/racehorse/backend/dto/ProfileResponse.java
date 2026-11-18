package racehorse.backend.dto;

import java.time.Instant;

/** Hồ sơ cá nhân trả cho frontend. Không có password_hash. */
// Kết quả GET/PUT /api/profile và POST /api/profile/avatar (ProfileController).
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
