package racehorse.backend.entity;

/** Một dòng APP_USER (join ROLE) đọc từ DB. Chỉ dùng nội bộ backend vì có passwordHash. */
// Khác StaffRow/ProfileResponse (trả ra frontend), UserRecord mang passwordHash nên chỉ
// dùng bên trong AuthService/PasswordService để so mật khẩu; KHÔNG bao giờ trả về client.
public record UserRecord(int userId, String username, String passwordHash,
                         String fullName, String email, String status, String role,
                         /* Thêm ở CUỐI chứ không chen vào giữa: record dựng theo thứ tự
                            tham số, chen vào giữa là mọi lời gọi new UserRecord(...) im
                            lặng gán lệch cột mà vẫn biên dịch được nếu kiểu trùng nhau. */
                         String avatarUrl,
                         /* V14: đang dùng mật khẩu tạm do Club Manager đặt, phải đổi trước
                            khi dùng hệ thống. Cũng thêm ở cuối, cùng lý do như trên. */
                         boolean mustChangePassword,
                         /* V39: sign-in locked until this moment (UTC) after too many
                            wrong passwords; null = not locked. */
                         java.time.Instant lockedUntil) {

    // Đang bị khoá đăng nhập nếu mốc khoá còn ở tương lai (sai mật khẩu 5 lần, V39).
    public boolean isLocked() {
        return lockedUntil != null && lockedUntil.isAfter(java.time.Instant.now());
    }
}
