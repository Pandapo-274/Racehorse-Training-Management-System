package racehorse.backend.horserequest;

import java.util.List;
import java.util.Locale;
import java.util.Map;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import racehorse.backend.auth.ApiException;
import racehorse.backend.auth.AuthenticatedUser;
import racehorse.backend.auth.UserRepository;
import racehorse.backend.horse.HorseResponse;
import racehorse.backend.horse.HorseService;

@Service
public class HorseRequestService {

    private final HorseRequestRepository requests;
    private final HorseService horseService;
    private final UserRepository users;

    public HorseRequestService(HorseRequestRepository requests, HorseService horseService,
                               UserRepository users) {
        this.requests = requests;
        this.horseService = horseService;
        this.users = users;
    }

    /* ------------------------------------------------------------------ *
     * Chủ ngựa gửi yêu cầu
     * ------------------------------------------------------------------ */

    public HorseRequestRow submit(SubmitRequest raw, AuthenticatedUser me, String ip) {
        String name = raw.horseName().trim();

        // Chặn trước cho câu lỗi dễ hiểu. UX_HREQ_open trong cơ sở dữ liệu mới
        // là chốt thật - hai lần bấm gần như đồng thời sẽ lọt qua phép kiểm
        // này nhưng vẫn bị index chặn.
        if (requests.hasOpenRequest(me.userId(), name)) {
            throw new ApiException(HttpStatus.CONFLICT,
                    "You already have a pending request for a horse with this name",
                    Map.of("horseName", "A request for this name is already waiting"));
        }

        SubmitRequest clean = new SubmitRequest(
                name, blankToNull(raw.breed()), blankToNull(raw.horseGender()),
                raw.dateOfBirth(), blankToNull(raw.color()), blankToNull(raw.note()));

        int id = requests.insert(me.userId(), clean);
        users.writeAudit(me.userId(), "HORSE_REQUEST_SUBMIT", "HORSE_REQUEST", id, "SUCCESS", ip);
        return getOrThrow(id);
    }

    /* ------------------------------------------------------------------ *
     * Xem
     * ------------------------------------------------------------------ */

    /** Chủ ngựa chỉ thấy yêu cầu của mình; học viện thấy cả hàng chờ. */
    public List<HorseRequestRow> list(AuthenticatedUser me) {
        if ("HORSE_OWNER".equals(me.role())) {
            return requests.findByOwner(me.userId());
        }
        return requests.findAll();
    }

    /* ------------------------------------------------------------------ *
     * Học viện xử lý
     * ------------------------------------------------------------------ */

    /**
     * Duyệt: tạo con ngựa thật rồi gắn nó vào yêu cầu.
     *
     * Việc tạo ngựa giao thẳng cho HorseService.create, không chép lại logic.
     * Nhờ vậy mọi luật của nó vẫn được áp: mã đăng ký không trùng, số chuồng
     * chưa có ai, và chủ phải là tài khoản HORSE_OWNER. Chép lại thì một ngày
     * nào đó hai nơi sẽ lệch nhau, và ngựa tạo qua đường duyệt sẽ lọt những
     * thứ mà ngựa tạo tay bị chặn.
     */
    @Transactional
    public HorseRequestRow approve(int requestId, ReviewRequest review,
                                   AuthenticatedUser me, String ip) {
        HorseRequestRow req = getOrThrow(requestId);
        requirePending(req);

        String code = review == null ? null : blankToNull(review.registrationCode());
        if (code == null) {
            throw new ApiException(HttpStatus.BAD_REQUEST,
                    "A registration code is required to approve",
                    Map.of("registrationCode", "Enter the code the academy assigns"));
        }

        racehorse.backend.horse.HorseRequest newHorse = new racehorse.backend.horse.HorseRequest(
                req.ownerId(),
                null, null,                       // phả hệ điền sau, lúc duyệt chưa biết
                req.horseName(),
                code.toUpperCase(Locale.ROOT),
                req.breed(),
                req.horseGender(),
                req.dateOfBirth(),
                req.color(),
                null,                             // cân nặng do học viện cân, không lấy lời khai
                blankToNull(review.stallNo()));

        HorseResponse created = horseService.create(newHorse, me, ip);

        // 0 dòng nghĩa là người khác vừa xử lý xong trong lúc ta đang tạo ngựa.
        // @Transactional sẽ cuốn con ngựa vừa tạo đi cùng, nên không để lại rác.
        int changed = requests.markReviewed(requestId, "APPROVED", me.userId(),
                created.horseId(), blankToNull(review.note()));
        if (changed == 0) {
            throw alreadyHandled();
        }

        users.writeAudit(me.userId(), "HORSE_REQUEST_APPROVE", "HORSE_REQUEST",
                requestId, "SUCCESS", ip);
        return getOrThrow(requestId);
    }

    @Transactional
    public HorseRequestRow reject(int requestId, ReviewRequest review,
                                  AuthenticatedUser me, String ip) {
        requirePending(getOrThrow(requestId));

        String note = review == null ? null : blankToNull(review.note());
        if (note == null) {
            // Từ chối mà không nói lý do thì chủ ngựa không biết phải sửa gì,
            // và lần gửi lại cũng sẽ hỏng y như lần đầu.
            throw new ApiException(HttpStatus.BAD_REQUEST,
                    "Say why the request is being turned down",
                    Map.of("note", "A reason is required when rejecting"));
        }

        int changed = requests.markReviewed(requestId, "REJECTED", me.userId(), null, note);
        if (changed == 0) {
            throw alreadyHandled();
        }

        users.writeAudit(me.userId(), "HORSE_REQUEST_REJECT", "HORSE_REQUEST",
                requestId, "SUCCESS", ip);
        return getOrThrow(requestId);
    }

    /* ------------------------------------------------------------------ *
     * helper
     * ------------------------------------------------------------------ */

    private HorseRequestRow getOrThrow(int requestId) {
        return requests.findById(requestId)
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND,
                        "Request #" + requestId + " does not exist"));
    }

    private void requirePending(HorseRequestRow req) {
        if (!"PENDING".equals(req.status())) {
            throw alreadyHandled();
        }
    }

    private ApiException alreadyHandled() {
        return new ApiException(HttpStatus.CONFLICT,
                "This request has already been handled by someone else");
    }

    private String blankToNull(String s) {
        return (s == null || s.isBlank()) ? null : s.trim();
    }
}
