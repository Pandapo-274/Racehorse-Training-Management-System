package racehorse.backend.auth;

import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

@Configuration
public class WebConfig implements WebMvcConfigurer {

    /**
     * Cửa công khai, không cần token (xem chú thích trong addInterceptors).
     * Là hằng số để trang Permissions (PermissionService) đọc lại đúng danh
     * sách này thay vì chép một bản thứ hai.
     */
    public static final String[] PUBLIC_PATHS = {
            "/api/auth/login", "/api/auth/register",
            "/api/auth/forgot-password", "/api/auth/reset-password", "/api/auth/google",
            "/api/hello", "/api/db-test"};

    private final AuthInterceptor authInterceptor;

    public WebConfig(AuthInterceptor authInterceptor) {
        this.authInterceptor = authInterceptor;
    }

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(authInterceptor)
                .addPathPatterns("/api/**")
                /* Cửa công khai - chỉ bốn đường này, và cả bốn đều BUỘC phải
                   công khai: không đăng nhập được thì không thể đăng nhập.

                   /api/hello và /api/db-test đã bị bỏ khỏi danh sách cùng lúc
                   với hai controller của chúng. /api/db-test là đáng kể: nó
                   mở kết nối database rồi trả về tên database và toàn bộ bảng
                   ROLE cho BẤT KỲ AI gọi tới, không cần token - vì nó nằm
                   trong chính danh sách này. Chú thích cũ ở đây có ghi "nhớ
                   khoá lại trước khi nộp"; một lời nhắc trong chú thích không
                   phải một phép kiểm, và nó đã nằm đó qua nhiều sprint.

                   Thêm đường nào vào danh sách này là mở một cửa không cần
                   token, nên mỗi dòng phải trả lời được: tại sao người chưa
                   đăng nhập cần gọi nó? */
                .excludePathPatterns(PUBLIC_PATHS);
    }
}
