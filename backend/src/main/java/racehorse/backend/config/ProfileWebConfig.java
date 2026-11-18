package racehorse.backend.config;

import java.nio.file.Path;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.ResourceHandlerRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * Cho trình duyệt xem ảnh qua URL /uploads/**. Nằm ngoài /api/** nên không cần token
 * (thẻ <img> không gắn được header Authorization). Tên file có UUID nên khó đoán.
 */
@Configuration
public class ProfileWebConfig implements WebMvcConfigurer {

    // Đường dẫn tuyệt đối dạng URI tới thư mục upload, vd. "file:/E:/.../backend/uploads/".
    private final String location;

    /*
     * @Value đọc thuộc tính app.upload.dir trong application.properties (mặc định "uploads",
     * tức thư mục backend/uploads khi chạy từ thư mục backend). ProfileService và
     * HorsePhotoService ghi file vào đúng thư mục này (avatars/, horses/...).
     * Dấu "/" ở cuối là bắt buộc: thiếu nó Spring sẽ hiểu "uploads" là một file chứ
     * không phải thư mục.
     */
    public ProfileWebConfig(@Value("${app.upload.dir:uploads}") String uploadDir) {
        String uri = Path.of(uploadDir).toAbsolutePath().normalize().toUri().toString();
        this.location = uri.endsWith("/") ? uri : uri + "/";
    }

    /*
     * Ánh xạ URL -> thư mục trên đĩa: GET /uploads/avatars/abc.jpg trả về file
     * <upload.dir>/avatars/abc.jpg. Đây là "static resource", không đi qua controller
     * nên cũng không đi qua AuthInterceptor (interceptor chỉ gắn cho /api/**).
     */
    @Override
    public void addResourceHandlers(ResourceHandlerRegistry registry) {
        registry.addResourceHandler("/uploads/**").addResourceLocations(location);
    }
}
