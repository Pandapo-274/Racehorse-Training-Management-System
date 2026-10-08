// src/features/horse/HorseFormPage.js
// UC7 - tạo mới và sửa hồ sơ ngựa. Một biểu mẫu dùng cho cả hai, khác nhau ở
// chỗ có `id` trên đường dẫn hay không.
//
// Mỗi luật kiểm tra dưới đây là bản sao của một ràng buộc trong HorseRequest
// hoặc HorseService. Kiểm ở trình duyệt chỉ để người dùng biết sớm - máy chủ
// vẫn kiểm lại, và khi nó trả 400 thì `errors` của nó ghi đè luôn lỗi tại chỗ,
// vì chỉ máy chủ mới biết những thứ như "mã đăng ký đã tồn tại".
import { useCallback, useEffect, useMemo, useState } from "react";
import { useNavigate, useParams, Link } from "react-router-dom";
import { getUser, handleAuthError } from "../auth/authService";
import HorseShell, { Card, StateBlock } from "./HorseShell";
import {
  listHorses, getHorse, createHorse, updateHorse, canEditHorses,
<<<<<<< HEAD
  ownersFromHorses, GENDER_OPTIONS, toGender, SIRE_GENDERS, DAM_GENDERS,
=======
  ownersFromHorses, getHorseBasePath, GENDER_OPTIONS, toGender, SIRE_GENDERS, DAM_GENDERS,
>>>>>>> 916da958dc05d2885df8052c0aed68e2296d7f72
} from "./horseApi";

const EMPTY = {
  ownerId: "", sireId: "", damId: "", horseName: "", registrationCode: "",
  breed: "", horseGender: "", dateOfBirth: "", color: "", weight: "", stallNo: "",
};

function validate(form, selfId) {
  const e = {};

  if (!String(form.ownerId).trim()) {
    e.ownerId = "Pick an owner";
  } else if (!/^\d+$/.test(String(form.ownerId).trim())) {
    e.ownerId = "Owner id must be a number";
  }

  if (!form.horseName.trim()) e.horseName = "Name is required";
  else if (form.horseName.trim().length > 100) e.horseName = "100 characters at most";

  if (!form.registrationCode.trim()) e.registrationCode = "Registration code is required";
  else if (form.registrationCode.trim().length > 30) e.registrationCode = "30 characters at most";

  if (form.breed.length > 50) e.breed = "50 characters at most";
  if (form.color.length > 50) e.color = "50 characters at most";
  if (form.stallNo.length > 20) e.stallNo = "20 characters at most";

  if (form.horseGender && !GENDER_OPTIONS.includes(form.horseGender)) {
    e.horseGender = "Pick one of the five values";
  }

  // @PastOrPresent. So sánh theo chuỗi yyyy-mm-dd để khỏi dính múi giờ: new
  // Date("2026-10-08") là nửa đêm UTC, ở Hà Nội đã là 7 giờ sáng hôm đó.
  if (form.dateOfBirth) {
    const today = new Date();
    const iso = `${today.getFullYear()}-${String(today.getMonth() + 1).padStart(2, "0")}-${String(
      today.getDate()
    ).padStart(2, "0")}`;
    if (form.dateOfBirth > iso) e.dateOfBirth = "Date of birth cannot be in the future";
  }

  if (String(form.weight).trim()) {
    const w = Number(form.weight);
    if (!Number.isFinite(w) || w <= 0) e.weight = "Weight must be greater than 0";
  }

  // Ba luật phả hệ, chép từ checkParent trong HorseService.
  if (form.sireId && String(form.sireId) === String(selfId)) {
    e.sireId = "A horse cannot be its own sire";
  }
  if (form.damId && String(form.damId) === String(selfId)) {
    e.damId = "A horse cannot be its own dam";
  }
  if (form.sireId && form.sireId === form.damId) {
    e.damId = "Sire and dam cannot be the same horse";
  }

  return e;
}

/** Chuỗi rỗng -> null, khớp blankToNull của HorseService. */
function toPayload(form) {
  const n = (v) => (String(v).trim() === "" ? null : Number(v));
  const s = (v) => (v.trim() === "" ? null : v.trim());

  return {
    ownerId: Number(form.ownerId),
    sireId: n(form.sireId),
    damId: n(form.damId),
    horseName: form.horseName.trim(),
    registrationCode: form.registrationCode.trim().toUpperCase(),
    breed: s(form.breed),
    horseGender: s(form.horseGender),
    dateOfBirth: s(form.dateOfBirth),
    color: s(form.color),
    weight: n(form.weight),
    stallNo: s(form.stallNo),
  };
}

function Field({ id, label, error, hint, children }) {
  return (
    <div className="hz-field">
      <label htmlFor={id}>{label}</label>
      {children}
      {error ? (
        <p className="hz-field__error" id={`${id}-error`}>{error}</p>
      ) : hint ? (
        <p className="hz-field__hint">{hint}</p>
      ) : null}
    </div>
  );
}

export default function HorseFormPage() {
  const navigate = useNavigate();
  const { id } = useParams();
  const editing = Boolean(id);
  const user = getUser();
<<<<<<< HEAD
=======
  const horseBase = getHorseBasePath(user);
>>>>>>> 916da958dc05d2885df8052c0aed68e2296d7f72

  const [form, setForm] = useState(EMPTY);
  const [errors, setErrors] = useState({});
  const [submitError, setSubmitError] = useState("");
  const [herd, setHerd] = useState([]);
  const [loading, setLoading] = useState(true);
  const [saving, setSaving] = useState(false);
  const [loadError, setLoadError] = useState("");

  const load = useCallback(
    (signal) => {
      setLoading(true);
      setLoadError("");

      // Đàn ngựa cần cho hai việc: chọn cha/mẹ, và suy ra danh sách chủ ngựa.
      const jobs = [listHorses(signal), editing ? getHorse(id, signal) : Promise.resolve(null)];

      Promise.all(jobs)
        .then(([all, horse]) => {
          setHerd(all || []);
          if (horse) {
            setForm({
              ownerId: horse.ownerId ?? "",
              sireId: horse.sireId ?? "",
              damId: horse.damId ?? "",
              horseName: horse.horseName || "",
              registrationCode: horse.registrationCode || "",
              breed: horse.breed || "",
              horseGender: horse.horseGender || "",
              dateOfBirth: horse.dateOfBirth || "",
              color: horse.color || "",
              weight: horse.weight ?? "",
              stallNo: horse.stallNo || "",
            });
          }
        })
        .catch((err) => {
          if (err.name === "AbortError") return;
          if (handleAuthError(err, navigate)) return;
          setLoadError(err.message);
        })
        .finally(() => setLoading(false));
    },
    [editing, id, navigate]
  );

  useEffect(() => {
    const ctrl = new AbortController();
    load(ctrl.signal);
    return () => ctrl.abort();
  }, [load]);

  const owners = useMemo(() => ownersFromHorses(herd), [herd]);

  // Cha phải là COLT/STALLION, mẹ phải là FILLY/MARE, và không con nào được
  // chọn chính nó. Lọc sẵn ở đây thay vì để người dùng chọn rồi nhận 400.
  const sires = useMemo(
    () => herd.filter((h) => SIRE_GENDERS.has(h.horseGender) && String(h.horseId) !== String(id)),
    [herd, id]
  );
  const dams = useMemo(
    () => herd.filter((h) => DAM_GENDERS.has(h.horseGender) && String(h.horseId) !== String(id)),
    [herd, id]
  );

  // Chủ ngựa suy từ đàn nên có thể thiếu người chưa có con nào. Nếu hồ sơ đang
  // sửa trỏ tới một người như vậy, vẫn phải hiện ra, nếu không việc lưu lại sẽ
  // âm thầm đổi chủ con ngựa.
  const ownerMissing =
    String(form.ownerId) !== "" && !owners.some((o) => String(o.ownerId) === String(form.ownerId));

  const change = (e) => {
    const { name, value } = e.target;
    setForm((prev) => ({ ...prev, [name]: value }));
    if (errors[name]) setErrors((prev) => ({ ...prev, [name]: undefined }));
  };

  const submit = async (e) => {
    e.preventDefault();

    const found = validate(form, id);
    setErrors(found);
    if (Object.keys(found).length > 0) return;

    setSaving(true);
    setSubmitError("");

    try {
      const payload = toPayload(form);
      const saved = editing ? await updateHorse(id, payload) : await createHorse(payload);
<<<<<<< HEAD
      navigate(`/horses/${saved.horseId}`, { replace: true });
=======
      navigate(`${horseBase}/${saved.horseId}`, { replace: true });
>>>>>>> 916da958dc05d2885df8052c0aed68e2296d7f72
    } catch (err) {
      if (handleAuthError(err, navigate)) return;

      // 400 kèm errors -> gắn vào từng ô. 409 -> trùng mã đăng ký hoặc chuồng,
      // nhưng máy chủ không nói ô nào nên hiện chung ở cuối.
      const fieldErrors = err.fieldErrors || {};
      if (Object.keys(fieldErrors).length > 0) {
        setErrors(fieldErrors);
      } else {
        setSubmitError(err.message);
      }
    } finally {
      setSaving(false);
    }
  };

  if (!canEditHorses(user)) {
    return (
      <HorseShell title={editing ? "Edit horse" : "Add a horse"}>
        <StateBlock kind="error" title="You cannot edit horse records">
          Only a head trainer or the club manager may register or change a horse. Ask one of
<<<<<<< HEAD
          them, or go back to <Link to="/horses">the registry</Link>.
=======
          them, or go back to <Link to={horseBase}>the registry</Link>.
>>>>>>> 916da958dc05d2885df8052c0aed68e2296d7f72
        </StateBlock>
      </HorseShell>
    );
  }

  return (
    <HorseShell
      title={editing ? "Edit horse" : "Add a horse"}
      subtitle={
        editing
          ? "Health status is set by the veterinarian and cannot be changed here"
          : "Register a horse into the academy"
      }
      actions={
<<<<<<< HEAD
        <button type="button" className="hz-btn hz-btn--ghost" onClick={() => navigate("/horses")}>
=======
        <button type="button" className="hz-btn hz-btn--ghost" onClick={() => navigate(horseBase)}>
>>>>>>> 916da958dc05d2885df8052c0aed68e2296d7f72
          Cancel
        </button>
      }
    >
      {loadError && (
        <StateBlock kind="error" title={loadError} onRetry={() => load()}>
          The form cannot be filled in without the current herd.
        </StateBlock>
      )}

      {loading && <StateBlock title="Loading…" />}

      {!loading && !loadError && (
        <form onSubmit={submit} noValidate>
          <Card title="Identity">
            <div className="hz-form-grid">
              <Field id="horseName" label="Name" error={errors.horseName}>
                <input
                  id="horseName" name="horseName" value={form.horseName}
                  onChange={change} maxLength={100} autoComplete="off"
                  aria-invalid={!!errors.horseName}
                />
              </Field>

              <Field
                id="registrationCode"
                label="Registration code"
                error={errors.registrationCode}
                hint="Stored in upper case, and unique across the academy"
              >
                <input
                  id="registrationCode" name="registrationCode" value={form.registrationCode}
                  onChange={change} maxLength={30} autoComplete="off"
                  className="hz-mono" aria-invalid={!!errors.registrationCode}
                />
              </Field>

              <Field id="horseGender" label="Sex" error={errors.horseGender}>
                <select id="horseGender" name="horseGender" value={form.horseGender} onChange={change}>
                  <option value="">Not recorded</option>
                  {GENDER_OPTIONS.map((g) => (
                    <option key={g} value={g}>{toGender(g)}</option>
                  ))}
                </select>
              </Field>

              <Field id="dateOfBirth" label="Date of birth" error={errors.dateOfBirth}>
                <input
                  id="dateOfBirth" name="dateOfBirth" type="date" value={form.dateOfBirth}
                  onChange={change} aria-invalid={!!errors.dateOfBirth}
                />
              </Field>

              <Field id="breed" label="Breed" error={errors.breed}>
                <input id="breed" name="breed" value={form.breed} onChange={change} maxLength={50} />
              </Field>

              <Field id="color" label="Colour" error={errors.color}>
                <input id="color" name="color" value={form.color} onChange={change} maxLength={50} />
              </Field>
            </div>
          </Card>

          <Card title="Stable">
            <div className="hz-form-grid">
              <Field id="weight" label="Weight (kg)" error={errors.weight}>
                <input
                  id="weight" name="weight" type="number" step="0.1" min="0"
                  value={form.weight} onChange={change} aria-invalid={!!errors.weight}
                />
              </Field>

              <Field
                id="stallNo"
                label="Stall"
                error={errors.stallNo}
                hint="One horse per stall. Leave empty if none is assigned."
              >
                <input
                  id="stallNo" name="stallNo" value={form.stallNo} onChange={change}
                  maxLength={20} className="hz-mono" aria-invalid={!!errors.stallNo}
                />
              </Field>

              <Field
                id="ownerId"
                label="Owner"
                error={errors.ownerId}
                hint={
                  ownerMissing
                    ? "This owner has no other horse, so they are shown by id"
                    : "Only accounts with the Horse Owner role may be chosen"
                }
              >
                <select id="ownerId" name="ownerId" value={form.ownerId} onChange={change}
                        aria-invalid={!!errors.ownerId}>
                  <option value="">Pick an owner</option>
                  {owners.map((o) => (
                    <option key={o.ownerId} value={o.ownerId}>
                      {o.ownerName}
                    </option>
                  ))}
                  {ownerMissing && (
                    <option value={form.ownerId}>User #{form.ownerId}</option>
                  )}
                </select>
              </Field>
            </div>
          </Card>

          <Card
            title="Pedigree"
            hint="Optional. Only horses already registered here can be named as a parent."
          >
            <div className="hz-form-grid">
              <Field
                id="sireId"
                label="Sire"
                error={errors.sireId}
                hint={sires.length === 0 ? "No colt or stallion is registered yet" : undefined}
              >
                <select id="sireId" name="sireId" value={form.sireId} onChange={change}
                        aria-invalid={!!errors.sireId}>
                  <option value="">Unknown</option>
                  {sires.map((h) => (
                    <option key={h.horseId} value={h.horseId}>
                      {h.horseName} · {h.registrationCode}
                    </option>
                  ))}
                </select>
              </Field>

              <Field
                id="damId"
                label="Dam"
                error={errors.damId}
                hint={dams.length === 0 ? "No filly or mare is registered yet" : undefined}
              >
                <select id="damId" name="damId" value={form.damId} onChange={change}
                        aria-invalid={!!errors.damId}>
                  <option value="">Unknown</option>
                  {dams.map((h) => (
                    <option key={h.horseId} value={h.horseId}>
                      {h.horseName} · {h.registrationCode}
                    </option>
                  ))}
                </select>
              </Field>
            </div>
          </Card>

          {submitError && (
            <p className="hz-form-error" role="alert">{submitError}</p>
          )}

          <div className="hz-form-footer">
            <button type="submit" className="hz-btn" disabled={saving}>
              {saving ? "Saving…" : editing ? "Save changes" : "Register horse"}
            </button>

            <button
              type="button" className="hz-btn hz-btn--ghost" disabled={saving}
<<<<<<< HEAD
              onClick={() => navigate(editing ? `/horses/${id}` : "/horses")}
=======
              onClick={() => navigate(editing ? `${horseBase}/${id}` : horseBase)}
>>>>>>> 916da958dc05d2885df8052c0aed68e2296d7f72
            >
              Cancel
            </button>
          </div>
        </form>
      )}
    </HorseShell>
  );
}
