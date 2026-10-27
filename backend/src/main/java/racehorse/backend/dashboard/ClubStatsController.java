package racehorse.backend.dashboard;

import java.time.LocalDate;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Ba con số giới thiệu câu lạc bộ trên trang chủ và trang đăng nhập / đăng ký.
 *
 * CÔNG KHAI (có trong WebConfig.PUBLIC_PATHS): hai trang đó mở trước khi đăng
 * nhập. Chỉ trả về ba số tổng, không tên, không id, nên không lộ dữ liệu của
 * ai.
 *
 *  - horsesInTraining: ngựa của học viện (không tính hồ sơ PEDIGREE) và chưa rút (V15).
 *  - activePlans:      giáo án đang ACTIVE.
 *  - racesThisSeason:  số giải đua (tên + ngày) có ngựa của câu lạc bộ đăng ký trong năm.
 *                      Database không lưu hạng giải nên không đếm riêng được G1.
 */
@RestController
@RequestMapping("/api/public/club-stats")
public class ClubStatsController {

    public record ClubStats(int horsesInTraining, int activePlans, int racesThisSeason, int season) {
    }

    private final JdbcTemplate jdbc;

    public ClubStatsController(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @GetMapping
    public ClubStats get() {
        int season = LocalDate.now().getYear();
        return jdbc.queryForObject("""
                SELECT
                    (SELECT COUNT(*) FROM HORSE
                     WHERE record_type = 'ACADEMY' AND activity_status = 'ACTIVE')       AS horses,
                    (SELECT COUNT(*) FROM TRAINING_PLAN WHERE status = 'ACTIVE')            AS plans,
                    (SELECT COUNT(*) FROM (SELECT DISTINCT race_name, race_date
                                           FROM RACE_REGISTRATION
                                           WHERE YEAR(race_date) = ?) r)                     AS races
                """,
                (rs, n) -> new ClubStats(rs.getInt("horses"), rs.getInt("plans"), rs.getInt("races"), season),
                season);
    }
}
