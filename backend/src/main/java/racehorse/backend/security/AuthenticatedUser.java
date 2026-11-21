package racehorse.backend.security;

import java.time.Instant;

/** Người đang gọi API, lấy ra từ JWT. Controller nhận qua @RequestAttribute("authUser"). */
/*
 * record = class bất biến (immutable) của Java 16+: tự sinh constructor, getter
 * (userId(), role()...), equals/hashCode/toString. Các trường:
 *   userId     - APP_USER.user_id (claim "sub" của JWT)
 *   username   - tên đăng nhập (claim "username")
 *   role       - CLUB_MANAGER | HEAD_TRAINER | VETERINARIAN | GROOM | HORSE_OWNER
 *   tokenId    - jti, mã duy nhất của token, dùng để thu hồi đúng token khi logout
 *   expiresAt  - thời điểm token hết hạn (mặc định 480 phút sau khi cấp)
 *   issuedAt   - thời điểm cấp, so với tokens_valid_after để biết token đã bị thu hồi chưa
 * Service nhận đối tượng này (thường đặt tên "me") để biết AI đang thao tác, kiểm
 * quyền theo dữ liệu (vd. chủ ngựa chỉ xem ngựa của mình) và ghi audit.
 */
public record AuthenticatedUser(int userId, String username, String role,
                                String tokenId, Instant expiresAt, Instant issuedAt) {}
