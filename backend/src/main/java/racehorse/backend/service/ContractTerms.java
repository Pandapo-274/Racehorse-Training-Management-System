package racehorse.backend.service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.text.NumberFormat;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.HexFormat;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Giá và nội dung hợp đồng gửi ngựa (V49).
 *
 * Giá: phí tháng × số tháng × (1 − chiết khấu theo gói). Phí tháng và bảng chiết
 * khấu nằm trong cấu hình (application.properties / .env), không viết cứng, vì
 * khi demo với payOS thật cần đặt phí rất nhỏ: tiền chuyển là tiền thật.
 *
 * Nội dung: dựng một lần lúc gửi, lưu nguyên văn (terms_snapshot) kèm SHA-256.
 * Đổi giá hay đổi điều khoản sau này không làm thay đổi hợp đồng đã gửi.
 * Các điều khoản lấy từ những mục mà mẫu hợp đồng huấn luyện / gửi nuôi ngựa
 * thường có: rủi ro chấn thương, quyền xử lý thú y khẩn cấp, an tử, bảo hiểm,
 * người liên hệ khẩn cấp, chấm dứt trước hạn, gia hạn, tranh chấp.
 * Chữ trên UI là tiếng Anh, như mọi chữ khác của hệ thống.
 */
@Component
public class ContractTerms {

    private final long monthlyFee;
    private final TreeMap<Integer, BigDecimal> discounts;
    private final int offerDays;
    private final long vetEmergencyCap;
    private final String clubName;

    public ContractTerms(@Value("${app.contract.monthly-fee:15000000}") long monthlyFee,
                         @Value("${app.contract.discounts:3:5,6:8,12:12}") String discounts,
                         @Value("${app.contract.offer-days:7}") int offerDays,
                         @Value("${app.contract.vet-emergency-cap:5000000}") long vetEmergencyCap,
                         @Value("${app.contract.club-name:Tracen Club}") String clubName) {
        this.monthlyFee = monthlyFee;
        this.discounts = parseDiscounts(discounts);
        this.offerDays = offerDays;
        this.vetEmergencyCap = vetEmergencyCap;
        this.clubName = clubName;
    }

    /** "3:5,6:8,12:12" → {3=5, 6=8, 12=12}: from 3 months 5% off, from 6 months 8%... */
    static TreeMap<Integer, BigDecimal> parseDiscounts(String spec) {
        TreeMap<Integer, BigDecimal> map = new TreeMap<>();
        if (spec == null || spec.isBlank()) return map;
        for (String part : spec.split(",")) {
            String[] kv = part.trim().split(":");
            if (kv.length != 2) continue;
            try {
                map.put(Integer.parseInt(kv[0].trim()), new BigDecimal(kv[1].trim()));
            } catch (NumberFormatException ignored) {
                // a typo in the config must not stop the server; that tier is simply skipped
            }
        }
        return map;
    }

    public record Price(int months, long monthlyFee, BigDecimal discountPct, long total) {}

    public Price priceFor(int months) {
        Map.Entry<Integer, BigDecimal> tier = discounts.floorEntry(months);
        BigDecimal pct = tier == null ? BigDecimal.ZERO : tier.getValue();
        BigDecimal gross = BigDecimal.valueOf(monthlyFee).multiply(BigDecimal.valueOf(months));
        long total = gross.multiply(BigDecimal.valueOf(100).subtract(pct))
                .divide(BigDecimal.valueOf(100), 0, RoundingMode.HALF_UP)
                .longValue();
        return new Price(months, monthlyFee, pct, total);
    }

    public int offerDays() {
        return offerDays;
    }

    public long monthlyFee() {
        return monthlyFee;
    }

    public Map<Integer, BigDecimal> discountTiers() {
        return java.util.Collections.unmodifiableMap(discounts);
    }

    public record Parties(String ownerName, String horseName, String breed, String horseGender,
                          LocalDate dateOfBirth, String color, String sireName, String damName,
                          Integer trainingWeeks, LocalDate plannedStart, String managerName,
                          boolean renewal, LocalDate renewalStart) {}

    /** The contract text, frozen at sending. Plain text, one clause per paragraph. */
    public String text(Parties p, Price price, LocalDate respondBy) {
        DateTimeFormatter d = DateTimeFormatter.ofPattern("d MMM yyyy", Locale.ENGLISH);
        StringBuilder t = new StringBuilder();
        t.append(p.renewal() ? "BOARDING AND TRAINING CONTRACT - RENEWAL" : "BOARDING AND TRAINING CONTRACT").append("\n\n");

        t.append("1. Parties\n");
        t.append("The club: ").append(clubName).append(", represented by ").append(p.managerName()).append(".\n");
        t.append("The owner: ").append(p.ownerName()).append(".\n\n");

        t.append("2. The horse\n");
        t.append(p.horseName());
        String about = join(p.breed(), gender(p.horseGender()), p.color(),
                p.dateOfBirth() == null ? null : "born " + d.format(p.dateOfBirth()));
        if (!about.isEmpty()) t.append(" (").append(about).append(")");
        t.append(". Sire: ").append(p.sireName() == null ? "unknown" : p.sireName())
         .append(". Dam: ").append(p.damName() == null ? "unknown" : p.damName()).append(".\n");
        t.append("The club assigns the registration code, the stall and the Head Trainer when the horse arrives.\n\n");

        t.append("3. Term\n");
        t.append(price.months()).append(price.months() == 1 ? " month" : " months");
        if (p.renewal()) {
            t.append(", following on from the current stay");
            if (p.renewalStart() != null) t.append(" (from ").append(d.format(p.renewalStart())).append(")");
            t.append(".");
        } else {
            t.append(", starting on the day the club receives the horse");
            if (p.plannedStart() != null) t.append(" (planned: ").append(d.format(p.plannedStart())).append(")");
            t.append(".");
        }
        if (p.trainingWeeks() != null) {
            t.append(" Training requested: ").append(p.trainingWeeks()).append(" weeks within the term.");
        }
        t.append("\n\n");

        t.append("4. Price and payment\n");
        t.append("Monthly fee ").append(vnd(price.monthlyFee())).append(" x ").append(price.months());
        if (price.discountPct().signum() > 0) {
            t.append(", less ").append(price.discountPct().stripTrailingZeros().toPlainString()).append("%");
        }
        t.append(" = ").append(vnd(price.total())).append(".\n");
        t.append("Sign and pay online through payOS by ").append(d.format(respondBy))
         .append(". After that date this offer lapses and nothing is owed.\n");
        t.append("The fee covers the stall, feed, daily care and training under the agreed plan. ")
         .append("Veterinary treatment beyond routine checks, medicines, race entry fees and transport ")
         .append("are charged separately.\n\n");

        t.append("5. Risk of injury, illness or death\n");
        t.append("Training a racehorse carries risk the club cannot remove. The club is not liable for injury, ")
         .append("illness or death of the horse except where caused by the club's gross negligence.\n\n");

        t.append("6. Emergency veterinary care\n");
        t.append("In an emergency the club may call a veterinarian and authorise treatment up to ")
         .append(vnd(vetEmergencyCap)).append(" without waiting for the owner, and informs the owner at once. ")
         .append("Treatment above that amount needs the owner's consent.\n\n");

        t.append("7. Euthanasia\n");
        t.append("The horse is not put down without the owner's consent, unless a veterinarian decides it ")
         .append("must be done at once to end suffering.\n\n");

        t.append("8. Insurance and emergency contact\n");
        t.append("The owner insures the horse (mortality and medical) and gives the club the insurer's ")
         .append("emergency number, and names a second person who may decide for the horse when the ")
         .append("owner cannot be reached.\n\n");

        t.append("9. Ending early\n");
        t.append("The owner may end the stay with 15 days' notice through a withdrawal request. ")
         .append("Months already started are not refunded; months not started are.\n\n");

        t.append("10. End of term and renewal\n");
        t.append("The club reminds the owner 14 and 3 days before the end. A renewal is a new contract ")
         .append("following on from this one; nothing renews or is charged automatically. If the term ends ")
         .append("without renewal, the horse takes no new training plan until it is renewed or withdrawn.\n\n");

        t.append("11. Disputes\n");
        t.append("The parties first try to settle a dispute between themselves; failing that, under the laws of Viet Nam.\n\n");

        t.append("Signed online by typing the owner's full name. The club records the name typed, the time, ")
         .append("the IP address and the SHA-256 fingerprint of this text.\n");
        return t.toString();
    }

    public static String sha256(String text) {
        try {
            byte[] hash = MessageDigest.getInstance("SHA-256").digest(text.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(hash);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is not available", e);
        }
    }

    public static String vnd(long amount) {
        return NumberFormat.getIntegerInstance(Locale.US).format(amount) + " VND";
    }

    private static String gender(String g) {
        if (g == null) return null;
        return g.charAt(0) + g.substring(1).toLowerCase(Locale.ROOT);
    }

    private static String join(String... parts) {
        StringBuilder b = new StringBuilder();
        for (String s : parts) {
            if (s == null || s.isBlank()) continue;
            if (b.length() > 0) b.append(", ");
            b.append(s);
        }
        return b.toString();
    }
}
