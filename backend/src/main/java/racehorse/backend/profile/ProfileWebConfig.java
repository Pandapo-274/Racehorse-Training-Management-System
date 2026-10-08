package racehorse.backend.profile;

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

    private final String location;

    public ProfileWebConfig(@Value("${app.upload.dir:uploads}") String uploadDir) {
        String uri = Path.of(uploadDir).toAbsolutePath().normalize().toUri().toString();
        this.location = uri.endsWith("/") ? uri : uri + "/";
    }

    @Override
    public void addResourceHandlers(ResourceHandlerRegistry registry) {
        registry.addResourceHandler("/uploads/**").addResourceLocations(location);
    }
}
