package racehorse.backend.entity;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * Một hợp đồng gửi ngựa (HORSE_CONTRACT, V49) như frontend nhìn thấy, kèm những
 * gì của yêu cầu đăng ký mà màn ký / thanh toán / nhận ngựa cần (tên ngựa, ảnh,
 * số đo, HLV mong muốn) để không phải gọi thêm một vòng.
 *
 * daysLeft chỉ có nghĩa khi hợp đồng ACTIVE: số ngày tới end_date (âm = đã quá).
 * termsSnapshot là nguyên văn hợp đồng đã chốt lúc gửi.
 */
public record ContractRow(
        int contractId,
        int requestId,
        Integer horseId,
        int ownerId, String ownerName,
        Integer renewsContractId,
        String horseName, String recordType, String breed, String horseGender,
        LocalDate dateOfBirth, String color, String photoUrl,
        BigDecimal weight, BigDecimal heightCm,
        String surface, String distancePref, String runningStyle,
        Integer preferredTrainerId, String preferredTrainerName,
        int months, Integer trainingWeeks,
        long monthlyFee, BigDecimal discountPct, long totalAmount,
        LocalDate plannedStart, LocalDate startDate, LocalDate endDate,
        String status,
        String sentByName, LocalDateTime sentAt, LocalDate respondBy,
        String signedName, LocalDateTime signedAt,
        Long orderCode, String checkoutUrl,
        LocalDateTime paidAt, Long paidAmount, String paymentRef,
        String receivedByName,
        String registrationCode, String stallNo,
        String termsSnapshot, String termsSha256,
        String closedNote,
        Long daysLeft,
        // V50: training window the owner asked for
        LocalDate trainingStart, LocalDate trainingEnd,
        // V61: a change-of-owner contract continues the stay of takesOverContractId, signed by
        // previousOwnerName. Clause 8 contacts live on the contract (copied on renewal).
        Integer takesOverContractId, String previousOwnerName,
        String insurerName, String insurerPhone, String emergencyName, String emergencyPhone,
        // V64: the contract of a horse coming back (it keeps its code)
        Integer returnOfHorseId, String returnCode
) {
    /** The buyer's contract for the rest of a stay the seller paid for (V61). */
    public boolean isTakeover() {
        return takesOverContractId != null;
    }
}
