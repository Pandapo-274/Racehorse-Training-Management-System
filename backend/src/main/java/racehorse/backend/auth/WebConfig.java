package racehorse.backend.auth;

import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

@Configuration
public class WebConfig implements WebMvcConfigurer {

    private final AuthInterceptor authInterceptor;

    public WebConfig(AuthInterceptor authInterceptor) {
        this.authInterceptor = authInterceptor;
    }

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(authInterceptor)
                .addPathPatterns("/api/**")
                // Cửa công khai. db-test để tạm cho team debug, nhớ khoá lại trước khi nộp.
                .excludePathPatterns("/api/auth/login", "/api/auth/register",
                        "/api/auth/forgot-password", "/api/auth/reset-password",
                        "/api/hello", "/api/db-test");
    }
}
