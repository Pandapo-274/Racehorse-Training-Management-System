package racehorse.backend.service;

/** Cổng gửi mail đặt lại mật khẩu. Bản thật nằm ở EmailResetMailSender. */
// Interface tách "gửi mail" khỏi PasswordService: test có thể thay bằng bản giả (mock) mà
// không cần SMTP thật. Spring tự tiêm bean duy nhất cài đặt interface này (EmailResetMailSender).
public interface ResetMailSender {
    void sendResetLink(String toEmail, String fullName, String resetLink, int validMinutes);
}
