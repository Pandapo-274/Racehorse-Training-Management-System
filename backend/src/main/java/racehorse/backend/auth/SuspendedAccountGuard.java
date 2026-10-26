package racehorse.backend.auth;

import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

/**
 * Giữ cho việc khoá tài khoản BỀN qua lần khởi động lại backend.
 *
 * Khi Manager khoá một tài khoản, StaffService đá hết phiên bằng TokenBlacklist.revokeAllSessions. Sổ đen đó nằm
 * trong RAM, nên restart server là mất: người đã bị khoá nhưng còn giữ token cũ (tối đa 8 tiếng) lại vào được
 * tiếp, vì AuthInterceptor chỉ nhìn chữ ký token chứ không hỏi lại DB. Đăng nhập mới thì vẫn bị chặn, nhưng token
 * cũ thì không.
 *
 * Mỗi lần khởi động, đọc lại danh sách tài khoản SUSPENDED trong DB và đưa từng người vào sổ đen một lần nữa.
 * Token phát hành TRƯỚC thời điểm này của họ đều chết; sau khi được mở khoá và đăng nhập lại thì token mới sống bình thường.
 */
@Component
public class SuspendedAccountGuard implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(SuspendedAccountGuard.class);

    private final JdbcClient jdbc;
    private final TokenBlacklist blacklist;

    public SuspendedAccountGuard(JdbcClient jdbc, TokenBlacklist blacklist) {
        this.jdbc = jdbc;
        this.blacklist = blacklist;
    }

    @Override
    public void run(ApplicationArguments args) {
        List<Integer> suspended = jdbc.sql("SELECT user_id FROM APP_USER WHERE status = 'SUSPENDED'")
                .query(Integer.class)
                .list();
        for (Integer userId : suspended) {
            blacklist.revokeAllSessions(userId);
        }
        log.info("Re-applied session revocation for {} suspended account(s)", suspended.size());
    }
}
