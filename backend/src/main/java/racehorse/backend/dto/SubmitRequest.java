package racehorse.backend.dto;

import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.PastOrPresent;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * Những gì chủ ngựa khai khi xin đăng ký.
 * V50: thêm khoảng huấn luyện mong muốn (từ ngày, đến ngày), thay cho chỉ số tuần.
 *
 * Hồ sơ PEDIGREE (chỉ làm cha mẹ / tổ tiên) vẫn chỉ cần tên. Ngựa vào CLB
 * (ACADEMY) giờ phải khai thêm những thứ học viện cần để DUYỆT có cơ sở
 * (góp ý review): cân nặng, chiều cao, thời gian huấn luyện, thời hạn gửi.
 * Ảnh đi bằng một request riêng (multipart), xem HorseRequestController.
 * HorseRequestService.submit kiểm những ô bắt buộc theo loại hồ sơ, vì
 * annotation ở đây không biết loại hồ sơ.
 *
 * KHÔNG có registrationCode và stallNo: mã đăng ký do học viện cấp, số chuồng
 * do học viện xếp, lúc nhận ngựa.
 */
// Body của POST /api/horse-requests (chỉ HORSE_OWNER). ownerId lấy từ token.
public record SubmitRequest(
        @NotBlank(message = "Horse name is required")
        @Size(max = 100, message = "Horse name must be at most 100 characters")
        String horseName,

        @Size(max = 50, message = "Breed must be at most 50 characters")
        String breed,

        @Pattern(regexp = "COLT|FILLY|STALLION|MARE|GELDING",
                 message = "Sex must be COLT, FILLY, STALLION, MARE or GELDING")
        String horseGender,

        @PastOrPresent(message = "Date of birth cannot be in the future")
        LocalDate dateOfBirth,

        @Size(max = 50, message = "Colour must be at most 50 characters")
        String color,

        @Size(max = 500, message = "Note must be at most 500 characters")
        String note,

        @Pattern(regexp = "ACADEMY|PEDIGREE", message = "Record type must be ACADEMY or PEDIGREE")
        String recordType,

        Integer sireId,
        Integer damId,

        // ---- V48: what the club needs to see before approving a club horse ----
        @DecimalMin(value = "30", message = "Weight must be at least 30 kg")
        @DecimalMax(value = "1000", message = "Weight must be at most 1000 kg")
        BigDecimal weight,

        @DecimalMin(value = "60", message = "Height must be at least 60 cm")
        @DecimalMax(value = "200", message = "Height must be at most 200 cm")
        BigDecimal heightCm,

        @Pattern(regexp = "TURF|DIRT", message = "Surface must be TURF or DIRT")
        String surface,

        @Pattern(regexp = "SPRINT|MEDIUM|LONG", message = "Distance must be SPRINT, MEDIUM or LONG")
        String distancePref,

        // MIDDLE / BACK are the V48 names, still accepted and stored as STALKER / CLOSER (V54).
        @Pattern(regexp = "FRONT|STALKER|CLOSER|MIDDLE|BACK",
                 message = "Running style must be FRONT, STALKER or CLOSER")
        String runningStyle,

        LocalDate preferredStart,
        Integer trainingWeeks,          // computed by the server from the two dates below (V50)
        Integer contractMonths,
        Integer preferredTrainerId,

        // ---- V50: the training window the owner asks for ----
        LocalDate trainingStart,
        LocalDate trainingEnd,

        // ---- V54: contract clause 8, insurance and the second person to call ----
        @Size(max = 100, message = "Insurer must be at most 100 characters")
        String insurerName,

        @Pattern(regexp = "^$|^[0-9+()\\s.-]{8,20}$", message = "Enter a valid phone number")
        String insurerPhone,

        @Size(max = 100, message = "Name must be at most 100 characters")
        String emergencyName,

        @Pattern(regexp = "^$|^[0-9+()\\s.-]{8,20}$", message = "Enter a valid phone number")
        String emergencyPhone
) {
    /** The nine fields a pedigree-only record uses (and that older callers pass). */
    public SubmitRequest(String horseName, String breed, String horseGender, LocalDate dateOfBirth,
                         String color, String note, String recordType, Integer sireId, Integer damId) {
        this(horseName, breed, horseGender, dateOfBirth, color, note, recordType, sireId, damId,
                null, null, null, null, null, null, null, null, null, null, null,
                null, null, null, null);
    }
}
