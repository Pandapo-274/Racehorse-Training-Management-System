// src/features/horse/HorseReviewPage.js
/*
 * Trang /manager/horse-requests: Club Manager duyệt yêu cầu đăng ký ngựa do chủ ngựa gửi (HORSE_REQUEST).
 *
 * Sau buổi review (V48/V49):
 *  - Mỗi yêu cầu có khung "Review basis": ảnh, số đo + phát triển theo tuổi, năng khiếu, thời hạn,
 *    HLV mong muốn và số chỗ còn của HLV đó, số ngựa chủ đang gửi. Đây là câu trả lời cho
 *    "duyệt dựa vào đâu".
 *  - Ngựa vào CLB: duyệt = xác nhận 2 ô (ảnh khớp mô tả, đã đối chiếu giấy tờ cha mẹ) rồi GỬI HỢP ĐỒNG.
 *    Không có ảnh thì nút duyệt khoá. Mã đăng ký, chuồng, HLV chốt lúc nhận ngựa (trang Contracts).
 *  - Hồ sơ pedigree: vẫn cấp mã ngay; ô mã có gợi ý và kiểm trùng khi đang gõ.
 * Từ chối bắt buộc ghi lý do (note).
 */
import { useCallback, useEffect, useState } from "react";
import { useNavigate } from "react-router-dom";
import { getUser, handleAuthError } from "../auth/authService";
import HorseShell, { Card, StateBlock, StatusChip } from "./HorseShell";
import HorsePhoto from "./HorsePhoto";
import {
  toGender, formatDate, formatDateTime, formatAge, toStage, ageProblem, canReviewRequests,
} from "./horseApi";
import {
  listRequests, approveRequest, rejectRequest, toRequestStatus, toGrowth, SURFACES, DISTANCES,
  RUNNING_STYLES, getPricing, estimatePrice, formatVnd,
} from "./horseRequestApi";
import RegistrationCodeField from "./RegistrationCodeField";

function Basis({ label, children }) {
  return (
    <div className="hz-basis__item">
      <span>{label}</span>
      <strong>{children}</strong>
    </div>
  );
}

// Một dòng yêu cầu đang chờ, mỗi dòng có state riêng.
function PendingRow({ row, pricing, onDone }) {
  const navigate = useNavigate();
  const [code, setCode] = useState("");
  const [note, setNote] = useState("");
  const [photoChecked, setPhotoChecked] = useState(false);
  const [papersChecked, setPapersChecked] = useState(false);
  const [errors, setErrors] = useState({});
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState("");

  // Hồ sơ PEDIGREE chỉ để làm cha mẹ / tổ tiên trong phả hệ: không ở CLB, không hợp đồng.
  const pedigreeOnly = row.recordType === "PEDIGREE";
  const stage = toStage({ recordType: row.recordType, dateOfBirth: row.dateOfBirth });
  const age = formatAge(row.dateOfBirth);
  const ageWarning = ageProblem(row.recordType, row.dateOfBirth);
  const growth = toGrowth(row.growth);
  const estimate = estimatePrice(pricing, row.contractMonths);
  const noPhoto = !pedigreeOnly && !row.photoUrl;
  const trainerFull = row.preferredTrainerId && row.preferredTrainerLoad >= row.preferredTrainerMax;

  // Khung chạy chung cho duyệt / từ chối: khoá nút, gọi API, lỗi từng ô (mã trùng, chuồng đã có ngựa...) gắn
  // vào đúng ô nhờ fieldErrors, thành công thì onDone() tải lại danh sách.
  const run = async (fn) => {
    setBusy(true);
    setError("");
    try {
      await fn();
      onDone();
    } catch (err) {
      if (handleAuthError(err, navigate)) return;
      if (err.fieldErrors && Object.keys(err.fieldErrors).length > 0) setErrors(err.fieldErrors);
      else setError(err.message);
    } finally {
      setBusy(false);
    }
  };

  const approve = () => {
    const e = {};
    if (pedigreeOnly) {
      if (!code.trim()) e.registrationCode = "Enter the code the club assigns";
    } else {
      if (noPhoto) e.photo = "No photo yet: ask the owner for one";
      if (!photoChecked) e.photoChecked = "Confirm the photo matches the description";
      if (!papersChecked) e.papersChecked = "Confirm you checked the parents against the papers";
    }
    setErrors(e);
    if (Object.keys(e).length > 0) return;
    run(() => approveRequest(row.requestId, {
      registrationCode: pedigreeOnly ? code : null, note, photoChecked, papersChecked,
    }));
  };

  const reject = () => {
    if (!note.trim()) {
      setErrors({ note: "A reason is required when rejecting" });
      return;
    }
    setErrors({});
    run(() => rejectRequest(row.requestId, note));
  };

  return (
    <li className="hz-review">
      <div className="hz-review__info">
        <div className="hz-review__head">
          {!pedigreeOnly && <HorsePhoto horse={row} />}
          <div>
            <strong>{row.horseName}</strong>
            {row.returnOfHorseId && (
              <p><StatusChip tone="neutral" label={`Coming back · ${row.returnCode}`} /> Left the club earlier; same horse, same code</p>
            )}
            <p>
              {[toGender(row.horseGender) === "—" ? null : toGender(row.horseGender), row.breed, row.color,
                row.dateOfBirth ? `${formatDate(row.dateOfBirth)}${age ? ` (${age})` : ""}` : null]
                .filter(Boolean).join(" · ") || "No other details given"}
            </p>
            <p>From {row.ownerName} · sent {formatDateTime(row.createdAt)}</p>
            <p><StatusChip tone={stage.tone} label={stage.label} title={stage.hint} /></p>
          </div>
        </div>
        {noPhoto && <p className="hz-field__error">{errors.photo || "No photo yet: the request cannot be approved until the owner adds one."}</p>}
        {ageWarning && <p className="hz-field__error">{ageWarning}</p>}

        {/* Review feedback 2: what the approval is based on, in one place. */}
        {!pedigreeOnly && (
          <div className="hz-basis" aria-label="Review basis">
            <Basis label="Weight / height">
              {row.weight ? `${row.weight} kg` : "—"} / {row.heightCm ? `${row.heightCm} cm` : "—"}
            </Basis>
            <Basis label="Growth for age">
              <StatusChip tone={growth.tone} label={growth.label}
                          title="Weight and height against the breed standard for this age, up to 60 months. Body condition is scored on arrival." />
            </Basis>
            <Basis label="Surface">{SURFACES[row.surface] || "Not given"}</Basis>
            <Basis label="Distance">{DISTANCES[row.distancePref] || "Not given"}</Basis>
            <Basis label="Running style">{RUNNING_STYLES[row.runningStyle] || "Not given"}</Basis>
            <Basis label="Stay">
              {row.contractMonths ? `${row.contractMonths} month${row.contractMonths === 1 ? "" : "s"}` : "—"}
              {row.preferredStart ? ` · arrives ${formatDate(row.preferredStart)}` : ""}
            </Basis>
            <Basis label="Training asked">
              {row.trainingStart && row.trainingEnd
                ? `${formatDate(row.trainingStart)} - ${formatDate(row.trainingEnd)} (${row.trainingWeeks} weeks)`
                : row.trainingWeeks ? `${row.trainingWeeks} weeks` : "—"}
            </Basis>
            <Basis label="Contract value">{estimate ? formatVnd(estimate.total) : "—"}</Basis>
            <Basis label="Preferred trainer">
              {row.preferredTrainerName
                ? <>{row.preferredTrainerName} ({row.preferredTrainerLoad}/{row.preferredTrainerMax})
                    {trainerFull && <span className="hz-field__error"> now full</span>}</>
                : "No preference"}
            </Basis>
            <Basis label="Owner's horses at the club">{row.ownerActiveHorses}</Basis>
            <Basis label="Insurer">
              {row.insurerName ? `${row.insurerName}${row.insurerPhone ? ` · ${row.insurerPhone}` : ""}` : "Not given"}
            </Basis>
            <Basis label="Second contact">
              {row.emergencyName ? `${row.emergencyName}${row.emergencyPhone ? ` · ${row.emergencyPhone}` : ""}` : "Not given"}
            </Basis>
            <Basis label="Parents">
              {row.sireName || row.damName
                ? `Sire ${row.sireName || "unknown"} · Dam ${row.damName || "unknown"}`
                : "Not given"}
            </Basis>
          </div>
        )}
        {pedigreeOnly && (
          <p>
            {row.sireName || row.damName
              ? <>Sire <strong>{row.sireName || "unknown"}</strong> · Dam <strong>{row.damName || "unknown"}</strong> <span className="hz-muted">(check against the papers)</span></>
              : <span className="hz-muted">Parents not given</span>}
          </p>
        )}
        {(errors.sireId || errors.damId) && (
          <p className="hz-field__error">{[errors.sireId, errors.damId].filter(Boolean).join(" ")}</p>
        )}
        {row.note && <p className="hz-review__note">“{row.note}”</p>}
      </div>

      <div className="hz-review__form">
        {pedigreeOnly ? (
          <RegistrationCodeField id={`code-${row.requestId}`} value={code} onChange={setCode}
                                 error={errors.registrationCode} />
        ) : (
          <>
            <label className="hz-check">
              <input type="checkbox" checked={photoChecked} disabled={noPhoto}
                     onChange={(e) => setPhotoChecked(e.target.checked)} />
              The photo matches the description (colour, sex, markings)
            </label>
            {errors.photoChecked && <p className="hz-field__error">{errors.photoChecked}</p>}
            <label className="hz-check">
              <input type="checkbox" checked={papersChecked}
                     onChange={(e) => setPapersChecked(e.target.checked)} />
              I checked the parents against the papers
            </label>
            {errors.papersChecked && <p className="hz-field__error">{errors.papersChecked}</p>}
            <p className="hz-field__hint">
              Approving sends the owner a contract to sign and pay. The registration code, stall and
              Head Trainer are assigned when the horse arrives (Contracts).
            </p>
          </>
        )}

        <div className="hz-field">
          <label htmlFor={`note-${row.requestId}`}>Note to the owner (required to reject)</label>
          <textarea id={`note-${row.requestId}`} className="hz-textarea" rows={2} maxLength={255}
                    value={note} onChange={(e) => setNote(e.target.value)} />
          {errors.note && <p className="hz-field__error">{errors.note}</p>}
        </div>

        {error && <p className="hz-form-error" role="alert">{error}</p>}

        <div className="hz-form-footer">
          <button type="button" className="hz-btn" disabled={busy || noPhoto} onClick={approve}
                  title={noPhoto ? "The owner has not added a photo yet" : undefined}>
            {pedigreeOnly ? "Approve" : "Approve and send contract"}
          </button>
          <button type="button" className="hz-btn hz-btn--ghost" disabled={busy} onClick={reject}>
            Reject
          </button>
        </div>
      </div>
    </li>
  );
}

export default function HorseReviewPage() {
  const navigate = useNavigate();
  const user = getUser();
  const [rows, setRows] = useState(null);
  const [pricing, setPricing] = useState(null);
  const [error, setError] = useState("");

  // Tải song song danh sách yêu cầu và danh sách HLV; lỗi khi tải HLV không chặn trang (.catch(() => [])).
  const load = useCallback(
    (signal) => {
      setError("");
      Promise.all([listRequests(signal), getPricing(signal).catch(() => null)])
        .then(([reqs, price]) => {
          setRows(reqs);
          setPricing(price);
        })
        .catch((err) => {
          if (err.name === "AbortError") return;
          if (handleAuthError(err, navigate)) return;
          setError(err.message);
        });
    },
    [navigate]
  );

  useEffect(() => {
    const ctrl = new AbortController();
    load(ctrl.signal);
    return () => ctrl.abort();
  }, [load]);

  // Chặn trên giao diện nếu không phải Manager (backend vẫn trả 403 cho API duyệt).
  if (!canReviewRequests(user)) {
    return (
      <HorseShell title="Horse registrations">
        <StateBlock kind="error" title="You cannot review registrations" />
      </HorseShell>
    );
  }

  const pending = (rows || []).filter((r) => r.status === "PENDING");
  const handled = (rows || []).filter((r) => r.status !== "PENDING");

  return (
    <HorseShell
      title="Horse registrations"
      subtitle="Requests sent by horse owners, waiting for the club"
      actions={
        <button type="button" className="hz-btn hz-btn--ghost" onClick={() => navigate("/manager/contracts")}>
          Contracts
        </button>
      }
    >
      {error && <StateBlock kind="error" title={error} onRetry={() => load()} />}
      {!rows && !error && <StateBlock title="Loading…" />}

      {rows && (
        <>
          <Card title="Waiting for review" hint={`${pending.length} pending`}>
            {pending.length === 0 ? (
              <p className="hz-empty">Nothing is waiting.</p>
            ) : (
              <ul className="hz-list">
                {pending.map((r) => (
                  <PendingRow key={r.requestId} row={r} pricing={pricing} onDone={() => load()} />
                ))}
              </ul>
            )}
          </Card>

          <Card title="Already handled" hint={`${handled.length} requests`}>
            {handled.length === 0 ? (
              <p className="hz-empty">No request has been handled yet.</p>
            ) : (
              <ul className="hz-list">
                {handled.map((r) => {
                  const st = toRequestStatus(r.status, r.contractStatus);
                  return (
                    <li key={r.requestId}>
                      <div>
                        <strong>{r.horseName}</strong>
                        <p>{r.ownerName} · {r.reviewerName && r.status !== "CANCELLED" ? `by ${r.reviewerName}` : ""} {formatDateTime(r.reviewedAt)}</p>
                        {r.reviewNote && <p className="hz-reject-note">{r.reviewNote}</p>}
                      </div>
                      <div className="hz-row-action">
                        <StatusChip tone={st.tone} label={st.label} />
                        {r.contractId && (
                          <button type="button" className="hz-btn hz-btn--ghost" style={{ marginLeft: 8 }}
                                  onClick={() => navigate(`/manager/contracts?open=${r.contractId}`)}>
                            Contract
                          </button>
                        )}
                      </div>
                    </li>
                  );
                })}
              </ul>
            )}
          </Card>
        </>
      )}
    </HorseShell>
  );
}
