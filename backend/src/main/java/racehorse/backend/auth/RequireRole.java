package racehorse.backend.auth;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Gắn lên controller hoặc method để giới hạn vai trò được gọi.
 * Ví dụ: @RequireRole({"CLUB_MANAGER"}). Không gắn = chỉ cần đăng nhập.
 */
@Documented
@Target({ElementType.METHOD, ElementType.TYPE})
@Retention(RetentionPolicy.RUNTIME)
public @interface RequireRole {
    String[] value();
}
