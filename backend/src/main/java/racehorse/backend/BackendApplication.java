package racehorse.backend;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.flyway.autoconfigure.FlywayMigrationStrategy;
import org.springframework.context.annotation.Bean;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * ĐIỂM KHỞI ĐỘNG của toàn bộ backend (chạy bằng `mvnw spring-boot:run`, cổng 8080).
 *
 * - @SpringBootApplication = @Configuration + @EnableAutoConfiguration + @ComponentScan.
 *   ComponentScan quét mọi class trong package racehorse.backend và các package con
 *   (controller, service, repository, security, config...). Class nào có @Component,
 *   @Service, @Repository, @RestController, @Configuration sẽ được Spring tự tạo một
 *   đối tượng duy nhất (singleton bean) và tự "tiêm" (inject) vào constructor của
 *   class khác cần nó. Vì vậy trong dự án không có chỗ nào gọi "new XxxService()".
 * - AutoConfiguration đọc application.properties để tự dựng DataSource (kết nối SQL
 *   Server), JdbcTemplate, Flyway, JavaMailSender, Jackson (JSON)... mà không cần code.
 * - @EnableScheduling bật các phương thức @Scheduled (job chạy theo giờ): MissedTaskJob
 *   (00:15 mỗi đêm), PreventiveReminderJob (07:00), SupplyDigestJob (07:05)...
 */
@SpringBootApplication
@EnableScheduling
public class BackendApplication {

	// Hàm main chuẩn của Java: giao toàn bộ việc khởi động cho Spring (dựng bean, chạy
	// Flyway migration, mở Tomcat nhúng ở cổng 8080).
	public static void main(String[] args) {
		SpringApplication.run(BackendApplication.class, args);
	}

	/**
	 * Ghi đè cách Flyway chạy migration lúc khởi động.
	 *
	 * - repair(): sửa bảng lịch sử flyway_schema_history trước khi migrate: xoá các dòng
	 *   migration bị lỗi dở dang và cập nhật lại checksum cho khớp với file .sql hiện tại.
	 *   Nhờ vậy nếu ai đó lỡ sửa một file migration đã chạy, backend vẫn khởi động được
	 *   (nhưng phần sửa đó KHÔNG được chạy lại trên database cũ, nên quy tắc của nhóm vẫn
	 *   là: không sửa migration đã áp dụng, luôn thêm file V<số mới>__mo_ta.sql).
	 * - migrate(): chạy các file V*.sql trong resources/migration chưa có trong lịch sử,
	 *   theo thứ tự số phiên bản (out-of-order=true trong properties cho phép chạy cả file
	 *   số nhỏ hơn đến muộn, vì các nhánh git thêm migration song song).
	 */
	@Bean
	public FlywayMigrationStrategy flywayMigrationStrategy() {
		return flyway -> {
			flyway.repair();
			flyway.migrate();
		};
	}

}
