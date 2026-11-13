package racehorse.backend.dto;

import java.time.Instant;
import java.time.LocalDate;

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
