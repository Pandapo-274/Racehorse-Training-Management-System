package racehorse.backend.service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

/**
 * Chuẩn theo giống và tháng tuổi (góp ý hội đồng: "đến bao nhiêu tuổi thì chỉ số bao nhiêu"
 * và "vận tốc theo giống"). Giáo án căn theo bảng này.
 *
 * Nguồn của từng con số, để trả lời khi bị hỏi:
 *  - PUBLISHED: cân nặng và chiều cao Thoroughbred 0-18 tháng, Brown-Douglas & Pagan (2006),
 *    Kentucky Equine Research, Bảng 2 và 3.
 *  - CLUB_REFERENCE: phần còn lại. Cỡ trưởng thành của từng giống và tỉ lệ so với trưởng thành
 *    (6 tháng 46% / 84%, 12 tháng 65% / 94%, 24 tháng 90% / 95%) lấy từ tài liệu khuyến nông
 *    thứ cấp; giữa các mốc là nội suy tuyến tính. Nhóm cần đối chiếu lại trước khi xem là chuẩn.
 *  - Trần tốc độ là LUẬT CỦA CLB, không phải số liệu khoa học: trần của giống bằng tốc độ cao
 *    nhất trong 9 giáo án mẫu (V50), nhân hệ số theo tuổi ở speedFactor.
 *
 * Từ 60 tháng ngựa coi như đã ngừng lớn, nên không so cân nặng / chiều cao nữa: khi đó dùng
 * thang thể trạng BCS (HORSE.bcs). Giống ngoài danh sách không có tham chiếu, không đoán.
 */
public final class HorseStandards {

    /** Dưới tháng tuổi này không xếp giáo án. Luật của CLB, đổi ở đây là đổi cho cả hệ thống. */
    public static final int MIN_TRAINING_MONTHS = 18;
    /** Từ tháng này không còn so sánh tăng trưởng. */
    public static final int GROWTH_UNTIL_MONTHS = 60;
    /** Giống chưa có tham chiếu: dùng trần thấp nhất trong ba giống cho an toàn. */
    public static final double UNKNOWN_BREED_LIMIT_KMH = 58.0;

    public static final String PUBLISHED = "PUBLISHED";
    public static final String CLUB_REFERENCE = "CLUB_REFERENCE";

    private static final int RACING_UNTIL_MONTHS = (HorsePolicy.RACING_MAX_AGE + 1) * 12;
    private static final int LAST_PUBLISHED_MONTH = 18;

    private static final int[] TB_MONTHS = {0, 1, 6, 12, 18, 24, 60};
    private static final double[] TB_WEIGHT_KG = {66.9, 98.6, 247.1, 350.7, 444.9, 450.0, 500.0};
    // Chiều cao lúc 7 ngày tuổi (106.1) được dùng cho mốc 0 tháng.
    private static final double[] TB_HEIGHT_CM = {106.1, 112.0, 135.0, 147.1, 153.8, 154.9, 163.0};

    private static final int[] SHARE_MONTHS = {6, 12, 24, 60};
    private static final double[] SHARE_WEIGHT = {0.46, 0.65, 0.90, 1.00};
    private static final double[] SHARE_HEIGHT = {0.84, 0.94, 0.95, 1.00};

    private static final int[] TABLE_MONTHS = {6, 12, 18, 24, 36, 48, 60, 132};

    public record Breed(String name, double matureWeightKg, double matureHeightCm, double workLimitKmh) {}

    private static final List<Breed> BREEDS = List.of(
            new Breed("Thoroughbred", 500.0, 163.0, 66.0),
            new Breed("Arabian", 409.0, 150.0, 58.0),
            new Breed("Quarter Horse", 488.0, 152.0, 70.0));

    /** Cân nặng và chiều cao kỳ vọng ở một tháng tuổi. */
    public record Expected(double weightKg, double heightCm, String basis) {}

    /** Chuẩn áp cho MỘT con ngựa, hiện trên form tạo giáo án. */
    public record Reference(String breed, boolean breedKnown, Integer ageMonths, Double expectedWeightKg,
                            Double expectedHeightCm, String basis, BigDecimal speedLimitKmh,
                            boolean canTrain, String note) {}

    public record AgeRow(int months, Double weightKg, Double heightCm, String basis, BigDecimal speedLimitKmh) {}

    public record BreedTable(String breed, double matureWeightKg, double matureHeightCm, double workLimitKmh,
                             List<AgeRow> rows) {}

    public record Catalog(int minTrainingMonths, int growthUntilMonths, double unknownBreedLimitKmh,
                          List<BreedTable> breeds) {}

    private HorseStandards() {}

    public static Breed find(String breed) {
        if (breed == null) return null;
        String name = breed.trim();
        return BREEDS.stream().filter(b -> b.name().equalsIgnoreCase(name)).findFirst().orElse(null);
    }

    /** null khi không có tham chiếu: giống khác, đã trưởng thành, hoặc quá nhỏ so với dữ liệu. */
    public static Expected expected(String breed, Integer months) {
        Breed b = find(breed);
        if (b == null || months == null || months < 0 || months > GROWTH_UNTIL_MONTHS) return null;
        if ("Thoroughbred".equals(b.name())) {
            return new Expected(interpolate(TB_MONTHS, TB_WEIGHT_KG, months),
                    interpolate(TB_MONTHS, TB_HEIGHT_CM, months),
                    months <= LAST_PUBLISHED_MONTH ? PUBLISHED : CLUB_REFERENCE);
        }
        if (months < SHARE_MONTHS[0]) return null;
        return new Expected(b.matureWeightKg() * interpolate(SHARE_MONTHS, SHARE_WEIGHT, months),
                b.matureHeightCm() * interpolate(SHARE_MONTHS, SHARE_HEIGHT, months),
                CLUB_REFERENCE);
    }

    /** Hệ số trần tốc độ theo tuổi. Luật của CLB: ngựa non và ngựa quá tuổi đua chạy nhẹ hơn. */
    static double speedFactor(int months) {
        if (months < MIN_TRAINING_MONTHS) return 0;
        if (months < 24) return 0.80;
        if (months < 36) return 0.90;
        if (months < RACING_UNTIL_MONTHS) return 1.00;
        return 0.90;
    }

    /** Trần tốc độ (km/h) của giống ở tháng tuổi này. 0 = chưa đủ tuổi tập. Không rõ tuổi = trần người lớn. */
    public static BigDecimal speedLimit(String breed, Integer months) {
        Breed b = find(breed);
        double base = b == null ? UNKNOWN_BREED_LIMIT_KMH : b.workLimitKmh();
        double factor = months == null ? 1.0 : speedFactor(months);
        return BigDecimal.valueOf(base * factor).setScale(1, RoundingMode.HALF_UP);
    }

    /** Lý do chưa được xếp giáo án, hoặc null. */
    public static String trainingAgeProblem(String horseName, LocalDate dateOfBirth, LocalDate today) {
        Integer months = HorsePolicy.ageInMonths(dateOfBirth, today);
        if (months == null || months >= MIN_TRAINING_MONTHS) return null;
        return horseName + " is " + months + " months old. Training plans start at "
                + MIN_TRAINING_MONTHS + " months";
    }

    /** Tốc độ vượt trần của giống và tuổi thì trả về lý do. Ngựa chưa đủ tuổi do trainingAgeProblem chặn. */
    public static String speedProblem(String breed, LocalDate dateOfBirth, BigDecimal speed, LocalDate today) {
        if (speed == null) return null;
        Integer months = HorsePolicy.ageInMonths(dateOfBirth, today);
        BigDecimal limit = speedLimit(breed, months);
        if (limit.signum() == 0 || speed.compareTo(limit) <= 0) return null;
        Breed b = find(breed);
        return "Above the " + limit.toPlainString() + " km/h limit for "
                + (b == null ? "a breed without a reference" : b.name())
                + (months == null ? "" : " at " + months + " months");
    }

    public static Reference reference(String breed, LocalDate dateOfBirth, LocalDate today) {
        Integer months = HorsePolicy.ageInMonths(dateOfBirth, today);
        Breed b = find(breed);
        Expected e = expected(breed, months);
        BigDecimal limit = speedLimit(breed, months);
        boolean canTrain = months == null || months >= MIN_TRAINING_MONTHS;
        String note;
        if (!canTrain) {
            note = "Too young for a training plan: plans start at " + MIN_TRAINING_MONTHS + " months.";
        } else if (b == null) {
            note = "No reference for this breed. The lowest club speed limit applies; choose a template by hand.";
        } else if (e == null) {
            note = "Fully grown: use the body condition score instead of weight and height for age.";
        } else {
            note = null;
        }
        return new Reference(b == null ? breed : b.name(), b != null, months,
                e == null ? null : round(e.weightKg()), e == null ? null : round(e.heightCm()),
                e == null ? null : e.basis(), limit, canTrain, note);
    }

    public static Catalog catalog() {
        List<BreedTable> tables = new ArrayList<>();
        for (Breed b : BREEDS) {
            List<AgeRow> rows = new ArrayList<>();
            for (int m : TABLE_MONTHS) {
                Expected e = expected(b.name(), Math.min(m, GROWTH_UNTIL_MONTHS));
                rows.add(new AgeRow(m, e == null ? null : round(e.weightKg()),
                        e == null ? null : round(e.heightCm()),
                        m > GROWTH_UNTIL_MONTHS || e == null ? CLUB_REFERENCE : e.basis(),
                        speedLimit(b.name(), m)));
            }
            tables.add(new BreedTable(b.name(), b.matureWeightKg(), b.matureHeightCm(), b.workLimitKmh(), rows));
        }
        return new Catalog(MIN_TRAINING_MONTHS, GROWTH_UNTIL_MONTHS, UNKNOWN_BREED_LIMIT_KMH, tables);
    }

    private static double interpolate(int[] months, double[] values, int month) {
        if (month <= months[0]) return values[0];
        for (int i = 1; i < months.length; i++) {
            if (month <= months[i]) {
                double t = (month - months[i - 1]) / (double) (months[i] - months[i - 1]);
                return values[i - 1] + t * (values[i] - values[i - 1]);
            }
        }
        return values[values.length - 1];
    }

    private static double round(double v) {
        return Math.round(v * 10) / 10.0;
    }
}
