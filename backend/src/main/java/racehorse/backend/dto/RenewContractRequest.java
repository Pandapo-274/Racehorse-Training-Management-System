package racehorse.backend.dto;

import jakarta.validation.constraints.NotNull;

/** Chủ ngựa xin gia hạn thời gian gửi ngựa: một hợp đồng mới nối tiếp hợp đồng đang chạy. */
public record RenewContractRequest(
        @NotNull(message = "Choose the horse") Integer horseId,
        @NotNull(message = "Choose how many months") Integer months
) {}
