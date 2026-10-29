// src/features/care/IncidentReportPage.js
// Flow 4 - the Groom reports a problem seen at the stable (off feed, colic,
// fever, a cut…), with an optional photo. The report goes into the same vet
// queue as a stopped session, and the horse cannot train until a vet examines it.
// Below the form: the reports this Groom has filed and where they are now.
import { useEffect, useMemo, useState } from "react";
import { useNavigate, useSearchParams } from "react-router-dom";
import { handleAuthError } from "../auth/authService";
import HorseShell, { Card, StateBlock, StatusChip } from "../horse/HorseShell";
import { formatDateTime, isPedigreeOnly, isWithdrawn, listHorses } from "../horse/horseApi";
import {
  INCIDENT_STATUS, PHOTO_MAX_BYTES, PHOTO_TYPES, STABLE_SYMPTOMS, URGENCY, fileIncident, listIncidents,
} from "./careApi";
import "./care.css";

function ReportForm({ horses, initialHorseId, onFiled }) {
  const navigate = useNavigate();
  const [form, setForm] = useState({ horseId: initialHorseId || "", symptoms: "", description: "", urgency: "MEDIUM" });
  const [photo, setPhoto] = useState(null);
  const [preview, setPreview] = useState("");
  const [errors, setErrors] = useState({});
  const [error, setError] = useState("");
  const [saving, setSaving] = useState(false);

  useEffect(() => {
    if (!photo) {
      setPreview("");
      return undefined;
    }
    const url = URL.createObjectURL(photo);
    setPreview(url);
    return () => URL.revokeObjectURL(url);
  }, [photo]);

  const pickPhoto = (e) => {
    const f = e.target.files?.[0];
    e.target.value = "";
    if (!f) return;
    if (!PHOTO_TYPES.includes(f.type)) {
      setErrors((x) => ({ ...x, photo: "Only JPEG, PNG or WebP images are allowed" }));
      return;
    }
    if (f.size > PHOTO_MAX_BYTES) {
      setErrors((x) => ({ ...x, photo: "File is too large (max 2 MB)" }));
      return;
    }
    setErrors(({ photo: _ignored, ...rest }) => rest);
    setPhoto(f);
  };

  const submit = async (e) => {
    e.preventDefault();
    setError("");
    const local = {};
    if (!form.horseId) local.horseId = "Choose a horse";
    if (!form.symptoms.trim()) local.symptoms = "Describe what you saw";
    setErrors(local);
    if (Object.keys(local).length) return;

    setSaving(true);
    try {
      const report = await fileIncident({
        horseId: form.horseId,
        symptoms: form.symptoms.trim(),
        description: form.description.trim(),
        urgency: form.urgency,
        photo,
      });
      setForm({ horseId: "", symptoms: "", description: "", urgency: "MEDIUM" });
      setPhoto(null);
      onFiled(report);
    } catch (err) {
      if (handleAuthError(err, navigate)) return;
      setError(err.message);
      setErrors(err.errors || {});
    } finally {
      setSaving(false);
    }
  };

  return (
    <Card title="Report a problem" hint="The vets are notified at once. The horse is kept off training until a vet examines it.">
      <form onSubmit={submit} noValidate>
        <div className="hz-form-grid">
          <div className="hz-field">
            <label htmlFor="inc-horse">Horse</label>
            <select id="inc-horse" value={form.horseId} aria-invalid={!!errors.horseId}
              onChange={(e) => setForm((f) => ({ ...f, horseId: e.target.value }))}>
              <option value="">Choose a horse…</option>
              {horses.map((h) => <option key={h.horseId} value={h.horseId}>{h.stallNo} · {h.horseName}</option>)}
            </select>
            {errors.horseId && <p className="hz-field__error">{errors.horseId}</p>}
          </div>
          <div className="hz-field">
            <label htmlFor="inc-urgency">Urgency</label>
            <select id="inc-urgency" value={form.urgency}
              onChange={(e) => setForm((f) => ({ ...f, urgency: e.target.value }))}>
              {Object.entries(URGENCY).map(([v, u]) => <option key={v} value={v}>{u.label} – {u.hint}</option>)}
            </select>
          </div>
          <div className="hz-field">
            <label htmlFor="inc-photo">Photo (optional)</label>
            <input id="inc-photo" type="file" accept={PHOTO_TYPES.join(",")} onChange={pickPhoto} />
            {errors.photo && <p className="hz-field__error">{errors.photo}</p>}
            {!errors.photo && <p className="hz-field__hint">JPEG, PNG or WebP, up to 2 MB.</p>}
          </div>
          <div className="hz-field care-field-wide">
            <label htmlFor="inc-symptoms">What did you see?</label>
            <input id="inc-symptoms" value={form.symptoms} maxLength={255} aria-invalid={!!errors.symptoms}
              placeholder="e.g. Left half of the morning feed"
              onChange={(e) => setForm((f) => ({ ...f, symptoms: e.target.value }))} />
            <div className="care-chips">
              {STABLE_SYMPTOMS.map((s) => (
                <button type="button" key={s} onClick={() => setForm((f) => ({ ...f, symptoms: s }))}>{s}</button>
              ))}
            </div>
            {errors.symptoms && <p className="hz-field__error">{errors.symptoms}</p>}
          </div>
          <div className="hz-field care-field-wide">
            <label htmlFor="inc-desc">Details (optional)</label>
            <textarea id="inc-desc" className="care-textarea" maxLength={2000} value={form.description}
              placeholder="When it started, temperature, what you already did…"
              onChange={(e) => setForm((f) => ({ ...f, description: e.target.value }))} />
          </div>
        </div>
        {preview && (
          <div className="care-photo-preview">
            <img src={preview} alt="Selected" />
            <button type="button" className="care-link-btn care-link-btn--danger" onClick={() => setPhoto(null)}>
              Remove photo
            </button>
          </div>
        )}
        {error && <p className="hz-form-error" role="alert">{error}</p>}
        <div className="hz-form-footer">
          <button type="submit" className="hz-btn" disabled={saving}>{saving ? "Sending…" : "Send report"}</button>
        </div>
      </form>
    </Card>
  );
}

export default function IncidentReportPage() {
  const navigate = useNavigate();
  const [params] = useSearchParams();
  const [horses, setHorses] = useState(null);
  const [reports, setReports] = useState([]);
  const [error, setError] = useState("");
  const [sent, setSent] = useState("");
  const [reloadKey, setReloadKey] = useState(0);

  useEffect(() => {
    const ctrl = new AbortController();
    setError("");
    Promise.all([listHorses(ctrl.signal), listIncidents({ status: "ALL" }, ctrl.signal)])
      .then(([h, r]) => {
        setHorses(h);
        setReports(r);
      })
      .catch((err) => {
        if (err.name === "AbortError") return;
        if (handleAuthError(err, navigate)) return;
        setError(err.message);
      });
    return () => ctrl.abort();
  }, [navigate, reloadKey]);

  const stabled = useMemo(
    () => (horses || [])
      .filter((h) => !isPedigreeOnly(h) && !isWithdrawn(h) && h.stallNo)
      .sort((a, b) => a.stallNo.localeCompare(b.stallNo, "en", { numeric: true })),
    [horses]
  );
  const initialHorseId = params.get("horseId") || "";

  return (
    <HorseShell title="Incident reports" subtitle="Tell the vets when a horse is not right">
      {error && (
        <StateBlock kind="error" title="Could not load this page" onRetry={() => setReloadKey((k) => k + 1)}>
          {error}
        </StateBlock>
      )}
      {!error && !horses && <StateBlock title="Loading…" />}

      {horses && (
        <>
          <ReportForm
            horses={stabled}
            initialHorseId={stabled.some((h) => String(h.horseId) === initialHorseId) ? initialHorseId : ""}
            onFiled={(r) => {
              setReports((old) => [r, ...old]);
              setSent(`Report #${r.reportId} for ${r.horseName} sent to the vets.`);
            }}
          />
          {sent && <p className="care-ok" role="status">{sent}</p>}

          <Card title="My reports" hint="Reports you filed, newest first">
            <div className="hz-table-wrap">
              <table className="hz-table">
                <thead>
                  <tr>
                    <th>Reported</th>
                    <th>Horse</th>
                    <th>What you saw</th>
                    <th>Urgency</th>
                    <th>Status</th>
                    <th>Photo</th>
                  </tr>
                </thead>
                <tbody>
                  {reports.map((r) => {
                    const u = URGENCY[r.urgency] || { label: r.urgency, tone: "neutral" };
                    const s = INCIDENT_STATUS[r.status] || { label: r.status, tone: "neutral" };
                    return (
                      <tr key={r.reportId}>
                        <td className="hz-muted">{formatDateTime(r.reportedAt)}</td>
                        <td className="hz-name">{r.horseName}{r.stallNo && <p className="hz-note hz-muted">{r.stallNo}</p>}</td>
                        <td>
                          {r.symptoms}
                          {r.sessionId && <p className="hz-note hz-muted">During a training session</p>}
                        </td>
                        <td><StatusChip tone={u.tone} label={u.label} /></td>
                        <td>
                          <StatusChip tone={s.tone} label={s.label} />
                          {r.receivedByName && <p className="hz-note hz-muted">{r.receivedByName}</p>}
                        </td>
                        <td>
                          {r.photoUrl
                            ? <a href={r.photoUrl} target="_blank" rel="noreferrer"><img className="care-thumb" src={r.photoUrl} alt={`Report #${r.reportId}: ${r.symptoms}`} /></a>
                            : <span className="hz-muted">—</span>}
                        </td>
                      </tr>
                    );
                  })}
                </tbody>
              </table>
              {reports.length === 0 && <p className="hz-empty">You have not filed any reports.</p>}
            </div>
          </Card>
        </>
      )}
    </HorseShell>
  );
}
