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
import { useCallback, useEffect, useState } from "react";
import { useNavigate } from "react-router-dom";
import { handleAuthError } from "../auth/authService";
import HorseShell, { Card, StateBlock, StatusChip } from "./HorseShell";
import { GENDER_OPTIONS, toGender, formatDate, formatDateTime } from "./horseApi";
import { listRequests, submitRequest, toRequestStatus } from "./horseRequestApi";

const EMPTY = {
  horseName: "", breed: "", horseGender: "", dateOfBirth: "", color: "", note: "",
};

function todayIso() {
  const d = new Date();
  const p = (n) => String(n).padStart(2, "0");
  return `${d.getFullYear()}-${p(d.getMonth() + 1)}-${p(d.getDate())}`;
}

function validate(form) {
  const e = {};
  if (!form.horseName.trim()) e.horseName = "Tell us the horse's name";
  else if (form.horseName.trim().length > 100) e.horseName = "100 characters at most";
  if (form.breed.length > 50) e.breed = "50 characters at most";
  if (form.color.length > 50) e.color = "50 characters at most";
  if (form.note.length > 500) e.note = "500 characters at most";
  // So sánh theo chuỗi yyyy-mm-dd để khỏi dính múi giờ.
  if (form.dateOfBirth && form.dateOfBirth > todayIso()) {
    e.dateOfBirth = "Date of birth cannot be in the future";
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

export default function HorseRequestPage() {
  const navigate = useNavigate();

  const [form, setForm] = useState(EMPTY);
  const [errors, setErrors] = useState({});
  const [submitError, setSubmitError] = useState("");
  const [sending, setSending] = useState(false);
  const [rows, setRows] = useState(null);
  const [loadError, setLoadError] = useState("");

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

  const submit = async (e) => {
    e.preventDefault();

    const found = validate(form);
    setErrors(found);
    if (Object.keys(found).length > 0) return;

    setSending(true);
    setSubmitError("");

    try {
      await submitRequest(form);
      setForm(EMPTY);
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

  return (
    <HorseShell
      title="Register a horse"
      subtitle="Ask the academy to add a horse to your name"
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
          hint="Only the name is required — the academy will ask for anything else it needs"
        >
          <div className="hz-form-grid">
            <Field id="horseName" label="Name" error={errors.horseName}>
              <input id="horseName" name="horseName" value={form.horseName}
                     onChange={change} maxLength={100} autoComplete="off"
                     aria-invalid={!!errors.horseName} />
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

            <Field id="dateOfBirth" label="Date of birth" error={errors.dateOfBirth}>
              <input id="dateOfBirth" name="dateOfBirth" type="date"
                     value={form.dateOfBirth} onChange={change}
                     aria-invalid={!!errors.dateOfBirth} />
            </Field>

            <Field id="breed" label="Breed" error={errors.breed}>
              <input id="breed" name="breed" value={form.breed} onChange={change}
                     maxLength={50} />
            </Field>

            <Field id="color" label="Colour" error={errors.color}>
              <input id="color" name="color" value={form.color} onChange={change}
                     maxLength={50} />
            </Field>
          </div>

          <Field
            id="note"
            label="Anything the academy should know"
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
              The academy reviews your request and assigns a registration code and a
              stall. The horse appears under “My horses” once that is done.
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
          <ul className="hz-list">
            {rows.map((r) => {
              const st = toRequestStatus(r.status);
              return (
                <li key={r.requestId}>
                  <div>
                    <strong>{r.horseName}</strong>
                    <p>
                      {[toGender(r.horseGender) === "—" ? null : toGender(r.horseGender),
                        r.breed,
                        r.dateOfBirth ? formatDate(r.dateOfBirth) : null]
                        .filter(Boolean)
                        .join(" · ") || "No other details given"}
                    </p>
                    <p>Sent {formatDateTime(r.createdAt)}</p>

                    {/* Lý do từ chối phải hiện ra. Không có nó thì lần gửi lại
                        sẽ hỏng y như lần đầu. */}
                    {r.status === "REJECTED" && r.reviewNote && (
                      <p className="hz-reject-note">Academy: {r.reviewNote}</p>
                    )}
                  </div>

                  <StatusChip tone={st.tone} label={st.label} />
                </li>
              );
            })}
          </ul>
        )}
      </Card>
    </HorseShell>
  );
}
