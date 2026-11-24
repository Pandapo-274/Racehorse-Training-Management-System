// src/features/horse/HorseRequestPage.js
// Chủ ngựa xin học viện đăng ký một con ngựa, và xem lại những yêu cầu đã gửi.
//
// Biểu mẫu này cố ý ÍT ô hơn biểu mẫu đăng ký ngựa của học viện. Không có mã
// đăng ký và không có số chuồng: mã do học viện cấp, chuồng do học viện xếp.
// Cho chủ ngựa tự điền hai thứ đó là để người ngoài tự đặt số hiệu trong sổ
// của học viện.
//
// Cũng chỉ mỗi tên là bắt buộc. Người đang muốn gửi ngựa thường chưa cầm đủ
// giấy tờ, mà bắt khai đủ thì chặn đúng việc màn hình này sinh ra để mở.
import { useCallback, useEffect, useMemo, useState } from "react";
import { useNavigate } from "react-router-dom";
import { handleAuthError } from "../auth/authService";
import { useAppDialog } from "../common/AppDialog";
import HorseShell, { Card, StateBlock, StatusChip } from "./HorseShell";
import {
  GENDER_OPTIONS, toGender, formatDate, formatDateTime, ageProblem, toStage, RECORD_TYPES,
  listParentCandidates, parentLabel, SIRE_GENDERS, DAM_GENDERS,
} from "./horseApi";
import {
  listRequests, submitRequest, cancelRequest, toRequestStatus, uploadRequestPhoto, listTrainerOptions,
  getPricing, estimatePrice, formatVnd, stayProblems, BREED_PRESETS, SURFACES, DISTANCES, RUNNING_STYLES, PHONE_PATTERN,
  CONTRACT_MONTH_OPTIONS, STAY_RULES, isoAddDays, isoAddMonths, trainingWeeksOf,
} from "./horseRequestApi";
import HorsePhoto from "./HorsePhoto";

// Giá trị ban đầu của form. recordType mặc định ACADEMY (ngựa vào CLB huấn luyện); PEDIGREE = chỉ khai báo
// để làm cha mẹ trong phả hệ.
const EMPTY = {
  horseName: "", breed: "", horseGender: "", dateOfBirth: "", color: "", note: "",
  recordType: "ACADEMY", sireId: "", damId: "",
  // V48 (review feedback): what the club needs to approve a club horse.
  breedChoice: "", weight: "", heightCm: "", surface: "", distancePref: "", runningStyle: "",
  preferredStart: "", contractMonths: "1", preferredTrainerId: "",
  // V50: the training window asked for (from / until), at least 3 weeks, inside the stay.
  trainingStart: "", trainingEnd: "",
  // V54: contract clause 8.
  insurerName: "", insurerPhone: "", emergencyName: "", emergencyPhone: "",
};

const PHOTO_TYPES = "image/jpeg,image/png,image/webp";

function todayIso() {
  const d = new Date();
  const p = (n) => String(n).padStart(2, "0");
  return `${d.getFullYear()}-${p(d.getMonth() + 1)}-${p(d.getDate())}`;
}

// Kiểm ở trình duyệt, cùng luật với backend (độ dài, ngày sinh không ở tương lai, tuổi theo loại hồ sơ).
function validate(form, photo) {
  const e = {};
  if (!form.horseName.trim()) e.horseName = "Tell us the horse's name";
  else if (form.horseName.trim().length > 100) e.horseName = "100 characters at most";
  if (form.breed.length > 50) e.breed = "50 characters at most";
  if (form.color.length > 50) e.color = "50 characters at most";
  if (form.note.length > 500) e.note = "500 characters at most";
  if (form.sireId && form.sireId === form.damId) e.damId = "Sire and dam cannot be the same horse";
  // So sánh theo chuỗi yyyy-mm-dd để khỏi dính múi giờ.
  if (form.dateOfBirth && form.dateOfBirth > todayIso()) {
    e.dateOfBirth = "Date of birth cannot be in the future";
  }
  // Ngựa vào học viện cần ngày sinh và không được quá già. Ngựa quá già vẫn gửi
  // được, nhưng dưới dạng hồ sơ pedigree (chỉ để làm cha mẹ / tổ tiên).
  if (!e.dateOfBirth) {
    const problem = ageProblem(form.recordType, form.dateOfBirth);
    if (problem) e.dateOfBirth = problem;
  }
  if (form.recordType === "ACADEMY") {
    if (form.breedChoice === "OTHER" && !form.breed.trim()) e.breed = "Type the breed";
    const w = Number(form.weight);
    if (!form.weight) e.weight = "Weight is required for a club horse";
    else if (w < 30 || w > 1000) e.weight = "Between 30 and 1000 kg";
    const h = Number(form.heightCm);
    if (!form.heightCm) e.heightCm = "Height is required for a club horse";
    else if (h < 60 || h > 200) e.heightCm = "Between 60 and 200 cm";
    if (!photo) e.photo = "Add a photo: the club cannot approve a horse it has not seen";
    Object.assign(e, stayProblems(form, todayIso()));
    if (!form.insurerName.trim()) e.insurerName = "Name the horse's insurer";
    if (!form.insurerPhone.trim()) e.insurerPhone = "Give the insurer's emergency number";
    else if (!PHONE_PATTERN.test(form.insurerPhone.trim())) e.insurerPhone = "Enter a valid phone number";
    if (!form.emergencyName.trim()) e.emergencyName = "Name a second person to call";
    if (!form.emergencyPhone.trim()) e.emergencyPhone = "Give that person's phone number";
    else if (!PHONE_PATTERN.test(form.emergencyPhone.trim())) e.emergencyPhone = "Enter a valid phone number";
  }
  return e;
}

function Field({ id, label, error, hint, children }) {
  return (
    <div className="hz-field">
      <label htmlFor={id}>{label}</label>
      {children}
      {error ? <p className="hz-field__error">{error}</p>
        : hint ? <p className="hz-field__hint">{hint}</p> : null}
    </div>
  );
}

/*
 * Trang /horse-owner/request của chủ ngựa: phần trên là form gửi yêu cầu đăng ký (POST /api/horse-requests),
 * phần dưới là danh sách yêu cầu đã gửi với trạng thái PENDING / APPROVED / REJECTED / CANCELLED.
 * Yêu cầu còn PENDING thì chủ tự huỷ được (PUT /api/horse-requests/{id}/cancel), không cần Manager.
 */
export default function HorseRequestPage() {
  const navigate = useNavigate();
  const dialog = useAppDialog();

  const [form, setForm] = useState(EMPTY);
  const [photo, setPhoto] = useState(null); // File chọn cho yêu cầu đang soạn
  const [photoPreview, setPhotoPreview] = useState("");
  const [trainers, setTrainers] = useState([]);
  const [pricing, setPricing] = useState(null);
  const [photoBusyId, setPhotoBusyId] = useState(null);
  const [errors, setErrors] = useState({});
  const [submitError, setSubmitError] = useState("");
  const [sending, setSending] = useState(false);
  const [rows, setRows] = useState(null);
  const [loadError, setLoadError] = useState("");
  const [cancellingId, setCancellingId] = useState(null);
  const [listError, setListError] = useState("");
  // V41: parents come from the papers; candidates = club pedigree records + my own horses.
  const [herd, setHerd] = useState([]);
  useEffect(() => {
    const ctrl = new AbortController();
    listParentCandidates(ctrl.signal).then((h) => setHerd(h || [])).catch(() => {});
    listTrainerOptions(ctrl.signal).then((t) => setTrainers(t || [])).catch(() => {});
    getPricing(ctrl.signal).then(setPricing).catch(() => {});
    return () => ctrl.abort();
  }, []);

  // Xem trước ảnh vừa chọn; thu hồi URL tạm khi đổi ảnh để khỏi rò bộ nhớ.
  useEffect(() => {
    if (!photo) { setPhotoPreview(""); return undefined; }
    const url = URL.createObjectURL(photo);
    setPhotoPreview(url);
    return () => URL.revokeObjectURL(url);
  }, [photo]);

  const estimate = estimatePrice(pricing, form.contractMonths);
  // V50: the stay ends arrival + months; training lasts at least 3 weeks and ends inside it.
  const stayEnd = form.preferredStart && form.contractMonths
    ? isoAddMonths(form.preferredStart, form.contractMonths) : null;
  const minTrainingEnd = form.trainingStart
    ? isoAddDays(form.trainingStart, STAY_RULES.minTrainingWeeks * 7) : null;
  const weeks = trainingWeeksOf(form.trainingStart, form.trainingEnd);
  // useMemo: chỉ lọc lại danh sách cha / mẹ khi herd đổi, không lọc lại ở mỗi lần render.
  const sires = useMemo(() => herd.filter((h) => SIRE_GENDERS.has(h.horseGender)), [herd]);
  const dams = useMemo(() => herd.filter((h) => DAM_GENDERS.has(h.horseGender)), [herd]);

  const load = useCallback(
    (signal) => {
      setLoadError("");
      listRequests(signal)
        .then(setRows)
        .catch((err) => {
          if (err.name === "AbortError") return;
          if (handleAuthError(err, navigate)) return;
          setLoadError(err.message);
        });
    },
    [navigate]
  );

  useEffect(() => {
    const ctrl = new AbortController();
    load(ctrl.signal);
    return () => ctrl.abort();
  }, [load]);

  const change = (e) => {
    const { name, value } = e.target;
    setForm((prev) => ({ ...prev, [name]: value }));
    if (errors[name]) setErrors((prev) => ({ ...prev, [name]: undefined }));
  };

  // Chọn ngày mang ngựa tới: nếu chưa chọn ngày bắt đầu huấn luyện thì gợi ý luôn ngày đó.
  const changeArrival = (e) => {
    const { value } = e.target;
    setForm((prev) => ({
      ...prev,
      preferredStart: value,
      trainingStart: !prev.trainingStart || prev.trainingStart < value ? value : prev.trainingStart,
    }));
    setErrors((prev) => ({ ...prev, preferredStart: undefined, trainingStart: undefined }));
  };

  // Chọn ngày bắt đầu: nếu chưa có ngày kết thúc (hoặc quá ngắn) thì gợi ý đủ 4 tuần.
  const changeTrainingStart = (e) => {
    const { value } = e.target;
    setForm((prev) => {
      const min = value ? isoAddDays(value, STAY_RULES.minTrainingWeeks * 7) : "";
      const suggested = value ? isoAddDays(value, 28) : "";
      return {
        ...prev,
        trainingStart: value,
        trainingEnd: !prev.trainingEnd || prev.trainingEnd < min ? suggested : prev.trainingEnd,
      };
    });
    setErrors((prev) => ({ ...prev, trainingStart: undefined, trainingEnd: undefined }));
  };

  // Chọn giống có sẵn thì điền luôn mặt đường, cự ly, kiểu chạy (owner vẫn sửa được).
  const chooseBreed = (e) => {
    const choice = e.target.value;
    const preset = BREED_PRESETS[choice];
    setForm((prev) => ({
      ...prev,
      breedChoice: choice,
      // "Other": keep what was typed before, but not a preset name left over from the list.
      breed: choice === "OTHER" ? (BREED_PRESETS[prev.breed] ? "" : prev.breed) : choice,
      ...(preset || {}),
    }));
    if (errors.breed) setErrors((prev) => ({ ...prev, breed: undefined }));
  };

  const choosePhoto = (e) => {
    const file = e.target.files?.[0] || null;
    setPhoto(file);
    if (errors.photo) setErrors((prev) => ({ ...prev, photo: undefined }));
  };

  // Thêm / thay ảnh cho một yêu cầu đã gửi mà còn chờ duyệt.
  const replacePhoto = async (r, file) => {
    if (!file) return;
    setPhotoBusyId(r.requestId);
    setListError("");
    try {
      await uploadRequestPhoto(r.requestId, file);
      load();
    } catch (err) {
      if (handleAuthError(err, navigate)) return;
      setListError(err.fieldErrors?.file || err.message);
    } finally {
      setPhotoBusyId(null);
    }
  };

  // Gửi yêu cầu: validate -> submitRequest -> xoá form và tải lại danh sách. Manager nhận thông báo HORSE_REQUEST.
  const submit = async (e) => {
    e.preventDefault();

    const found = validate(form, form.recordType === "ACADEMY" ? photo : true);
    setErrors(found);
    if (Object.keys(found).length > 0) return;

    setSending(true);
    setSubmitError("");

    try {
      const saved = await submitRequest(form);
      if (form.recordType === "ACADEMY" && photo) {
        try {
          await uploadRequestPhoto(saved.requestId, photo);
        } catch (err) {
          // The request is saved; only the photo failed. Say so and let the owner retry from the list.
          setListError(`Request sent, but the photo was not saved: ${err.fieldErrors?.file || err.message}. Add it again below.`);
        }
      }
      setForm(EMPTY);
      setPhoto(null);
      load(); // nạp lại để yêu cầu vừa gửi hiện ngay trong danh sách bên dưới
    } catch (err) {
      if (handleAuthError(err, navigate)) return;
      const fieldErrors = err.fieldErrors || {};
      if (Object.keys(fieldErrors).length > 0) setErrors(fieldErrors);
      else setSubmitError(err.message);
    } finally {
      setSending(false);
    }
  };

  /* Huỷ yêu cầu còn chờ. Hỏi lại một lần trước khi gửi: huỷ xong không mở
     lại được, chỉ gửi yêu cầu mới. */
  const cancel = async (r) => {
    const ok = await dialog.confirm({
      title: `Cancel ${r.horseName}?`,
      message: "The request is withdrawn from the club's queue. You can send it again later from this page.",
      confirmLabel: "Cancel request",
      cancelLabel: "Keep it",
      danger: true,
    });
    if (!ok) return;
    setCancellingId(r.requestId);
    setListError("");
    try {
      await cancelRequest(r.requestId);
      load();
    } catch (err) {
      if (handleAuthError(err, navigate)) return;
      setListError(err.message);
      load(); // có thể Club Manager vừa xử lý xong, nạp lại cho đúng trạng thái
    } finally {
      setCancellingId(null);
    }
  };

  // A rejected or cancelled request cannot be reopened (its review is history),
  // but the owner should not have to type everything again: copy it into the
  // form, fix what the club's note asked for, and send it as a new request.
  const resubmit = (r) => {
    setForm({
      horseName: r.horseName || "",
      breed: r.breed || "",
      horseGender: r.horseGender || "",
      dateOfBirth: r.dateOfBirth || "",
      color: r.color || "",
      note: r.note || "",
      recordType: r.recordType || EMPTY.recordType,
      sireId: r.sireId ? String(r.sireId) : "",
      damId: r.damId ? String(r.damId) : "",
      breedChoice: !r.breed ? "" : BREED_PRESETS[r.breed] ? r.breed : "OTHER",
      weight: r.weight ?? "",
      heightCm: r.heightCm ?? "",
      surface: r.surface || "",
      distancePref: r.distancePref || "",
      runningStyle: r.runningStyle || "",
      // Dates of an old request are likely past: the owner picks them again.
      preferredStart: "",
      trainingStart: "",
      trainingEnd: "",
      contractMonths: r.contractMonths ? String(r.contractMonths) : EMPTY.contractMonths,
      preferredTrainerId: r.preferredTrainerId ? String(r.preferredTrainerId) : "",
      insurerName: r.insurerName || "",
      insurerPhone: r.insurerPhone || "",
      emergencyName: r.emergencyName || "",
      emergencyPhone: r.emergencyPhone || "",
    });
    setErrors({});
    setSubmitError("");
    window.scrollTo({ top: 0, behavior: "smooth" });
  };

  return (
    <HorseShell
      title="Register a horse"
      subtitle="Ask the club to add a horse to your name"
      actions={
        <button type="button" className="hz-btn hz-btn--ghost"
                onClick={() => navigate("/horse-owner/horses")}>
          Back to my horses
        </button>
      }
    >
      <form onSubmit={submit} noValidate>
        <Card
          title="About the horse"
          hint="Club horses need a date of birth, weight, height, a photo and the length of the stay"
        >
          <div className="hz-form-grid">
            <Field id="horseName" label="Name" error={errors.horseName}>
              <input id="horseName" name="horseName" value={form.horseName}
                     onChange={change} maxLength={100} autoComplete="off"
                     aria-invalid={!!errors.horseName} />
            </Field>

            <Field
              id="recordType"
              label="What is this record for?"
              hint={
                form.recordType === "PEDIGREE"
                  ? "Kept only as a parent or ancestor. No stall, no training, no health checks."
                  : "A horse that will live and train at the club."
              }
            >
              <select id="recordType" name="recordType" value={form.recordType} onChange={change}>
                {Object.entries(RECORD_TYPES).map(([k, label]) => (
                  <option key={k} value={k}>{label}</option>
                ))}
              </select>
            </Field>

            <Field id="horseGender" label="Sex" error={errors.horseGender}>
              <select id="horseGender" name="horseGender" value={form.horseGender}
                      onChange={change}>
                <option value="">Not sure</option>
                {GENDER_OPTIONS.map((g) => (
                  <option key={g} value={g}>{toGender(g)}</option>
                ))}
              </select>
            </Field>

            <Field
              id="dateOfBirth"
              label={form.recordType === "PEDIGREE" ? "Date of birth" : "Date of birth (required)"}
              error={errors.dateOfBirth}
              hint="Club horses must be 25 or younger. Older horses can be sent as pedigree records."
            >
              <input id="dateOfBirth" name="dateOfBirth" type="date"
                     value={form.dateOfBirth} onChange={change}
                     aria-invalid={!!errors.dateOfBirth} />
            </Field>

            <Field id="breedChoice" label="Breed" error={errors.breed}
                   hint={form.breedChoice && form.breedChoice !== "OTHER"
                     ? "Surface, distance and running style below were filled in for this breed. Change them if your horse differs."
                     : undefined}>
              <select id="breedChoice" name="breedChoice" value={form.breedChoice} onChange={chooseBreed}>
                <option value="">Not sure</option>
                {Object.keys(BREED_PRESETS).map((b) => <option key={b} value={b}>{b}</option>)}
                <option value="OTHER">Other (type it)</option>
              </select>
              {form.breedChoice === "OTHER" && (
                <input id="breed" name="breed" value={form.breed} onChange={change} maxLength={50}
                       placeholder="Breed name" aria-label="Breed name" style={{ marginTop: 8 }} />
              )}
            </Field>

            <Field id="color" label="Colour" error={errors.color}>
              <input id="color" name="color" value={form.color} onChange={change}
                     maxLength={50} />
            </Field>
          </div>

          <div className="hz-form-grid">
            <Field id="sireId" label="Sire (father), from the papers" error={errors.sireId}
                   hint="A club pedigree record or one of your own horses. Leave Unknown if it is not listed.">
              <select id="sireId" name="sireId" value={form.sireId} onChange={change}>
                <option value="">Unknown</option>
                {sires.map((h) => <option key={h.horseId} value={h.horseId}>{parentLabel(h)}</option>)}
              </select>
            </Field>
            <Field id="damId" label="Dam (mother), from the papers" error={errors.damId}>
              <select id="damId" name="damId" value={form.damId} onChange={change}>
                <option value="">Unknown</option>
                {dams.map((h) => <option key={h.horseId} value={h.horseId}>{parentLabel(h)}</option>)}
              </select>
            </Field>
          </div>

          {form.recordType === "ACADEMY" && (
            <>
              <h3 className="hz-subhead">Measures and photo</h3>
              <div className="hz-form-grid">
                <Field id="weight" label="Weight (kg)" error={errors.weight}>
                  <input id="weight" name="weight" type="number" min="30" max="1000" step="0.5"
                         value={form.weight} onChange={change} aria-invalid={!!errors.weight} />
                </Field>
                <Field id="heightCm" label="Height at the withers (cm)" error={errors.heightCm}>
                  <input id="heightCm" name="heightCm" type="number" min="60" max="200" step="0.5"
                         value={form.heightCm} onChange={change} aria-invalid={!!errors.heightCm} />
                </Field>
              </div>
              <div className="hz-field">
                <label htmlFor="photo">Photo of the horse (required)</label>
                <div className="hz-photo-editor">
                  {photoPreview
                    ? <img className="hz-photo hz-photo--sm" src={photoPreview} alt="Selected horse" />
                    : <div className="hz-photo hz-photo--sm hz-photo--empty" aria-hidden="true">?</div>}
                  <input id="photo" type="file" accept={PHOTO_TYPES} onChange={choosePhoto} />
                </div>
                {errors.photo ? <p className="hz-field__error">{errors.photo}</p>
                  : <p className="hz-field__hint">JPEG, PNG or WebP, up to 2 MB. The club checks it against the description.</p>}
              </div>

              <h3 className="hz-subhead">Aptitude</h3>
              <div className="hz-form-grid">
                <Field id="surface" label="Surface">
                  <select id="surface" name="surface" value={form.surface} onChange={change}>
                    <option value="">Not sure</option>
                    {Object.entries(SURFACES).map(([k, v]) => <option key={k} value={k}>{v}</option>)}
                  </select>
                </Field>
                <Field id="distancePref" label="Distance">
                  <select id="distancePref" name="distancePref" value={form.distancePref} onChange={change}>
                    <option value="">Not sure</option>
                    {Object.entries(DISTANCES).map(([k, v]) => <option key={k} value={k}>{v}</option>)}
                  </select>
                </Field>
                <Field id="runningStyle" label="Running style">
                  <select id="runningStyle" name="runningStyle" value={form.runningStyle} onChange={change}>
                    <option value="">Not sure</option>
                    {Object.entries(RUNNING_STYLES).map(([k, v]) => <option key={k} value={k}>{v}</option>)}
                  </select>
                </Field>
              </div>

              <h3 className="hz-subhead">Stay and training</h3>
              <div className="hz-form-grid">
                <Field id="preferredStart" label="Arrival day" error={errors.preferredStart}
                       hint="The day you bring the horse in. The stay is counted from the day the club receives it.">
                  <input id="preferredStart" name="preferredStart" type="date" min={todayIso()}
                         value={form.preferredStart} onChange={changeArrival}
                         aria-invalid={!!errors.preferredStart} />
                </Field>
                <Field id="contractMonths" label="Stay at the club" error={errors.contractMonths}
                       hint={stayEnd ? `Until ${formatDate(stayEnd)} if the horse arrives on that day.` : undefined}>
                  <select id="contractMonths" name="contractMonths" value={form.contractMonths} onChange={change}>
                    {CONTRACT_MONTH_OPTIONS.map((m) => (
                      <option key={m} value={m}>{m} month{m === 1 ? "" : "s"}</option>
                    ))}
                  </select>
                </Field>
                <Field id="trainingStart" label="Training from" error={errors.trainingStart}>
                  <input id="trainingStart" name="trainingStart" type="date"
                         min={form.preferredStart || todayIso()} max={stayEnd || undefined}
                         value={form.trainingStart} onChange={changeTrainingStart}
                         aria-invalid={!!errors.trainingStart} />
                </Field>
                <Field id="trainingEnd" label="Training until" error={errors.trainingEnd}
                       hint={weeks != null && !errors.trainingEnd
                         ? `${weeks} week${weeks === 1 ? "" : "s"} of training`
                         : `At least ${STAY_RULES.minTrainingWeeks} weeks, inside the stay.`}>
                  <input id="trainingEnd" name="trainingEnd" type="date"
                         min={minTrainingEnd || undefined} max={stayEnd || undefined}
                         value={form.trainingEnd} onChange={change}
                         aria-invalid={!!errors.trainingEnd} />
                </Field>
                <Field id="preferredTrainerId" label="Preferred Head Trainer" error={errors.preferredTrainerId}
                       hint="Only trainers who can take another horse are listed. The club confirms it on arrival.">
                  <select id="preferredTrainerId" name="preferredTrainerId" value={form.preferredTrainerId}
                          onChange={change}>
                    <option value="">No preference</option>
                    {trainers.map((t) => <option key={t.userId} value={t.userId}>{t.fullName}</option>)}
                  </select>
                </Field>
              </div>
              <h3 className="hz-subhead">Insurance and emergency contact</h3>
              <div className="hz-form-grid">
                <Field id="insurerName" label="Insurer" error={errors.insurerName}>
                  <input id="insurerName" name="insurerName" value={form.insurerName} onChange={change}
                         maxLength={100} aria-invalid={!!errors.insurerName} />
                </Field>
                <Field id="insurerPhone" label="Insurer's emergency number" error={errors.insurerPhone}>
                  <input id="insurerPhone" name="insurerPhone" type="tel" value={form.insurerPhone} onChange={change}
                         maxLength={20} aria-invalid={!!errors.insurerPhone} />
                </Field>
                <Field id="emergencyName" label="Second person to call" error={errors.emergencyName}
                       hint="Someone who can decide for the horse if the club cannot reach you.">
                  <input id="emergencyName" name="emergencyName" value={form.emergencyName} onChange={change}
                         maxLength={100} aria-invalid={!!errors.emergencyName} />
                </Field>
                <Field id="emergencyPhone" label="Their phone number" error={errors.emergencyPhone}>
                  <input id="emergencyPhone" name="emergencyPhone" type="tel" value={form.emergencyPhone} onChange={change}
                         maxLength={20} aria-invalid={!!errors.emergencyPhone} />
                </Field>
              </div>
              {estimate && (
                <p className="hz-estimate">
                  Contract value: <strong>{formatVnd(estimate.total)}</strong>
                  <span className="hz-muted">
                    {" "}({formatVnd(estimate.monthlyFee)} × {estimate.months}
                    {estimate.discountPct > 0 ? `, ${estimate.discountPct}% off` : ""})
                  </span>
                </p>
              )}
            </>
          )}

          <Field
            id="note"
            label="Anything the club should know"
            error={errors.note}
            hint="Where the horse is now, when it can arrive, papers still on the way…"
          >
            <textarea id="note" name="note" value={form.note} onChange={change}
                      maxLength={500} rows={3} className="hz-textarea" />
          </Field>

          {/* Nói trước điều gì sẽ xảy ra. Không có dòng này thì người gửi chờ
              một con ngựa xuất hiện ngay trong danh sách, và tưởng hỏng. */}
          <div className="hz-note-box">
            <strong>What happens next</strong>
            <p>
              The club manager reviews the photo, measures and papers. If approved, you receive a
              contract for the stay: sign it online and pay through payOS (bank QR). The club then
              receives the horse, assigns its registration code, stall and Head Trainer, and the
              stay starts that day. Pedigree records skip the contract: the club issues the code
              straight away.
            </p>
          </div>

          {submitError && <p className="hz-form-error" role="alert">{submitError}</p>}

          <div className="hz-form-footer">
            <button type="submit" className="hz-btn" disabled={sending}>
              {sending ? "Sending…" : "Send request"}
            </button>
          </div>
        </Card>
      </form>

      <Card
        title="Your requests"
        hint={rows ? `${rows.length} sent so far` : undefined}
      >
        {loadError ? (
          <StateBlock kind="error" title={loadError} onRetry={() => load()} />
        ) : !rows ? (
          <StateBlock title="Loading…" />
        ) : rows.length === 0 ? (
          <p className="hz-empty">You have not sent a request yet.</p>
        ) : (
          <>
          {listError && <p className="hz-form-error" role="alert">{listError}</p>}
          <ul className="hz-list">
            {rows.map((r) => {
              const st = toRequestStatus(r.status, r.contractStatus);
              return (
                <li key={r.requestId}>
                  {r.recordType !== "PEDIGREE" && <HorsePhoto horse={r} className="hz-photo--sm" />}
                  <div>
                    <strong>{r.horseName}</strong>
                    <p>{toStage({ recordType: r.recordType, dateOfBirth: r.dateOfBirth }).label}</p>
                    <p>
                      {[toGender(r.horseGender) === "—" ? null : toGender(r.horseGender),
                        r.breed,
                        r.dateOfBirth ? formatDate(r.dateOfBirth) : null]
                        .filter(Boolean)
                        .join(" · ") || "No other details given"}
                    </p>
                    {(r.sireName || r.damName) && (
                      <p>Sire {r.sireName || "unknown"} · Dam {r.damName || "unknown"}</p>
                    )}
                    {r.recordType !== "PEDIGREE" && r.contractMonths && (
                      <p>
                        Stay {r.contractMonths} month{r.contractMonths === 1 ? "" : "s"}
                        {r.trainingStart && r.trainingEnd
                          ? ` · training ${formatDate(r.trainingStart)} - ${formatDate(r.trainingEnd)} (${r.trainingWeeks} weeks)`
                          : r.trainingWeeks ? ` · training ${r.trainingWeeks} weeks` : ""}
                      </p>
                    )}
                    <p>Sent {formatDateTime(r.createdAt)}</p>
                    {r.status === "PENDING" && r.recordType !== "PEDIGREE" && !r.photoUrl && (
                      <p className="hz-field__error">No photo yet: the club cannot approve it until you add one.</p>
                    )}

                    {/* Lý do từ chối phải hiện ra. Không có nó thì lần gửi lại
                        sẽ hỏng y như lần đầu. */}
                    {(r.status === "REJECTED" || r.status === "CANCELLED") && r.reviewNote && (
                      <p className="hz-reject-note">Club: {r.reviewNote}</p>
                    )}
                  </div>

                  <div className="hz-row-action">
                    <StatusChip tone={st.tone} label={st.label} />
                    {r.status === "PENDING" && r.recordType !== "PEDIGREE" && (
                      <label className="hz-btn hz-btn--ghost" style={{ marginLeft: 8 }}>
                        {photoBusyId === r.requestId ? "Uploading…" : r.photoUrl ? "Replace photo" : "Add photo"}
                        <input type="file" accept={PHOTO_TYPES} hidden
                               disabled={photoBusyId === r.requestId}
                               onChange={(e) => replacePhoto(r, e.target.files?.[0])} />
                      </label>
                    )}
                    {r.status === "PENDING" && (
                      <button
                        type="button"
                        className="hz-btn hz-btn--ghost"
                        style={{ marginLeft: 8 }}
                        disabled={cancellingId === r.requestId}
                        onClick={() => cancel(r)}
                      >
                        {cancellingId === r.requestId ? "Cancelling…" : "Cancel request"}
                      </button>
                    )}
                    {r.contractId && (
                      <button
                        type="button"
                        className="hz-btn"
                        style={{ marginLeft: 8 }}
                        onClick={() => navigate(`/horse-owner/contracts/${r.contractId}`)}
                      >
                        {r.contractStatus === "SENT" ? "Read and sign" : r.contractStatus === "SIGNED" ? "Pay" : "Open contract"}
                      </button>
                    )}
                    {(r.status === "REJECTED" || r.status === "CANCELLED") && (
                      <button
                        type="button"
                        className="hz-btn hz-btn--ghost"
                        style={{ marginLeft: 8 }}
                        onClick={() => resubmit(r)}
                      >
                        Submit again
                      </button>
                    )}
                  </div>
                </li>
              );
            })}
          </ul>
          </>
        )}
      </Card>
    {dialog.element}
    </HorseShell>
  );
}