package racehorse.backend.password;

import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableAsync;

/** Bật @Async để gửi mail chạy nền, API không phải đợi SMTP (thường 1-3 giây). */
@Configuration
@EnableAsync
public class AsyncConfig {
}
