// src/features/horse/HorseFormPage.js
// Chủ ngựa sửa thông tin cơ bản của NGỰA CỦA MÌNH.
//
// Màn này không còn tạo ngựa nữa: ngựa mới vào qua yêu cầu đăng ký
// (HorseRequestPage) và Club Manager duyệt. Cũng không còn là màn của
// Manager/Trainer - họ chỉ xem.
//
// Sửa được: tên, giới tính, ngày sinh, giống, màu lông, cha, mẹ.
// KHÔNG sửa được (hiện ở dạng khoá): mã đăng ký, chuồng, chủ, vì hai thứ đầu là
// định danh cố định của con ngựa trong hệ thống. Cân nặng do học viện cân,
// trạng thái do bác sĩ thú y quyết định.
import { useCallback, useEffect, useMemo, useState } from "react";
import { useNavigate, useParams, Link } from "react-router-dom";
import { getUser, handleAuthError } from "../auth/authService";
import HorseShell, { Card, StateBlock } from "./HorseShell";
import HorsePhoto from "./HorsePhoto";
import {
  listHorses, getHorse, updateHorse, canEditHorse, getHorseBasePath,
  GENDER_OPTIONS, toGender, SIRE_GENDERS, DAM_GENDERS, ageProblem, isPedigreeOnly,
  uploadHorsePhoto, removeHorsePhoto, checkHorsePhoto,
} from "./horseApi";

function todayIso() {
  const d = new Date();
  const p = (n) => String(n).padStart(2, "0");
  return `${d.getFullYear()}-${p(d.getMonth() + 1)}-${p(d.getDate())}`;
}

function validate(form, horse, selfId) {
  const e = {};
  if (!form.horseName.trim()) e.horseName = "Name is required";
  else if (form.horseName.trim().length > 100) e.horseName = "100 characters at most";
  if (form.breed.length > 50) e.breed = "50 characters at most";
  if (form.color.length > 50) e.color = "50 characters at most";
  if (form.dateOfBirth && form.dateOfBirth > todayIso()) {
    e.dateOfBirth = "Date of birth cannot be in the future";
  }
  // Sửa ngày sinh không được là cách lách luật tuổi của học viện.
  const age = ageProblem(horse?.recordType, form.dateOfBirth);
  if (!e.dateOfBirth && age) e.dateOfBirth = age;

  if (form.sireId && String(form.sireId) === String(selfId)) e.sireId = "A horse cannot be its own sire";
  if (form.damId && String(form.damId) === String(selfId)) e.damId = "A horse cannot be its own dam";
  if (form.sireId && form.sireId === form.damId) e.damId = "Sire and dam cannot be the same horse";
  return e;
}

function toPayload(form) {
  const n = (v) => (String(v).trim() === "" ? null : Number(v));
  const s = (v) => (v.trim() === "" ? null : v.trim());
  return {
    horseName: form.horseName.trim(),
    breed: s(form.breed),
    horseGender: s(form.horseGender),
    dateOfBirth: s(form.dateOfBirth),
    color: s(form.color),
    sireId: n(form.sireId),
    damId: n(form.damId),
  };
}

function Field({ id, label, error, hint, children }) {
  return (
    <div className="hz-field">
      <label htmlFor={id}>{label}</label>
      {children}
      {error ? <p className="hz-field__error" id={`${id}-error`}>{error}</p>
        : hint ? <p className="hz-field__hint">{hint}</p> : null}
    </div>
  );
}

export default function HorseFormPage() {
  const navigate = useNavigate();
  const { id } = useParams();
  const user = getUser();
  const horseBase = getHorseBasePath(user);

  const [horse, setHorse] = useState(null);
  const [form, setForm] = useState(null);
  const [herd, setHerd] = useState([]);
  const [errors, setErrors] = useState({});
  const [submitError, setSubmitError] = useState("");
  const [loading, setLoading] = useState(true);
  const [saving, setSaving] = useState(false);
  const [loadError, setLoadError] = useState("");
  const [photoBusy, setPhotoBusy] = useState(false);
  const [photoError, setPhotoError] = useState("");

  const load = useCallback(
    (signal) => {
      setLoading(true);
      setLoadError("");
      Promise.all([listHorses(signal), getHorse(id, signal)])
        .then(([all, h]) => {
          setHerd(all || []);
          setHorse(h);
          setForm({
            horseName: h.horseName || "",
            horseGender: h.horseGender || "",
            dateOfBirth: h.dateOfBirth || "",
            breed: h.breed || "",
            color: h.color || "",
            sireId: h.sireId ?? "",
            damId: h.damId ?? "",
          });
        })
        .catch((err) => {
          if (err.name === "AbortError") return;
          if (handleAuthError(err, navigate)) return;
          setLoadError(err.message);
        })
        .finally(() => setLoading(false));
    },
    [id, navigate]
  );

  useEffect(() => {
    const ctrl = new AbortController();
    load(ctrl.signal);
    return () => ctrl.abort();
  }, [load]);

  // Chủ ngựa chỉ nhận ngựa của mình từ GET /api/horses, nên danh sách cha/mẹ
  // ở đây là những con ngựa (kể cả hồ sơ pedigree) đứng tên mình.
  const sires = useMemo(
    () => herd.filter((h) => SIRE_GENDERS.has(h.horseGender) && String(h.horseId) !== String(id)),
    [herd, id]
  );
  const dams = useMemo(
    () => herd.filter((h) => DAM_GENDERS.has(h.horseGender) && String(h.horseId) !== String(id)),
    [herd, id]
  );

  const change = (e) => {
    const { name, value } = e.target;
    setForm((prev) => ({ ...prev, [name]: value }));
    if (errors[name]) setErrors((prev) => ({ ...prev, [name]: undefined }));
  };

  const submit = async (e) => {
    e.preventDefault();
    const found = validate(form, horse, id);
    setErrors(found);
    if (Object.keys(found).length > 0) return;

    setSaving(true);
    setSubmitError("");
    try {
      const saved = await updateHorse(id, toPayload(form));
      navigate(`${horseBase}/${saved.horseId}`, { replace: true });
    } catch (err) {
      if (handleAuthError(err, navigate)) return;
      const fieldErrors = err.fieldErrors || {};
      if (Object.keys(fieldErrors).length > 0) setErrors(fieldErrors);
      else setSubmitError(err.message);
    } finally {
      setSaving(false);
    }
  };

  // Ảnh lưu ngay khi chọn, độc lập với nút "Save" của biểu mẫu - giống avatar ở ProfilePage.
  const pickPhoto = async (e) => {
    const file = e.target.files?.[0];
    e.target.value = ""; // chọn lại cùng một file vẫn kích hoạt onChange
    if (!file) return;

    setPhotoError("");
    const problem = await checkHorsePhoto(file);
    if (problem) {
      setPhotoError(problem);
      return;
    }
    await runPhoto(() => uploadHorsePhoto(id, file));
  };

  const runPhoto = async (action) => {
    setPhotoBusy(true);
    setPhotoError("");
    try {
      const updated = await action();
      setHorse((prev) => ({ ...prev, photoUrl: updated.photoUrl }));
    } catch (err) {
      if (handleAuthError(err, navigate)) return;
      setPhotoError(err.fieldErrors?.file || err.message);
    } finally {
      setPhotoBusy(false);
    }
  };

  if (loading) {
    return <HorseShell title="Edit horse"><StateBlock title="Loading…" /></HorseShell>;
  }

  if (loadError) {
    return (
      <HorseShell title="Edit horse">
        <StateBlock kind="error" title={loadError} onRetry={() => load()}>
          Go back to <Link to={horseBase}>the registry</Link>.
        </StateBlock>
      </HorseShell>
    );
  }

  if (!canEditHorse(user, horse)) {
    return (
      <HorseShell title="Edit horse">
        <StateBlock kind="error" title="You cannot edit this horse">
          Only the owner of a horse can change its details. Go back to{" "}
          <Link to={horseBase}>the registry</Link>.
        </StateBlock>
      </HorseShell>
    );
  }

  const pedigreeOnly = isPedigreeOnly(horse);

  return (
    <HorseShell
      title="Edit horse"
      subtitle="You can change the basic details. The registration code and stall are fixed."
      actions={
        <button type="button" className="hz-btn hz-btn--ghost"
                onClick={() => navigate(`${horseBase}/${id}`)}>
          Cancel
        </button>
      }
    >
      <Card title="Photo" hint="JPEG, PNG or WebP, up to 2 MB. Saved as soon as you pick it.">
        <div className="hz-photo-editor">
          <HorsePhoto horse={horse} />

          <div className="hz-photo-editor__actions">
            <label className={`hz-btn${photoBusy ? " is-disabled" : ""}`}>
              {photoBusy ? "Saving…" : horse.photoUrl ? "Replace photo" : "Upload a photo"}
              <input
                type="file"
                accept="image/jpeg,image/png,image/webp"
                onChange={pickPhoto}
                disabled={photoBusy}
                hidden
              />
            </label>

            {horse.photoUrl && (
              <button
                type="button" className="hz-btn hz-btn--ghost" disabled={photoBusy}
                onClick={() => runPhoto(() => removeHorsePhoto(id))}
              >
                Remove photo
              </button>
            )}

            {photoError && <p className="hz-field__error" role="alert">{photoError}</p>}
          </div>
        </div>
      </Card>

      <form onSubmit={submit} noValidate>
        <Card title="Fixed by the club"
              hint="These identify the horse in the system, so they cannot be changed here">
          <div className="hz-form-grid">
            <Field id="registrationCode" label="Registration code">
              <input id="registrationCode" value={horse.registrationCode} disabled readOnly className="hz-mono" />
            </Field>
            {!pedigreeOnly && (
              <Field id="stallNo" label="Stall">
                <input id="stallNo" value={horse.stallNo || "—"} disabled readOnly className="hz-mono" />
              </Field>
            )}
            <Field id="ownerName" label="Owner">
              <input id="ownerName" value={horse.ownerName || ""} disabled readOnly />
            </Field>
          </div>
        </Card>

        <Card title="Basic details">
          <div className="hz-form-grid">
            <Field id="horseName" label="Name" error={errors.horseName}>
              <input id="horseName" name="horseName" value={form.horseName} onChange={change}
                     maxLength={100} autoComplete="off" aria-invalid={!!errors.horseName} />
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
              <input id="dateOfBirth" name="dateOfBirth" type="date" value={form.dateOfBirth}
                     onChange={change} aria-invalid={!!errors.dateOfBirth} />
            </Field>

            <Field id="breed" label="Breed" error={errors.breed}>
              <input id="breed" name="breed" value={form.breed} onChange={change} maxLength={50} />
            </Field>

            <Field id="color" label="Colour" error={errors.color}>
              <input id="color" name="color" value={form.color} onChange={change} maxLength={50} />
            </Field>
          </div>
        </Card>

        <Card title="Pedigree" hint="Optional. Choose from the horses registered in your name.">
          <div className="hz-form-grid">
            <Field id="sireId" label="Sire" error={errors.sireId}
                   hint={sires.length === 0 ? "No colt or stallion in your name yet" : undefined}>
              <select id="sireId" name="sireId" value={form.sireId} onChange={change}>
                <option value="">Unknown</option>
                {sires.map((h) => (
                  <option key={h.horseId} value={h.horseId}>{h.horseName} · {h.registrationCode}</option>
                ))}
              </select>
            </Field>

            <Field id="damId" label="Dam" error={errors.damId}
                   hint={dams.length === 0 ? "No filly or mare in your name yet" : undefined}>
              <select id="damId" name="damId" value={form.damId} onChange={change}>
                <option value="">Unknown</option>
                {dams.map((h) => (
                  <option key={h.horseId} value={h.horseId}>{h.horseName} · {h.registrationCode}</option>
                ))}
              </select>
            </Field>
          </div>
        </Card>

        {submitError && <p className="hz-form-error" role="alert">{submitError}</p>}

        <div className="hz-form-footer">
          <button type="submit" className="hz-btn" disabled={saving}>
            {saving ? "Saving…" : "Save changes"}
          </button>
          <button type="button" className="hz-btn hz-btn--ghost" disabled={saving}
                  onClick={() => navigate(`${horseBase}/${id}`)}>
            Cancel
          </button>
        </div>
      </form>
    </HorseShell>
  );
}