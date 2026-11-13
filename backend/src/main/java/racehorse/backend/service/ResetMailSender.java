package racehorse.backend.service;

/** Cổng gửi mail đặt lại mật khẩu. Bản thật nằm ở EmailResetMailSender. */
public interface ResetMailSender {
    void sendResetLink(String toEmail, String fullName, String resetLink, int validMinutes);
}
