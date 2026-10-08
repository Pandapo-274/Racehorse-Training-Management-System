package racehorse.backend.password;

/**
 * Cổng gửi mail. Hiện chỉ có bản in ra console (ConsoleResetMailSender).
 * Khi có SMTP: viết thêm 1 class implements interface này (dùng JavaMailSender) và bỏ @Component ở bản console.
 * Logic đặt lại mật khẩu không phải sửa gì.
 */
public interface ResetMailSender {
    void sendResetLink(String toEmail, String fullName, String resetLink);
}
