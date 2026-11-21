package racehorse.backend.dto;

import java.time.Instant;
import java.time.LocalDate;

// Một ca chuyển viện (bảng EXTERNAL_REFERRAL) kèm tên ngựa, chủ, bác sĩ chuyển/nhận về.
// status: HOSPITALIZED | DISCHARGED | CANCELLED. Instant = giờ UTC có "Z" khi ra JSON.
public record ExternalReferralResponse(
        int referralId,
        int horseId,
        String horseName,
        String stallNo,
        String horseStatus,
        int ownerId,
        String ownerName,
        Integer reportId,
        Integer medicalRecordId,
        int referredById,
        String referredByName,
        Instant referredAt,
        String hospitalName,
        String referralReason,
        String urgency,
        LocalDate expectedReturn,
        String status,
        String dischargeNotes,
        Instant dischargedAt,
        Integer dischargedById,
        String dischargedByName
) {}
