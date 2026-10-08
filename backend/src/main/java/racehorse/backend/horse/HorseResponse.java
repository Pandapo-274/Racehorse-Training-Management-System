package racehorse.backend.horse;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

public record HorseResponse(
        int horseId, int ownerId, String ownerName,
        Integer sireId, Integer damId,
        String horseName, String registrationCode, String breed, String horseGender,
        LocalDate dateOfBirth, String color, BigDecimal weight, String stallNo,
        String status, LocalDateTime createdAt, LocalDateTime updatedAt
) {}