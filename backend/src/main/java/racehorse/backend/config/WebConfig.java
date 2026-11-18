package racehorse.backend.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

import racehorse.backend.controller.ClubStatsController;
import racehorse.backend.security.AuthInterceptor;
import racehorse.backend.service.PermissionService;

/**
 * Cấu hình Spring MVC: gắn AuthInterceptor ("người gác cổng") vào mọi URL /api/**.
 *
 * Luồng một request: Trình duyệt -> Tomcat -> DispatcherServlet -> AuthInterceptor.preHandle
 * (kiểm JWT, trạng thái tài khoản, @RequireRole) -> Controller -> Service -> Repository -> DB.
 * Nếu preHandle ném ApiException thì controller không bao giờ được gọi; lỗi được
 * GlobalExceptionHandler đổi thành JSON {status, message, errors, timestamp}.
 *
 * Mọi endpoint công khai mới (không cần đăng nhập) PHẢI được thêm vào PUBLIC_PATHS.
 */
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
            // Hai đường này vẫn còn trong danh sách nhưng controller của chúng đã bị xoá, nên gọi
            // tới chỉ nhận 404. Giữ lại không gây hại vì không còn code nào phục vụ chúng.
            "/api/hello", "/api/db-test",
            // Ba số tổng trên trang chủ / trang đăng nhập, mở trước khi đăng nhập (ClubStatsController).
            "/api/public/club-stats"};

    private final AuthInterceptor authInterceptor;

    // Constructor injection: Spring tự truyền bean AuthInterceptor (@Component) vào đây.
    public WebConfig(AuthInterceptor authInterceptor) {
        this.authInterceptor = authInterceptor;
    }

    /*
     * Ghi chú thêm khi đọc chú thích bên dưới: chú thích gốc nói "chỉ bốn đường" và
     * "/api/hello, /api/db-test đã bị bỏ", nhưng thực tế PUBLIC_PATHS hiện có 8 phần tử
     * (login, register, forgot, reset, google, hello, db-test, club-stats). Hai đường
     * hello/db-test không còn controller nên vô hại; danh sách PUBLIC_PATHS ở trên mới
     * là nguồn sự thật.
     */
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
