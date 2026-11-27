package racehorse.backend.entity;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * Một yêu cầu đăng ký ngựa, như frontend nhìn thấy.
 *
 * ownerName và reviewerName ghép sẵn từ APP_USER để màn hình không phải gọi
 * thêm một vòng nữa chỉ để đổi id thành tên.
 *
 * V48/V49: thêm những gì khung "Review basis" của Manager cần (ảnh, số đo,
 * năng khiếu, thời hạn, HLV mong muốn và số chỗ còn của HLV đó, số ngựa chủ
 * đang gửi), cùng hợp đồng mới nhất của yêu cầu để cả hai bên biết đang ở bước
 * nào. growth là kết quả HorsePolicy.growthFor (BELOW / WITHIN / ABOVE / null).
 */
// Hậu tố "Row" trong dự án = một dòng đọc từ DB (thường là kết quả JOIN) do RowMapper
// trong repository dựng ra, dùng để trả thẳng về frontend.
public record HorseRequestRow(
        int requestId,
        int ownerId, String ownerName,
        String horseName, String breed, String horseGender, String recordType,
        LocalDate dateOfBirth, String color, String note,
        String status, LocalDateTime createdAt,
        Integer reviewedBy, String reviewerName, LocalDateTime reviewedAt, String reviewNote,
        Integer horseId,
        Integer sireId, String sireName,
        Integer damId, String damName,
        // V48
        String photoUrl,
        BigDecimal weight, BigDecimal heightCm,
        String surface, String distancePref, String runningStyle,
        LocalDate preferredStart, Integer trainingWeeks, Integer contractMonths,
        Integer preferredTrainerId, String preferredTrainerName,
        Integer preferredTrainerLoad, Integer preferredTrainerMax,
        int ownerActiveHorses,
        String growth,
        // V49: newest contract of this request, null before approval
        Integer contractId, String contractStatus,
        // V50: training window asked for
        LocalDate trainingStart, LocalDate trainingEnd,
        // V54: contract clause 8
        String insurerName, String insurerPhone, String emergencyName, String emergencyPhone,
        // V64: a horse that left and is coming back (its registration code stays)
        Integer returnOfHorseId, String returnCode
) {
    public boolean isReturn() {
        return returnOfHorseId != null;
    }
}
