package racehorse.backend.password;

import jakarta.mail.internet.MimeMessage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.mail.javamail.MimeMessageHelper;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;

/**
 * Gửi mail đặt lại mật khẩu qua SMTP.
 *  - app.mail.enabled=true  : gửi mail thật (cần cấu hình spring.mail.*).
 *  - app.mail.enabled=false : (mặc định) chỉ in link ra log để dev test, máy ai chưa có SMTP vẫn chạy bình thường.
 * @Async: chạy ở luồng khác. Nhờ vậy thời gian phản hồi của API không phụ thuộc việc email có tồn tại hay không.
 */
@Component
public class EmailResetMailSender implements ResetMailSender {

    private static final Logger log = LoggerFactory.getLogger(EmailResetMailSender.class);

    // ObjectProvider: nếu chưa cấu hình spring.mail.host thì không có JavaMailSender, app vẫn khởi động được.
    private final ObjectProvider<JavaMailSender> mailSenderProvider;
    private final boolean enabled;
    private final String from;

    public EmailResetMailSender(ObjectProvider<JavaMailSender> mailSenderProvider,
                                @Value("${app.mail.enabled:false}") boolean enabled,
                                @Value("${app.mail.from:}") String configuredFrom,
                                @Value("${spring.mail.username:}") String smtpUsername) {
        this.mailSenderProvider = mailSenderProvider;
        this.enabled = enabled;
        this.from = (configuredFrom == null || configuredFrom.isBlank()) ? smtpUsername : configuredFrom;
    }

    @Async
    @Override
    public void sendResetLink(String toEmail, String fullName, String resetLink, int validMinutes) {
        if (!enabled) {
            // CHỈ KHI DEV. Khi bật mail thật thì tuyệt đối không log link vì trong đó có token.
            log.info("[DEV MAIL - app.mail.enabled=false] To: {} | Reset link: {}", toEmail, resetLink);
            return;
        }
        JavaMailSender sender = mailSenderProvider.getIfAvailable();
        if (sender == null) {
            log.error("Cannot send reset email: app.mail.enabled=true but spring.mail.host is not configured");
            return;
        }
        try {
            MimeMessage message = sender.createMimeMessage();
            MimeMessageHelper helper = new MimeMessageHelper(message, true, "UTF-8");
            if (from != null && !from.isBlank()) helper.setFrom(from);
            helper.setTo(toEmail);
            helper.setSubject("Reset your Racehorse Training Management System password");
            helper.setText(plainText(fullName, resetLink, validMinutes), htmlText(fullName, resetLink, validMinutes));
            sender.send(message);
            log.info("Password reset email sent to {}", toEmail);
        } catch (Exception e) {
            // Chỉ log: API đã trả 200 cho client (cố ý, để không lộ email nào có tài khoản).
            log.error("Failed to send password reset email to {}", toEmail, e);
        }
    }

    private static String plainText(String name, String link, int minutes) {
        return "Hi " + name + ",\n\n"
                + "We received a request to reset the password of your Racehorse Training Management System account.\n"
                + "Open this link to choose a new password:\n" + link + "\n\n"
                + "The link expires in " + minutes + " minutes and can be used only once.\n"
                + "If you did not request this, you can ignore this email. Your password will not change.\n";
    }

    private static String htmlText(String name, String link, int minutes) {
        String safeLink = esc(link);
        return "<div style=\"font-family:Arial,sans-serif;font-size:14px;color:#222;max-width:520px\">"
                + "<p>Hi " + esc(name) + ",</p>"
                + "<p>We received a request to reset the password of your "
                + "Racehorse Training Management System account.</p>"
                + "<p><a href=\"" + safeLink + "\" style=\"display:inline-block;padding:10px 18px;"
                + "background:#1f6feb;color:#fff;text-decoration:none;border-radius:6px\">Reset password</a></p>"
                + "<p>Or copy this link into your browser:<br><a href=\"" + safeLink + "\">" + safeLink + "</a></p>"
                + "<p>The link expires in " + minutes + " minutes and can be used only once.</p>"
                + "<p style=\"color:#666\">If you did not request this, you can ignore this email. "
                + "Your password will not change.</p></div>";
    }

    /** Họ tên do user tự nhập nên phải escape trước khi nhét vào HTML. */
    private static String esc(String s) {
        return s == null ? "" : s.replace("&", "&amp;").replace("<", "&lt;")
                .replace(">", "&gt;").replace("\"", "&quot;");
    }
}
