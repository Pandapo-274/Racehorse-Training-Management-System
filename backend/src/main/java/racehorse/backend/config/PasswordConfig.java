package racehorse.backend.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;

/**
 * Khai báo bean PasswordEncoder dùng chung cho cả hệ thống (AuthService, PasswordService,
 * StaffService, ProfileService... đều nhận qua constructor).
 *
 * Dự án KHÔNG dùng toàn bộ Spring Security (không có filter chain, không có login form
 * của Spring); chỉ lấy module spring-security-crypto để băm mật khẩu. Phần xác thực
 * được tự viết bằng JWT + AuthInterceptor.
 *
 * Cột APP_USER.password_hash lưu chuỗi dạng "$2a$10$<salt 22 ký tự><hash 31 ký tự>":
 * "10" là cost factor (2^10 vòng băm), đủ chậm để chống dò mật khẩu hàng loạt.
 */
@Configuration
public class PasswordConfig {
    /** BCrypt: tự random salt, mỗi lần hash ra chuỗi khác nhau, vẫn verify được bằng matches(). */
    // encode(raw) khi tạo/đổi mật khẩu; matches(raw, hash) khi đăng nhập.
    @Bean
    public PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder();
    }
}
