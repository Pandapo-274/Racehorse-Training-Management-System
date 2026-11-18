package racehorse.backend;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;

/*
 * Test "khói" (smoke test) do Spring Initializr sinh ra: @SpringBootTest dựng TOÀN BỘ ứng dụng (mọi bean,
 * kết nối DB thật, chạy Flyway). Nếu khởi động không lỗi thì contextLoads() qua.
 * Vì cần SQL Server và file .env, test này KHÔNG nằm trong danh sách mặc định của maven-surefire-plugin
 * (pom.xml chỉ include BusinessRulesTest). Chạy riêng: .\mvnw test -Dtest=BackendApplicationTests
 */
@SpringBootTest
class BackendApplicationTests {

	@Test
	void contextLoads() {
	}

}
