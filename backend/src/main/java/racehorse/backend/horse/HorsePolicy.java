package racehorse.backend.horse;

import java.time.LocalDate;
import java.time.Period;

/**
 * Luật về loại hồ sơ và độ tuổi, gom về MỘT chỗ.
 *
 * Hai loại hồ sơ:
 *  - ACADEMY:  ngựa sống và tập tại học viện (có chuồng, có giáo án, có khám bệnh).
 *  - PEDIGREE: chỉ là hồ sơ tham chiếu để làm cha mẹ / tổ tiên của ngựa khác.
 *              Không chuồng, không cân nặng, không vào giáo án hay thi đấu.
 *
 * Độ tuổi chỉ dùng để PHÂN LOẠI và CHẶN ngựa quá già vào học viện. Muốn đổi
 * mốc thì sửa ba hằng số dưới đây, và hằng số tương ứng AGE_RULES trong
 * frontend/src/features/horse/horseApi.js.
 *
 * Mốc dưới đây là mốc thực tế của ngựa đua (đua từ ~2 tuổi, hầu hết giải nghệ
 * trước ~10 tuổi), KHÔNG phải 10 đến 25 tuổi: khoảng đó là tuổi sinh sản chứ
 * không phải tuổi đua.
 */
public final class HorsePolicy {

    public static final String ACADEMY = "ACADEMY";
    public static final String PEDIGREE = "PEDIGREE";

    /** Dưới tuổi này là ngựa con (Foal). */
    public static final int FOAL_BELOW = 2;
    /** Từ FOAL_BELOW đến tuổi này là tuổi đua (Racing). Trên đó là Retired. */
    public static final int RACING_MAX_AGE = 10;
    /** Trên tuổi này không nhận vào học viện, chỉ nhận làm hồ sơ PEDIGREE. */
    public static final int ACADEMY_MAX_AGE = 25;

    private HorsePolicy() {}

    public static int ageOf(LocalDate dateOfBirth) {
        return Period.between(dateOfBirth, LocalDate.now()).getYears();
    }

    public static String normalizeType(String recordType) {
        return PEDIGREE.equals(recordType) ? PEDIGREE : ACADEMY;
    }

    /**
     * Kiểm tra điều kiện tuổi cho ngựa loại ACADEMY.
     *
     * @return thông báo lỗi để gắn vào ô ngày sinh, hoặc null nếu hợp lệ.
     *         Loại PEDIGREE luôn hợp lệ (ngựa nào cũng có thể là tổ tiên).
     */
    public static String ageProblem(String recordType, LocalDate dateOfBirth) {
        if (!ACADEMY.equals(normalizeType(recordType))) return null;
        if (dateOfBirth == null) {
            return "Date of birth is required for an academy horse";
        }
        int age = ageOf(dateOfBirth);
        if (age > ACADEMY_MAX_AGE) {
            return "This horse is " + age + " years old, above the academy limit of "
                    + ACADEMY_MAX_AGE + ". Register it as a pedigree record instead.";
        }
        return null;
    }
}