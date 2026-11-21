package racehorse.backend.service;

import java.time.LocalDate;
import java.time.ZoneId;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Mỗi đêm (V49), ba việc của thời hạn gửi ngựa, theo thứ tự:
 *   1. Lời mời hợp đồng quá hạn ký/trả -> VOID, báo chủ ngựa.
 *   2. Hợp đồng ACTIVE đã tới end_date -> EXPIRED, báo chủ ngựa và Manager. Ngựa vẫn
 *      ở chuồng (không tự rút: con ngựa thật vẫn đứng đó), nhưng không nhận giáo án mới.
 *   3. Còn 14 và 3 ngày -> nhắc gia hạn (mỗi mốc một lần, nhớ trong reminded_days).
 * Mỗi việc có try/catch riêng để một lỗi không chặn hai việc còn lại, giống MissedTaskJob.
 */
@Component
public class ContractJob {

    private static final Logger log = LoggerFactory.getLogger(ContractJob.class);

    private final ContractService contracts;
    private final boolean enabled;

    public ContractJob(ContractService contracts,
                       @Value("${app.contract.job.enabled:true}") boolean enabled) {
        this.contracts = contracts;
        this.enabled = enabled;
    }

    @Scheduled(cron = "${app.contract.job.cron:0 30 0 * * *}", zone = "${app.contract.job.zone:Asia/Ho_Chi_Minh}")
    public void runDaily() {
        if (!enabled) return;
        LocalDate today = LocalDate.now(ZoneId.of("Asia/Ho_Chi_Minh"));
        try {
            log.info("Contract offers voided (not signed and paid in time): {}", contracts.voidOverdueOffers(today));
        } catch (RuntimeException e) {
            log.error("Voiding overdue contract offers failed", e);
        }
        try {
            log.info("Stays expired without renewal: {}", contracts.expireLapsed(today));
        } catch (RuntimeException e) {
            log.error("Expiring stays failed", e);
        }
        try {
            log.info("Stay-ending reminders sent: {}", contracts.sendReminders(today));
        } catch (RuntimeException e) {
            log.error("Stay reminders failed", e);
        }
    }
}
