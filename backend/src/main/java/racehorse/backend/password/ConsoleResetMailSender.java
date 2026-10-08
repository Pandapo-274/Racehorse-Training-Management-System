package racehorse.backend.password;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/** CHỈ DÙNG KHI DEV: in link ra log thay vì gửi mail thật. Production tuyệt đối không log token. */
@Component
public class ConsoleResetMailSender implements ResetMailSender {

    private static final Logger log = LoggerFactory.getLogger(ConsoleResetMailSender.class);

    @Override
    public void sendResetLink(String toEmail, String fullName, String resetLink) {
        log.info("[DEV MAIL] To: {} ({}) | Password reset link: {}", toEmail, fullName, resetLink);
    }
}
