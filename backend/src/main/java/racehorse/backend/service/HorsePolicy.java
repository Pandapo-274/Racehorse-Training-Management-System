package racehorse.backend.service;

import java.time.LocalDate;
import java.time.Period;

import racehorse.backend.dto.HorseResponse;

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
// Lớp tiện ích tĩnh (final + constructor private): không tạo đối tượng, chỉ gọi HorsePolicy.xxx(...).
// Frontend chép lại các mốc này trong horseApi.js (AGE_RULES): đổi ở đây phải đổi cả ở đó.
public final class HorsePolicy {

    public static final String ACADEMY = "ACADEMY";
    public static final String PEDIGREE = "PEDIGREE";

    /**
     * Tình trạng sức khoẻ cho phép tập. Một chỗ duy nhất: giáo án
     * (TrainingPlanService) và buổi tập (TrainingSessionService) cùng dùng, để
     * không bao giờ lệch nhau.
     */
    public static final java.util.Set<String> TRAINABLE_STATUSES = java.util.Set.of("ELIGIBLE", "MONITOR");

    /** Dưới tuổi này là ngựa con (Foal). */
    public static final int FOAL_BELOW = 2;
    /** Từ FOAL_BELOW đến tuổi này là tuổi đua (Racing). Trên đó là Retired. */
    public static final int RACING_MAX_AGE = 10;
    /** Trên tuổi này không nhận vào học viện, chỉ nhận làm hồ sơ PEDIGREE. */
    public static final int CLUB_MAX_AGE = 25;

    private HorsePolicy() {}

    // Period.between(...).getYears(): số năm tròn giữa ngày sinh và hôm nay (tuổi thực).
    public static int ageOf(LocalDate dateOfBirth) {
        return Period.between(dateOfBirth, LocalDate.now()).getYears();
    }

    // Giá trị lạ hoặc null đều coi là ACADEMY (mặc định an toàn).
    public static String normalizeType(String recordType) {
        return PEDIGREE.equals(recordType) ? PEDIGREE : ACADEMY;
    }

    /**
     * Kiểm tra điều kiện tuổi cho ngựa loại ACADEMY.
     *
     * @return thông báo lỗi để gắn vào ô ngày sinh, hoặc null nếu hợp lệ.
     *         Loại PEDIGREE luôn hợp lệ (ngựa nào cũng có thể là tổ tiên).
     */
    // (Ghi chú: khối Javadoc ngay phía trên mô tả ageProblem ở cuối lớp, bị đặt lệch chỗ.)
    /**
     * Hồ sơ PEDIGREE có được vào giáo án / buổi tập không.
     *
     * Luật này đã được phát biểu trong phần chú thích đầu lớp ngay từ V6
     * ("không vào giáo án hay thi đấu"), nhưng package training chưa bao giờ
     * hỏi đến, nên trên thực tế một hồ sơ tổ tiên 25 tuổi vẫn xếp được giáo án
     * và vẫn giao được buổi tập. Đặt phép kiểm ở đây thay vì rải
     * PEDIGREE.equals(...) khắp nơi: lớp này là nơi luật về loại hồ sơ sống,
     * và đổi luật thì chỉ sửa một chỗ.
     *
     * @return thông báo lỗi, hoặc null nếu con ngựa được phép tập.
     */
    public static String trainingProblem(String recordType) {
        if (PEDIGREE.equals(normalizeType(recordType))) {
            return "This is a pedigree-only record, not a club horse. "
                    + "It cannot be put on a training plan or a training session.";
        }
        return null;
    }

    /**
     * Ngựa đã rút khỏi học viện (V15) thì không xếp giáo án, không giao buổi
     * tập, không bắt đầu buổi tập. Gọi cùng chỗ với trainingProblem(recordType).
     */
    public static String withdrawnProblem(HorseResponse horse) {
        if (horse != null && horse.isWithdrawn()) {
            return horse.horseName() + " has been withdrawn from the club and cannot train.";
        }
        return null;
    }

    /** V49: the stay (contract term) ended and was not renewed. */
    public static String stayEndedProblem(HorseResponse horse) {
        return horse.horseName() + "'s stay at the club has ended. "
                + "It takes no new training plan until the owner renews the stay.";
    }

    public static String ageProblem(String recordType, LocalDate dateOfBirth) {
        if (!ACADEMY.equals(normalizeType(recordType))) return null;
        if (dateOfBirth == null) {
            return "Date of birth is required for a club horse";
        }
        int age = ageOf(dateOfBirth);
        if (age > CLUB_MAX_AGE) {
            return "This horse is " + age + " years old, above the club limit of "
                    + CLUB_MAX_AGE + ". Register it as a pedigree record instead.";
        }
        return null;
    }

    /* ------------------------------------------------------------------
     * Registration code (review feedback: "how do you know the code is unique?")
     *
     * Uniqueness itself is the database's job (registration_code UNIQUE, V1):
     * only a constraint holds when two managers press Approve at the same time.
     * What this adds is a FORMAT, so "tm 01", "TM-0001" and "TM0001" can no
     * longer all be stored as different horses. The shape matches the seed
     * data: TM-0332 for club horses, TM-A001 for the older pedigree records.
     * ------------------------------------------------------------------ */
    public static final java.util.regex.Pattern CODE_PATTERN =
            java.util.regex.Pattern.compile("^[A-Z]{2,5}-[A-Z]?\\d{3,6}$");

    public static String codeProblem(String code) {
        if (code == null || code.isBlank()) return "Enter the code the club assigns";
        if (!CODE_PATTERN.matcher(code).matches()) {
            return "Use the club format, for example TM-0751 (letters, a dash, then digits)";
        }
        return null;
    }

    /* ------------------------------------------------------------------
     * How long the horse stays and when it trains (review feedback: "no deadline
     * for the horse's time at the club", then "the owner gives no training start
     * and end date"). The stay is the contract term, counted from the day the horse
     * comes in; the training window the owner asks for must fit inside it and last
     * at least 3 weeks. Same rules as CK_HREQ_training_window (V50) and as
     * stayProblems in horseRequestApi.js.
     * ------------------------------------------------------------------ */
    public static final int MIN_TRAINING_WEEKS = 3;
    public static final int MIN_TRAINING_DAYS = MIN_TRAINING_WEEKS * 7;
    public static final int MIN_CONTRACT_MONTHS = 1;
    public static final int MAX_CONTRACT_MONTHS = 24;

    /** @return field -> message, empty when the stay and the training window are fine. */
    public static java.util.Map<String, String> stayProblems(LocalDate arrival, LocalDate trainingStart,
                                                            LocalDate trainingEnd, Integer contractMonths,
                                                            LocalDate today) {
        java.util.Map<String, String> errors = new java.util.LinkedHashMap<>();
        if (contractMonths == null) {
            errors.put("contractMonths", "Choose how many months the horse stays");
        } else if (contractMonths < MIN_CONTRACT_MONTHS || contractMonths > MAX_CONTRACT_MONTHS) {
            errors.put("contractMonths", "The stay is between " + MIN_CONTRACT_MONTHS + " and "
                    + MAX_CONTRACT_MONTHS + " months");
        }
        if (arrival == null) {
            errors.put("preferredStart", "Choose the day you bring the horse in");
        } else if (arrival.isBefore(today)) {
            errors.put("preferredStart", "The arrival date cannot be in the past");
        }
        if (trainingStart == null) {
            errors.put("trainingStart", "Choose the first day of training");
        } else if (arrival != null && trainingStart.isBefore(arrival)) {
            errors.put("trainingStart", "Training cannot start before the horse arrives");
        }
        if (trainingEnd == null) {
            errors.put("trainingEnd", "Choose the last day of training");
        } else if (trainingStart != null && trainingEnd.isBefore(trainingStart.plusDays(MIN_TRAINING_DAYS))) {
            errors.put("trainingEnd", "Training is at least " + MIN_TRAINING_WEEKS + " weeks: until "
                    + trainingStart.plusDays(MIN_TRAINING_DAYS) + " or later");
        } else if (arrival != null && contractMonths != null && contractMonths >= MIN_CONTRACT_MONTHS
                && trainingEnd.isAfter(arrival.plusMonths(contractMonths))) {
            errors.put("trainingEnd", "Training must end by " + arrival.plusMonths(contractMonths)
                    + ", the end of a " + contractMonths + "-month stay");
        }
        return errors;
    }

    /** Whole weeks in the training window (21 days = 3 weeks), null when a date is missing. */
    public static Integer trainingWeeks(LocalDate trainingStart, LocalDate trainingEnd) {
        if (trainingStart == null || trainingEnd == null) return null;
        return (int) (java.time.temporal.ChronoUnit.DAYS.between(trainingStart, trainingEnd) / 7);
    }

    /* ------------------------------------------------------------------
     * Growth for age.
     *
     * Weight and height against age say whether a young horse is growing on
     * track. They do NOT say whether it is thin or fat: that is the Henneke
     * body condition score (1-9), scored by eye and hand when the horse
     * arrives (HORSE.bcs). So this returns BELOW / WITHIN / ABOVE, never
     * "malnourished" or "obese".
     *
     * Reference: world-average Thoroughbred body weight and wither height at
     * 0, 1, 6, 12 and 18 months, Brown-Douglas & Pagan (2006), "Body weight,
     * wither height and growth rates in Thoroughbreds raised in America,
     * England, Australia, New Zealand and India", Kentucky Equine Research,
     * Tables 2 and 3. The paper gives means only, no spread, so "within" is a
     * +/-10% band around the mean, interpolated between the published months.
     * Past 18 months (or for another breed) there is no reference and the
     * answer is null: the UI says "No reference" instead of guessing.
     * ------------------------------------------------------------------ */
    public static final String GROWTH_BELOW = "BELOW";
    public static final String GROWTH_WITHIN = "WITHIN";
    public static final String GROWTH_ABOVE = "ABOVE";
    private static final double GROWTH_BAND = 0.10;
    private static final int[] REF_MONTHS = {0, 1, 6, 12, 18};
    private static final double[] REF_WEIGHT_KG = {66.9, 98.6, 247.1, 350.7, 444.9};
    // Height at 7 days (106.1) stands in for month 0.
    private static final double[] REF_HEIGHT_CM = {106.1, 112.0, 135.0, 147.1, 153.8};

    public static Integer ageInMonths(LocalDate dateOfBirth, LocalDate today) {
        if (dateOfBirth == null || today == null || dateOfBirth.isAfter(today)) return null;
        return (int) Period.between(dateOfBirth, today).toTotalMonths();
    }

    /** Thoroughbred only, 0..18 months; null when there is nothing to compare with. */
    public static String growthFor(String breed, LocalDate dateOfBirth,
                                   java.math.BigDecimal weight, java.math.BigDecimal heightCm,
                                   LocalDate today) {
        if (breed == null || !breed.trim().equalsIgnoreCase("Thoroughbred")) return null;
        Integer months = ageInMonths(dateOfBirth, today);
        if (months == null || months > REF_MONTHS[REF_MONTHS.length - 1]) return null;
        String byWeight = weight == null ? null : band(weight.doubleValue(), interpolate(REF_WEIGHT_KG, months));
        String byHeight = heightCm == null ? null : band(heightCm.doubleValue(), interpolate(REF_HEIGHT_CM, months));
        if (byWeight == null) return byHeight;
        if (byHeight == null || byWeight.equals(byHeight)) return byWeight;
        // The two disagree: a long, light foal or a short, heavy one. Report the one off the band.
        return GROWTH_WITHIN.equals(byWeight) ? byHeight : byWeight;
    }

    private static double interpolate(double[] values, int months) {
        for (int i = 1; i < REF_MONTHS.length; i++) {
            if (months <= REF_MONTHS[i]) {
                int m0 = REF_MONTHS[i - 1], m1 = REF_MONTHS[i];
                double t = (months - m0) / (double) (m1 - m0);
                return values[i - 1] + t * (values[i] - values[i - 1]);
            }
        }
        return values[values.length - 1];
    }

    private static String band(double actual, double mean) {
        if (actual < mean * (1 - GROWTH_BAND)) return GROWTH_BELOW;
        if (actual > mean * (1 + GROWTH_BAND)) return GROWTH_ABOVE;
        return GROWTH_WITHIN;
    }

    /** Henneke 1-3 thin, 4-6 ideal, 7-9 fat; null outside 1..9. */
    public static String bcsGroup(Integer bcs) {
        if (bcs == null || bcs < 1 || bcs > 9) return null;
        return bcs <= 3 ? "THIN" : bcs <= 6 ? "IDEAL" : "FAT";
    }
}
