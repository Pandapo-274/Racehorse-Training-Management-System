package racehorse.backend.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableAsync;

/*
 * @EnableAsync làm cho mọi phương thức gắn @Async (vd. EmailResetMailSender.send)
 * được Spring chạy trên một luồng (thread) khác. Request HTTP trả về ngay cho người
 * dùng, còn việc kết nối SMTP Gmail chạy ngầm phía sau.
 * Lưu ý: @Async chỉ có tác dụng khi gọi qua bean do Spring quản lý (qua proxy), gọi
 * trực tiếp this.method() trong cùng class sẽ KHÔNG chạy nền.
 */
/** Bật @Async để gửi mail chạy nền, API không phải đợi SMTP (thường 1-3 giây). */
@Configuration
@EnableAsync
public class AsyncConfig {
}
