package racehorse.backend.service;

import java.time.LocalDate;
import java.time.ZoneId;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import racehorse.backend.dto.HorseRequest;
import racehorse.backend.dto.HorseResponse;
import racehorse.backend.dto.ReceiveRequest;
import racehorse.backend.dto.SignContractRequest;
import racehorse.backend.entity.ContractRow;
import racehorse.backend.entity.HorseRequestRow;
import racehorse.backend.entity.Notice;
import racehorse.backend.exception.ApiException;
import racehorse.backend.repository.ContractRepository;
import racehorse.backend.repository.HorseRepository;
import racehorse.backend.repository.HorseRequestRepository;
import racehorse.backend.repository.UserRepository;
import racehorse.backend.security.AuthenticatedUser;
import racehorse.backend.service.payment.PaymentGateway;

/**
 * Hợp đồng gửi ngựa (V49): góp ý review "chưa thể hiện thời hạn ngựa ở CLB".
 *
 *   Manager duyệt hồ sơ  ->  SENT     (offerForRequest, gọi từ HorseRequestService.approve)
 *   Owner ký online      ->  SIGNED   (sign)
 *   Owner trả qua payOS  ->  PAID     (webhook hoặc syncPayment khi quay về từ trang payOS)
 *   Manager nhận ngựa    ->  ACTIVE   (receive: lúc này mới tạo HORSE, cấp mã, chuồng, HLV)
 *   Hết hạn chưa gia hạn ->  EXPIRED  (ContractJob mỗi đêm; ngựa không nhận giáo án mới)
 *   Gia hạn / rút ngựa   ->  ENDED
 *   Quá hạn ký/trả       ->  VOID
 *
 * Mọi bước đổi trạng thái là một UPDATE có điều kiện trạng thái cũ, rồi đọc số
 * dòng: webhook và trang "quay về" của chủ ngựa có thể đến cùng lúc, chỉ một
 * bên thắng và chỉ bên đó gửi thông báo.
 */
@Service
public class ContractService {

    private static final Logger log = LoggerFactory.getLogger(ContractService.class);
    private static final ZoneId CLUB_ZONE = ZoneId.of("Asia/Ho_Chi_Minh");
    public static final int[] REMINDER_DAYS = {14, 3};

    private final ContractRepository contracts;
    private final HorseRequestRepository requests;
    private final HorseService horseService;
    private final HorseRepository horses;
    private final HorsePhotoService photos;
    private final UserRepository users;
    private final NotificationService notifications;
    private final ContractTerms terms;
    private final PaymentGateway gateway;
    private final CostPaymentService costPayments;
    private final String frontendBaseUrl;

    public ContractService(ContractRepository contracts, HorseRequestRepository requests,
                           HorseService horseService, HorseRepository horses, HorsePhotoService photos,
                           UserRepository users, NotificationService notifications, ContractTerms terms,
                           PaymentGateway gateway, CostPaymentService costPayments,
                           @Value("${app.frontend-base-url:http://localhost:3000}") String frontendBaseUrl) {
        this.contracts = contracts;
        this.requests = requests;
        this.horseService = horseService;
        this.horses = horses;
        this.photos = photos;
        this.users = users;
        this.notifications = notifications;
        this.terms = terms;
        this.gateway = gateway;
        this.costPayments = costPayments;
        this.frontendBaseUrl = frontendBaseUrl.endsWith("/")
                ? frontendBaseUrl.substring(0, frontendBaseUrl.length() - 1) : frontendBaseUrl;
    }

    /* ==================================================================
     * 1. Offer: the manager approved the registration.
     * ================================================================== */

    /** Called inside HorseRequestService.approve's transaction. @return the new contract id. */
    public int offerForRequest(HorseRequestRow req, AuthenticatedUser manager) {
        if (req.contractMonths() == null) {
            throw new ApiException(HttpStatus.CONFLICT,
                    "This request was sent before stays had a term. Ask the owner to send it again "
                            + "with the number of months");
        }
        ContractTerms.Price price = terms.priceFor(req.contractMonths());
        LocalDate respondBy = today().plusDays(terms.offerDays());
        String managerName = users.findById(manager.userId()).map(u -> u.fullName()).orElse("the Club Manager");
        String text = terms.text(new ContractTerms.Parties(
                        req.ownerName(), req.horseName(), req.breed(), req.horseGender(), req.dateOfBirth(),
                        req.color(), req.sireName(), req.damName(), req.trainingWeeks(), req.preferredStart(),
                        managerName, false, null, req.trainingStart(), req.trainingEnd(),
                        req.insurerName(), req.insurerPhone(), req.emergencyName(), req.emergencyPhone(),
                        null, null),
                price, respondBy);
        return contracts.insert(new ContractRepository.NewContract(
                req.requestId(), null, req.ownerId(), null, price.months(), req.trainingWeeks(),
                price.monthlyFee(), price.discountPct(), price.total(), req.preferredStart(), null,
                text, ContractTerms.sha256(text), manager.userId(), respondBy,
                req.trainingStart(), req.trainingEnd(), null,
                req.insurerName(), req.insurerPhone(), req.emergencyName(), req.emergencyPhone()));
    }

    /* ==================================================================
     * 2. Reading
     * ================================================================== */

    public List<ContractRow> list(AuthenticatedUser user) {
        return isOwner(user) ? contracts.findByOwner(user.userId()) : contracts.findAll();
    }

    public ContractRow get(int contractId, AuthenticatedUser user) {
        ContractRow c = getOrThrow(contractId);
        if (isOwner(user) && c.ownerId() != user.userId()) {
            throw new ApiException(HttpStatus.FORBIDDEN, "This contract belongs to another owner");
        }
        return c;
    }

    /** Contracts of one horse, newest first: its stay so far and any renewal waiting. */
    public List<ContractRow> forHorse(int horseId, AuthenticatedUser user) {
        HorseResponse horse = horseService.getOrThrow(horseId);
        if (isOwner(user) && horse.ownerId() != user.userId()) {
            throw new ApiException(HttpStatus.FORBIDDEN, "You can only view your own horses");
        }
        // V61: after a sale the previous owner's contracts (their name, contacts, payment) stay theirs.
        return isOwner(user) ? contracts.findByHorseAndOwner(horseId, user.userId()) : contracts.findByHorse(horseId);
    }

    public record Pricing(long monthlyFee, Map<Integer, java.math.BigDecimal> discounts, int offerDays,
                          boolean onlinePayment) {}

    /** What the registration form needs to show an estimate before sending. */
    public Pricing pricing() {
        return new Pricing(terms.monthlyFee(), terms.discountTiers(), terms.offerDays(), gateway.isConfigured());
    }

    /** applies = false: the contract is not running, or was signed before clause 11 existed. */
    public record Commitment(int contractId, boolean applies, LocalDate from, LocalDate to,
                             HorsePolicy.Delivery delivery) {}

    /**
     * Clause 11: how much of the promised training has been delivered so far. If the horse
     * arrived late, the training window moves with it (clause 3), staying inside the stay.
     */
    public Commitment commitment(int contractId, AuthenticatedUser user) {
        ContractRow c = get(contractId, user);
        boolean running = "ACTIVE".equals(c.status()) || "EXPIRED".equals(c.status());
        boolean hasClause = c.termsSnapshot() != null && c.termsSnapshot().contains(ContractTerms.COMMITMENT_HEADING);
        if (!running || !hasClause || c.horseId() == null) {
            return new Commitment(contractId, false, null, null, null);
        }
        LocalDate from = c.trainingStart() != null ? c.trainingStart() : c.startDate();
        LocalDate to = c.trainingEnd() != null ? c.trainingEnd() : c.endDate();
        if (c.trainingStart() != null && c.plannedStart() != null && c.startDate() != null
                && c.startDate().isAfter(c.plannedStart())) {
            long late = java.time.temporal.ChronoUnit.DAYS.between(c.plannedStart(), c.startDate());
            from = from.plusDays(late);
            to = to.plusDays(late);
        }
        if (c.endDate() != null && to.isAfter(c.endDate())) {
            to = c.endDate();
        }
        LocalDate today = today();
        ContractRepository.SessionCounts n = contracts.sessionCounts(c.horseId(), from, to, today);
        return new Commitment(contractId, true, from, to,
                HorsePolicy.delivery(n.due(), n.delivered(), n.excused(), today.isAfter(to)));
    }

    /* ==================================================================
     * 3. Signing
     * ================================================================== */

    @Transactional
    public ContractRow sign(int contractId, SignContractRequest r, AuthenticatedUser owner, String ip) {
        ContractRow c = get(contractId, owner);
        if (!isOwner(owner)) {
            throw new ApiException(HttpStatus.FORBIDDEN, "Only the horse owner signs the contract");
        }
        if (!"SENT".equals(c.status())) {
            throw new ApiException(HttpStatus.CONFLICT, "This contract is " + c.status().toLowerCase(Locale.ROOT)
                    + " and cannot be signed");
        }
        if (!c.isTakeover() && c.respondBy().isBefore(today())) {
            throw new ApiException(HttpStatus.CONFLICT, "This offer lapsed on " + c.respondBy());
        }
        Map<String, String> errors = new HashMap<>();
        if (!Boolean.TRUE.equals(r.agree())) {
            errors.put("agree", "Tick the box to confirm you have read and agree");
        }
        String ownerName = users.findById(owner.userId()).map(u -> u.fullName()).orElse("");
        if (!sameName(r.fullName(), ownerName)) {
            errors.put("fullName", "Type your full name exactly as on your account: " + ownerName);
        }
        if (!errors.isEmpty()) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "The contract was not signed", errors);
        }
        // The text must still be the text that was sent, and the one the owner was looking at.
        String stored = ContractTerms.sha256(c.termsSnapshot());
        if (!stored.equals(c.termsSha256()) || !stored.equalsIgnoreCase(r.termsSha256())) {
            throw new ApiException(HttpStatus.CONFLICT,
                    "The contract text does not match what you are looking at. Reload the page and read it again");
        }
        if (contracts.markSigned(contractId, owner.userId(), r.fullName().trim(), ip, stored) == 0) {
            throw alreadyHandled();
        }
        users.writeAudit(owner.userId(), "CONTRACT_SIGN", "HORSE_CONTRACT", contractId, "SUCCESS", ip,
                "Signed as \"" + r.fullName().trim() + "\", SHA-256 " + stored);
        if (c.isTakeover()) {
            return activateTakeover(c);
        }
        notifications.notifyRole("CLUB_MANAGER", owner, new Notice("CONTRACT_SIGNED",
                "Contract signed",
                c.ownerName() + " signed the contract for " + c.horseName() + ". Waiting for payment.",
                Notice.HORSE_REQUEST, c.requestId()));
        return getOrThrow(contractId);
    }

    /* ==================================================================
     * 4. Paying (payOS)
     * ================================================================== */

    /** checkoutUrl is null when it turned out the contract was already paid. */
    public record PaymentStart(int contractId, String checkoutUrl, long amount, boolean alreadyPaid) {}

    /**
     * Opens (or reuses) a payOS payment link. A link that is still open is reused,
     * so pressing Pay twice cannot produce two links that could both be paid.
     */
    @Transactional
    public PaymentStart startPayment(int contractId, AuthenticatedUser owner) {
        ContractRow c = get(contractId, owner);
        if (!isOwner(owner)) {
            throw new ApiException(HttpStatus.FORBIDDEN, "Only the horse owner pays the contract");
        }
        if (!"SIGNED".equals(c.status())) {
            throw new ApiException(HttpStatus.CONFLICT, "SENT".equals(c.status())
                    ? "Sign the contract first" : "This contract is not waiting for payment");
        }
        if (c.respondBy().isBefore(today())) {
            throw new ApiException(HttpStatus.CONFLICT, "This offer lapsed on " + c.respondBy());
        }
        if (c.orderCode() != null && c.checkoutUrl() != null) {
            PaymentGateway.Payment existing = gateway.fetch(c.orderCode());
            if (existing.paid()) {
                // Paid already (the webhook has not arrived yet): record it, do not charge twice.
                applyPayment(c, existing);
                return new PaymentStart(contractId, null, c.totalAmount(), true);
            }
            if ("PENDING".equals(existing.status()) || "PROCESSING".equals(existing.status())) {
                // Still open on payOS: reuse it, so two open links can never both be paid.
                return new PaymentStart(contractId, c.checkoutUrl(), c.totalAmount(), false);
            }
            // CANCELLED / EXPIRED / FAILED: that link is dead, make a new one below.
        }
        long orderCode = newOrderCode(contractId);
        String back = frontendBaseUrl + "/horse-owner/contracts/" + contractId;
        long expiresAt = c.respondBy().plusDays(1).atStartOfDay(CLUB_ZONE).toEpochSecond();
        PaymentGateway.Link link = gateway.createLink(orderCode, c.totalAmount(),
                paymentDescription(contractId),
                "Stay " + c.months() + " month" + (c.months() == 1 ? "" : "s") + " - " + c.horseName(),
                c.ownerName(), back, back, expiresAt);
        if (contracts.setPaymentLink(contractId, link.orderCode(), link.paymentLinkId(), link.checkoutUrl()) == 0) {
            throw alreadyHandled();
        }
        return new PaymentStart(contractId, link.checkoutUrl(), c.totalAmount(), false);
    }

    /**
     * The owner came back from the payOS page. Ask payOS directly instead of waiting for
     * the webhook: on a laptop demo the webhook cannot reach localhost at all, and this
     * call is what still moves the contract to PAID.
     */
    @Transactional
    public ContractRow syncPayment(int contractId, AuthenticatedUser user) {
        ContractRow c = get(contractId, user);
        if (c.orderCode() != null && ("SIGNED".equals(c.status()) || "VOID".equals(c.status()))) {
            PaymentGateway.Payment p = gateway.fetch(c.orderCode());
            if (p.paid()) {
                applyPayment(c, p);
            }
        }
        return getOrThrow(contractId);
    }

    /**
     * payOS calls this (through PaymentWebhookController) when money arrives. The body is
     * trusted only after its signature checks out against our checksum key.
     *
     * @return false when the body was not a valid payOS webhook.
     */
    @Transactional
    public boolean handleWebhook(String rawBody) {
        PaymentGateway.Payment p;
        try {
            p = gateway.verifyWebhook(rawBody);
        } catch (IllegalArgumentException e) {
            log.warn("Rejected payOS webhook: {}", e.getMessage());
            return false;
        }
        // payOS sends a test call when the webhook URL is registered: a valid signature
        // for an order we never made. Acknowledge it and do nothing.
        var id = contracts.findIdByOrderCode(p.orderCode());
        if (id.isEmpty()) {
            // V57: the same payOS channel also collects separately billed costs.
            if (!costPayments.applyWebhook(p)) {
                log.info("payOS webhook for unknown order {} acknowledged", p.orderCode());
            }
            return true;
        }
        if (p.paid()) {
            applyPayment(getOrThrow(id.get()), p);
        }
        return true;
    }

    /** Shared by webhook, sync and Pay: mark PAID once, then start a renewal straight away. */
    void applyPayment(ContractRow c, PaymentGateway.Payment p) {
        if (p.amount() < c.totalAmount()) {
            log.warn("Contract {} received {} VND of {}: not marked paid", c.contractId(), p.amount(), c.totalAmount());
            return;
        }
        if (contracts.markPaid(c.contractId(), p.amount(), p.reference()) == 0) {
            return; // the other path got here first
        }
        users.writeAudit(c.ownerId(), "CONTRACT_PAID", "HORSE_CONTRACT", c.contractId(), "SUCCESS", null,
                ContractTerms.vnd(p.amount()) + " via payOS, order " + p.orderCode()
                        + (p.reference() == null ? "" : ", bank ref " + p.reference()));
        if (c.renewsContractId() != null && c.horseId() != null) {
            if (horses.findById(c.horseId()).map(HorseResponse::isWithdrawn).orElse(false)) {
                // V62: money for a horse that has left. Record it (it is real), start nothing,
                // and tell the club to send it back.
                notifications.notifyRole("CLUB_MANAGER", null, new Notice("CONTRACT_PAID",
                        "Renewal paid for a horse that left - refund it",
                        c.ownerName() + " paid " + ContractTerms.vnd(p.amount()) + " to renew " + c.horseName()
                                + ", which has already left the club. Refund it to the owner.",
                        Notice.HORSE, c.horseId()));
                notifications.notifyUser(c.ownerId(), new Notice("CONTRACT_PAID", "Payment received - to be refunded",
                        "We received " + ContractTerms.vnd(p.amount()) + " to renew " + c.horseName()
                                + ", but it has left the club. The club refunds this payment.",
                        Notice.HORSE_REQUEST, c.requestId()));
                return;
            }
            startRenewal(c);
            return;
        }
        notifications.notifyUser(c.ownerId(), new Notice("CONTRACT_PAID", "Payment received",
                "We received " + ContractTerms.vnd(p.amount()) + " for " + c.horseName()
                        + ". Bring the horse in; the stay starts the day the club receives it.",
                Notice.HORSE_REQUEST, c.requestId()));
        notifications.notifyRole("CLUB_MANAGER", null, new Notice("CONTRACT_PAID",
                "Contract paid - receive the horse",
                c.ownerName() + " paid for " + c.horseName()
                        + ". Assign the code, stall and Head Trainer when it arrives.",
                Notice.HORSE_REQUEST, c.requestId()));
    }

    private void startRenewal(ContractRow renewal) {
        ContractRow old = getOrThrow(renewal.renewsContractId());
        LocalDate start = old.endDate() != null && old.endDate().isAfter(today()) ? old.endDate() : today();
        LocalDate end = start.plusMonths(renewal.months());
        // Close the old stay first: UX_HCON_running allows one running stay per horse.
        contracts.markEnded(old.contractId(), "Renewed by contract #" + renewal.contractId());
        contracts.markActive(renewal.contractId(), renewal.horseId(), start, end, null);
        notifications.notifyUser(renewal.ownerId(), new Notice("CONTRACT_RENEWED", "Stay renewed",
                renewal.horseName() + " now stays until " + end + ".", Notice.HORSE, renewal.horseId()));
        notifications.notifyRole("CLUB_MANAGER", null, new Notice("CONTRACT_RENEWED", "Stay renewed",
                renewal.ownerName() + " renewed " + renewal.horseName() + " until " + end + ".",
                Notice.HORSE, renewal.horseId()));
    }

    /* ==================================================================
     * 5. Receiving the horse: the old Approve, moved to after payment.
     * ================================================================== */

    @Transactional
    public ContractRow receive(int contractId, ReceiveRequest r, AuthenticatedUser manager, String ip) {
        if (!"CLUB_MANAGER".equals(manager.role())) {
            throw new ApiException(HttpStatus.FORBIDDEN, "Only the Club Manager receives horses");
        }
        ContractRow c = getOrThrow(contractId);
        if (c.renewsContractId() != null) {
            throw new ApiException(HttpStatus.CONFLICT, "A renewal starts by itself when it is paid");
        }
        if (!"PAID".equals(c.status())) {
            throw new ApiException(HttpStatus.CONFLICT,
                    "Only a paid contract can be received (this one is " + c.status().toLowerCase(Locale.ROOT) + ")");
        }
        HorseRequestRow req = requests.findById(c.requestId())
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "Request of this contract not found"));

        Map<String, String> errors = new HashMap<>();
        String code = r.registrationCode() == null ? null : r.registrationCode().trim().toUpperCase(Locale.ROOT);
        String codeProblem = HorsePolicy.codeProblem(code);
        if (codeProblem != null) errors.put("registrationCode", codeProblem);
        if (r.stallNo() == null || r.stallNo().isBlank()) errors.put("stallNo", "Assign a stall");
        if (r.bcs() == null || r.bcs() < 1 || r.bcs() > 9) errors.put("bcs", "Score the body condition (1-9)");
        boolean overridesWish = req.preferredTrainerId() != null
                && !Objects.equals(req.preferredTrainerId(), r.trainerId());
        String note = r.note() == null || r.note().isBlank() ? null : r.note().trim();
        if (overridesWish && note == null) {
            errors.put("note", "The owner asked for " + req.preferredTrainerName()
                    + ". Say why another Head Trainer is assigned");
        }
        if (!errors.isEmpty()) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "The horse was not received", errors);
        }

        HorseResponse created = horseService.create(new HorseRequest(
                req.ownerId(), req.sireId(), req.damId(), req.horseName(), code,
                req.breed(), req.horseGender(), req.dateOfBirth(), req.color(),
                r.weight() != null ? r.weight() : req.weight(),
                r.stallNo().trim(), HorsePolicy.ACADEMY), manager, ip);
        int horseId = created.horseId();
        horses.updateIntake(horseId, r.heightCm() != null ? r.heightCm() : req.heightCm(), r.bcs(),
                req.surface(), req.distancePref(), req.runningStyle());
        photos.copyRequestPhotoToHorse(req.photoUrl(), horseId);
        if (r.trainerId() != null) {
            horseService.assignTrainer(horseId, r.trainerId(), manager, ip);
        }

        LocalDate start = today();
        LocalDate end = start.plusMonths(c.months());
        if (contracts.markActive(contractId, horseId, start, end, manager.userId()) == 0) {
            throw alreadyHandled();
        }
        requests.setHorse(c.requestId(), horseId);

        String group = HorsePolicy.bcsGroup(r.bcs());
        users.writeAudit(manager.userId(), "HORSE_RECEIVE", "HORSE_CONTRACT", contractId, "SUCCESS", ip,
                "Horse #" + horseId + " " + code + ", stall " + r.stallNo().trim() + ", BCS " + r.bcs()
                        + " (" + group + "), stay " + start + " to " + end
                        + (note == null ? "" : ". Note: " + note));
        notifications.notifyUser(c.ownerId(), new Notice("HORSE_RECEIVED", "Horse received",
                c.horseName() + " is at the club: code " + code + ", stall " + r.stallNo().trim()
                        + ". The stay runs until " + end + "."
                        + (overridesWish ? " Your preferred trainer was not available: " + note : ""),
                Notice.HORSE, horseId));
        return getOrThrow(contractId);
    }

    /* ==================================================================
     * 6. Renewal and cancelling
     * ================================================================== */

    @Transactional
    public ContractRow renew(int horseId, int months, AuthenticatedUser owner, String ip) {
        if (!isOwner(owner)) {
            throw new ApiException(HttpStatus.FORBIDDEN, "Only the horse owner renews a stay");
        }
        ContractRow running = contracts.findRunning(horseId)
                .orElseThrow(() -> new ApiException(HttpStatus.CONFLICT,
                        "This horse has no running stay to renew"));
        if (running.ownerId() != owner.userId()) {
            throw new ApiException(HttpStatus.FORBIDDEN, "You can only renew your own horses");
        }
        if (contracts.hasOpenRenewal(horseId)) {
            throw new ApiException(HttpStatus.CONFLICT,
                    "A renewal for this horse is already waiting for your signature or payment");
        }
        if (months < HorsePolicy.MIN_CONTRACT_MONTHS || months > HorsePolicy.MAX_CONTRACT_MONTHS) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "Invalid term",
                    Map.of("months", "Between " + HorsePolicy.MIN_CONTRACT_MONTHS + " and "
                            + HorsePolicy.MAX_CONTRACT_MONTHS + " months"));
        }
        HorseResponse horse = horseService.getOrThrow(horseId);
        if (horse.isWithdrawn()) {
            throw new ApiException(HttpStatus.CONFLICT, horse.horseName() + " has left the club");
        }
        HorseRequestRow req = requests.findById(running.requestId()).orElseThrow();
        ContractTerms.Price price = terms.priceFor(months);
        LocalDate respondBy = today().plusDays(terms.offerDays());
        LocalDate renewalStart = running.endDate() != null && running.endDate().isAfter(today())
                ? running.endDate() : today();
        // V61: clause 8 contacts come from the running contract - after a sale they are the new
        // owner's, not those of the request the previous owner filed.
        Contacts contacts = running.insurerName() != null || running.emergencyName() != null
                ? new Contacts(running.insurerName(), running.insurerPhone(),
                               running.emergencyName(), running.emergencyPhone())
                : new Contacts(req.insurerName(), req.insurerPhone(), req.emergencyName(), req.emergencyPhone());
        String text = terms.text(new ContractTerms.Parties(
                        running.ownerName(), horse.horseName(), horse.breed(), horse.horseGender(),
                        horse.dateOfBirth(), horse.color(), req.sireName(), req.damName(), null, null,
                        "the Club Manager", true, renewalStart, null, null,
                        contacts.insurerName(), contacts.insurerPhone(),
                        contacts.emergencyName(), contacts.emergencyPhone(),
                        horse.registrationCode(), horse.stallNo()),
                price, respondBy);
        int id = contracts.insert(new ContractRepository.NewContract(
                running.requestId(), horseId, owner.userId(), running.contractId(), months, null,
                price.monthlyFee(), price.discountPct(), price.total(), renewalStart, null,
                text, ContractTerms.sha256(text), owner.userId(), respondBy, null, null, null,
                contacts.insurerName(), contacts.insurerPhone(),
                contacts.emergencyName(), contacts.emergencyPhone()));
        users.writeAudit(owner.userId(), "CONTRACT_RENEW_REQUEST", "HORSE_CONTRACT", id, "SUCCESS", ip,
                months + " month(s) after contract #" + running.contractId());
        notifications.notifyRole("CLUB_MANAGER", owner, new Notice("CONTRACT_RENEW_REQUEST",
                "Stay renewal started", running.ownerName() + " is renewing " + horse.horseName()
                        + " for " + months + " month(s).", Notice.HORSE, horseId));
        return getOrThrow(id);
    }

    /** Owner declines, or the manager withdraws an offer nobody has paid. */
    @Transactional
    public ContractRow cancel(int contractId, String reason, AuthenticatedUser user, String ip) {
        ContractRow c = get(contractId, user);
        if (c.isTakeover()) {
            // The horse is already the new owner's; dropping this contract would leave it with no stay.
            throw new ApiException(HttpStatus.CONFLICT, "A change-of-owner contract cannot be declined or "
                    + "withdrawn. If the sale did not happen, the club transfers the horse back");
        }
        if (!"SENT".equals(c.status()) && !"SIGNED".equals(c.status())) {
            throw new ApiException(HttpStatus.CONFLICT,
                    "Only a contract that is not paid yet can be cancelled");
        }
        String note = (isOwner(user) ? "Declined by the owner" : "Withdrawn by the club")
                + (reason == null || reason.isBlank() ? "" : ": " + reason.trim());
        if (contracts.markVoid(contractId, note) == 0) {
            throw alreadyHandled();
        }
        users.writeAudit(user.userId(), "CONTRACT_CANCEL", "HORSE_CONTRACT", contractId, "SUCCESS", ip, note);
        if (isOwner(user)) {
            notifications.notifyRole("CLUB_MANAGER", user, new Notice("CONTRACT_VOID", "Contract declined",
                    c.ownerName() + " declined the contract for " + c.horseName() + ".",
                    Notice.HORSE_REQUEST, c.requestId()));
        } else {
            notifications.notifyUser(c.ownerId(), new Notice("CONTRACT_VOID", "Contract withdrawn",
                    "The club withdrew the contract for " + c.horseName() + ". " + note,
                    Notice.HORSE_REQUEST, c.requestId()));
        }
        return getOrThrow(contractId);
    }

    /* ==================================================================
     * 7. Nightly job (ContractJob)
     * ================================================================== */

    @Transactional
    public int voidOverdueOffers(LocalDate today) {
        int n = 0;
        for (ContractRow c : contracts.findOverdueOffers(today)) {
            if (contracts.markVoid(c.contractId(), "Not signed and paid by " + c.respondBy()) > 0) {
                n++;
                notifications.notifyUser(c.ownerId(), new Notice("CONTRACT_VOID", "Contract offer lapsed",
                        "The contract for " + c.horseName() + " was not signed and paid by " + c.respondBy()
                                + ". Send the registration again when you are ready.",
                        Notice.HORSE_REQUEST, c.requestId()));
            }
        }
        return n;
    }

    @Transactional
    public int expireLapsed(LocalDate today) {
        int n = 0;
        for (ContractRow c : contracts.findLapsed(today)) {
            if (contracts.markExpired(c.contractId()) > 0) {
                n++;
                Notice owner = new Notice("CONTRACT_EXPIRED", "Stay has ended",
                        c.horseName() + "'s stay ended on " + c.endDate()
                                + ". It takes no new training plan until you renew or withdraw it.",
                        Notice.HORSE, c.horseId());
                notifications.notifyUser(c.ownerId(), owner);
                notifications.notifyRole("CLUB_MANAGER", null, new Notice("CONTRACT_EXPIRED",
                        "Stay ended without renewal",
                        c.horseName() + " (" + c.ownerName() + ") ended on " + c.endDate()
                                + ". Follow up: renewal or withdrawal.",
                        Notice.HORSE, c.horseId()));
            }
        }
        return n;
    }

    @Transactional
    public int sendReminders(LocalDate today) {
        int n = 0;
        for (ContractRow c : contracts.findToRemind(today, REMINDER_DAYS[0])) {
            long left = java.time.temporal.ChronoUnit.DAYS.between(today, c.endDate());
            int threshold = left <= REMINDER_DAYS[1] ? REMINDER_DAYS[1] : REMINDER_DAYS[0];
            if (contracts.markReminded(c.contractId(), threshold) == 0) {
                continue; // this reminder (or a closer one) already went out
            }
            n++;
            notifications.notifyUser(c.ownerId(), new Notice("CONTRACT_ENDING",
                    "Stay ends in " + left + " day" + (left == 1 ? "" : "s"),
                    c.horseName() + "'s stay ends on " + c.endDate() + ". Renew it from the horse page.",
                    Notice.HORSE, c.horseId()));
            notifications.notifyRole("CLUB_MANAGER", null, new Notice("CONTRACT_ENDING",
                    c.horseName() + ": stay ends in " + left + " day" + (left == 1 ? "" : "s"),
                    c.ownerName() + "'s stay for " + c.horseName() + " ends on " + c.endDate() + ".",
                    Notice.HORSE, c.horseId()));
        }
        return n;
    }

    /* ==================================================================
     * 8. Change of owner (V61): the buyer signs their own contract.
     * ================================================================== */

    /** Clause 8 contacts of the new owner, handed to the club at the sale. */
    public record Contacts(String insurerName, String insurerPhone, String emergencyName, String emergencyPhone) {

        public static Contacts of(String insurerName, String insurerPhone, String emergencyName, String emergencyPhone) {
            return new Contacts(trim(insurerName), trim(insurerPhone), trim(emergencyName), trim(emergencyPhone));
        }

        /** Same messages as the registration form (V54). */
        public Map<String, String> problems() {
            Map<String, String> errors = new java.util.LinkedHashMap<>();
            if (insurerName == null) errors.put("insurerName", "Name the new owner's insurer");
            if (insurerPhone == null) errors.put("insurerPhone", "Give the insurer's emergency number");
            if (emergencyName == null) errors.put("emergencyName", "Name a second person to call");
            if (emergencyPhone == null) errors.put("emergencyPhone", "Give that person's phone number");
            return errors;
        }

        private static String trim(String s) {
            return s == null || s.isBlank() ? null : s.trim();
        }
    }

    /**
     * Checked BEFORE a transfer changes anything. Returns the contract whose stay the new owner
     * takes over: the running stay, or a change-of-owner contract still unsigned when the horse
     * is sold on. Null when the horse has no contract (registered before stays had a term).
     */
    public ContractRow transferSource(int horseId, String horseName) {
        var open = contracts.findOpenRenewal(horseId);
        if (open.isPresent()) {
            ContractRow r = open.get();
            throw new ApiException(HttpStatus.CONFLICT, "PAID".equals(r.status())
                    ? r.ownerName() + " has already paid a renewal of " + horseName
                            + "'s stay. Settle it with them before the transfer"
                    : "A renewal of " + horseName + "'s stay is waiting for " + r.ownerName()
                            + ". Cancel it before the transfer");
        }
        var waiting = contracts.findWaitingTakeover(horseId);
        if (waiting.isPresent()) {
            return waiting.get();
        }
        var running = contracts.findRunning(horseId);
        if (running.isEmpty()) {
            return null;
        }
        ContractRow c = running.get();
        if ("EXPIRED".equals(c.status()) || !c.endDate().isAfter(today())) {
            throw new ApiException(HttpStatus.CONFLICT, horseName + "'s stay ended on " + c.endDate() + ". "
                    + c.ownerName() + " renews it, or the horse leaves the club, before the transfer");
        }
        return c;
    }

    /**
     * Runs inside HorseAdminService.transferOwner's transaction, after the horse changed owner.
     * The seller's contract ENDS and stays in the seller's account; the buyer gets a contract
     * for the rest of the same stay, nothing to pay, with the buyer's own clause 8 contacts.
     * @return the new contract id.
     */
    public int handOver(ContractRow source, HorseResponse horse, int newOwnerId, String newOwnerName,
                        Contacts contacts, AuthenticatedUser manager, String ip) {
        LocalDate from = today();
        LocalDate until = source.endDate();
        String note = "Horse passed to " + newOwnerName + " on " + from;
        int closed = source.isTakeover() && "SENT".equals(source.status())
                ? contracts.markVoid(source.contractId(), note + ", before " + source.ownerName() + " signed")
                : contracts.markEnded(source.contractId(), note);
        if (closed == 0) {
            throw alreadyHandled();
        }
        HorseRequestRow req = requests.findById(source.requestId()).orElseThrow();
        String managerName = users.findById(manager.userId()).map(u -> u.fullName()).orElse("the Club Manager");
        String text = terms.takeoverText(new ContractTerms.Parties(
                        newOwnerName, horse.horseName(), horse.breed(), horse.horseGender(), horse.dateOfBirth(),
                        horse.color(), req.sireName(), req.damName(), source.trainingWeeks(), from,
                        managerName, false, null, source.trainingStart(), source.trainingEnd(),
                        contacts.insurerName(), contacts.insurerPhone(),
                        contacts.emergencyName(), contacts.emergencyPhone(),
                        horse.registrationCode(), horse.stallNo()),
                source.ownerName(), from, until, horse.registrationCode(), horse.stallNo(), until);
        int id = contracts.insert(new ContractRepository.NewContract(
                source.requestId(), horse.horseId(), newOwnerId, null, source.months(), source.trainingWeeks(),
                0, java.math.BigDecimal.ZERO, 0, from, until,
                text, ContractTerms.sha256(text), manager.userId(), until,
                source.trainingStart(), source.trainingEnd(), source.contractId(),
                contacts.insurerName(), contacts.insurerPhone(),
                contacts.emergencyName(), contacts.emergencyPhone()));
        users.writeAudit(manager.userId(), "CONTRACT_TAKEOVER_SENT", "HORSE_CONTRACT", id, "SUCCESS", ip,
                "Contract #" + source.contractId() + " (" + source.ownerName() + ") closed; "
                        + newOwnerName + " takes over the stay until " + until);
        notifications.notifyUser(newOwnerId, new Notice("CONTRACT_TAKEOVER",
                "Sign to take over " + horse.horseName() + "'s stay",
                horse.horseName() + " is now yours. Sign the change-of-owner contract to take over its stay "
                        + "until " + until + ". Nothing to pay. Until you sign, it takes no new training plan "
                        + "or race entry.",
                Notice.HORSE_REQUEST, source.requestId()));
        return id;
    }

    /** The buyer signed: the stay runs on at once, no payment, no arrival. */
    private ContractRow activateTakeover(ContractRow c) {
        boolean expired = !c.endDate().isAfter(today());
        if (contracts.activateTakeover(c.contractId(), c.plannedStart(), c.endDate(), expired) == 0) {
            throw alreadyHandled();
        }
        notifications.notifyRole("CLUB_MANAGER", null, new Notice("CONTRACT_SIGNED",
                "Change of owner signed",
                c.ownerName() + " signed the contract for " + c.horseName() + " and takes over its stay until "
                        + c.endDate() + ".",
                Notice.HORSE, c.horseId()));
        return getOrThrow(c.contractId());
    }

    /* ---------------- helpers ---------------- */

    /** payOS limits the description to 9 characters for a bank account not linked through payOS. */
    static String paymentDescription(int contractId) {
        return "HD" + contractId;
    }

    static long newOrderCode(int contractId) {
        // contract id in the high digits keeps codes unique across contracts; the time in the
        // low digits keeps a second link for the same contract from reusing the first code.
        return contractId * 100_000L + (System.currentTimeMillis() / 1000) % 100_000L;
    }

    static boolean sameName(String typed, String onAccount) {
        if (typed == null || onAccount == null) return false;
        String a = typed.trim().replaceAll("\\s+", " ");
        String b = onAccount.trim().replaceAll("\\s+", " ");
        return !a.isEmpty() && a.equalsIgnoreCase(b);
    }

    private LocalDate today() {
        return LocalDate.now(CLUB_ZONE);
    }

    private static boolean isOwner(AuthenticatedUser user) {
        return "HORSE_OWNER".equals(user.role());
    }

    private ContractRow getOrThrow(int contractId) {
        return contracts.findById(contractId)
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "Contract #" + contractId + " does not exist"));
    }

    private ApiException alreadyHandled() {
        return new ApiException(HttpStatus.CONFLICT,
                "This contract has just changed. Reload to see where it stands");
    }
}
