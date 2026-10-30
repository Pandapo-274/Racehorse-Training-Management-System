
import { useEffect, useRef, useState } from "react";
import { useNavigate, useParams, useSearchParams } from "react-router-dom";
import { getUser, handleAuthError } from "../auth/authService";
import { toAge } from "../horse/horseApi";
import VetShell, { VetCard, VetState, VetError } from "./VetShell";
import VetHorsePicker from "./VetHorsePicker";
import {
  getHorse,
  listLocksForHorse,
  listInjuries,
  createMedicalRecord,
  getIncidentReport,
  toUrgency,
  createInjury,
  issueLock,
  releaseLock,
  LOCK_LEVELS,
  SEVERITIES,
  lockLevelLabel,
  todayInputValue,
  examTimestamp,
  describeError,
} from "./vetApi";

/* Khoảng cân nặng hợp lệ (kg), khớp MedicalExamRequest.weight ở backend. */
const WEIGHT_MIN = 30;
const WEIGHT_MAX = 1500;

/* ---------- mô hình ngựa 2D (toạ độ theo % của khung vuông) ---------- */

const SHAPES = {
  body: { x: 12, y: 26, w: 52, h: 26, r: 5 },
  head: { x: 60, y: 12, w: 28, h: 15, r: 4 },
  legs: [
    { x: 16, y: 48, w: 7, h: 40 },
    { x: 27, y: 48, w: 7, h: 40 },
    { x: 50, y: 48, w: 7, h: 40 },
    { x: 59, y: 48, w: 7, h: 40 },
  ],
};

/** Vùng cơ thể gần đúng từ vị trí chạm. */
function guessBodyPart(x, y) {
  if (x >= 58 && y < 30) return "Head";

  if (y >= 50) {
    const level = y < 62 ? "upper" : y < 76 ? "middle" : "lower";

    if (x < 40) return `Hind limb, ${level}`;
    if (x >= 46) return `Forelimb, ${level}`;
  }

  return "Trunk";
}

function composeLocation(part, side) {
  return /^(Head|Trunk)/.test(part)
    ? part
    : `${side} ${part[0].toLowerCase()}${part.slice(1)}`;
}

function swapSide(text, side) {
  return /^(left|right)\b/i.test(text)
    ? text.replace(/^(left|right)/i, side)
    : text;
}

const clamp = (n) => Math.min(100, Math.max(0, n));
const round2 = (n) => Math.round(n * 100) / 100;

function rect({ x, y, w, h, r }) {
  return {
    x,
    y,
    width: w,
    height: h,
    rx: r,
    className: "vx-model-shape",
  };
}

function HorseModel({ marker, existingMarkers = [], onMark }) {
  const handleClick = (e) => {
    const rect = e.currentTarget.getBoundingClientRect();

    onMark(
      round2(clamp(((e.clientX - rect.left) / rect.width) * 100)),
      round2(clamp(((e.clientY - rect.top) / rect.height) * 100))
    );
  };

  const handleKey = (e) => {
    const step = 2;

    if ((e.key === "Enter" || e.key === " ") && !marker) {
      e.preventDefault();
      onMark(38, 40);
      return;
    }

    if (!marker) return;

    const moves = {
      ArrowLeft: [-step, 0],
      ArrowRight: [step, 0],
      ArrowUp: [0, -step],
      ArrowDown: [0, step],
    };

    if (moves[e.key]) {
      e.preventDefault();

      onMark(
        round2(clamp(marker.x + moves[e.key][0])),
        round2(clamp(marker.y + moves[e.key][1])),
        true
      );
    }
  };

  return (
    <div
      className="vx-model"
      role="button"
      tabIndex={0}
      aria-label="Horse model. Click to mark the injury site, or press Enter and use the arrow keys"
      onClick={handleClick}
      onKeyDown={handleKey}
    >
      <svg viewBox="0 0 100 100" aria-hidden="true" focusable="false">
        <rect {...rect(SHAPES.body)} />
        <rect {...rect(SHAPES.head)} />

        {SHAPES.legs.map((leg) => (
          <rect key={leg.x} {...rect({ ...leg, r: 3 })} />
        ))}
      </svg>

      {existingMarkers.map((item) => (
        <span
          key={item.injuryId}
          className="vx-marker"
          title={`${item.bodyLocation || "Injury"} · ${item.severity || ""}`}
          style={{
            left: `${Number(item.coordinateX)}%`,
            top: `${Number(item.coordinateY)}%`,
          }}
        >
          <i />
        </span>
      ))}

      {marker && (
        <span
          className="vx-marker"
          style={{
            left: `${marker.x}%`,
            top: `${marker.y}%`,
          }}
        >
          <i />
        </span>
      )}
    </div>
  );
}

/* ---------- trang ---------- */

export default function VetExaminationPage() {
  const { horseId } = useParams();
  // Mở từ hàng chờ khám: khám này trả lời báo cáo sự cố đó (backend đóng báo cáo khi lưu).
  const [searchParams] = useSearchParams();
  const reportParam = Number(searchParams.get("reportId"));
  const reportId = Number.isInteger(reportParam) && reportParam > 0 ? reportParam : null;

  if (!horseId) {
    return <VetHorsePicker mode="examination" />;
  }

  return (
    <ExaminationForm
      key={`${horseId}-${reportId || ""}`}
      horseId={Number(horseId)}
      reportId={reportId}
    />
  );
}

function Field({ label, full, children }) {
  return (
    <label className={`vx-field${full ? " vx-field--full" : ""}`}>
      <span>{label}</span>
      {children}
    </label>
  );
}

function ExaminationForm({ horseId, reportId }) {
  const navigate = useNavigate();
  const user = getUser();
  const [report, setReport] = useState(null);

  useEffect(() => {
    if (!reportId) return undefined;
    const ctrl = new AbortController();
    getIncidentReport(reportId, ctrl.signal)
      .then(setReport)
      .catch(() => {}); // banner chỉ là phụ; backend vẫn kiểm reportId khi lưu
    return () => ctrl.abort();
  }, [reportId]);

  const [horse, setHorse] = useState(null);
  const [locks, setLocks] = useState([]);
  const [existingMarkers, setExistingMarkers] = useState([]);
  const [loadError, setLoadError] = useState("");
  const [reload, setReload] = useState(0);

  const [examDate, setExamDate] = useState(todayInputValue());
  const [diagnosis, setDiagnosis] = useState("");
  const [confirmed, setConfirmed] = useState("yes");
  const [restDays, setRestDays] = useState("");
  const [otherStatus, setOtherStatus] = useState("");
  const [notes, setNotes] = useState("");
  // Cân nặng đo lúc khám (kg). Để trống = lần khám này không cân.
  const [weight, setWeight] = useState("");

  const [marker, setMarker] = useState(null);
  const [side, setSide] = useState("Left");
  const [bodyLocation, setBodyLocation] = useState("");
  const [severity, setSeverity] = useState("GRADE_2");

  const [lockLevel, setLockLevel] = useState("FULL");
  const [reason, setReason] = useState("");

  const [submitting, setSubmitting] = useState(null);
  const [error, setError] = useState(null);
  const [releasing, setReleasing] = useState(false);

  const saved = useRef({ record: null, injury: null });

  const injuryConfirmed = confirmed === "yes";
  const activeLock = locks.find((l) => l.status === "ACTIVE") || null;

  useEffect(() => {
    const ctrl = new AbortController();

    setLoadError("");

    Promise.all([
      getHorse(horseId, ctrl.signal),
      listLocksForHorse(horseId, ctrl.signal),
      listInjuries(horseId, ctrl.signal),
    ])
      .then(([h, l, injuries]) => {
        setHorse(h);
        setLocks(l);

        setExistingMarkers(
          (Array.isArray(injuries)
            ? injuries
            : injuries?.content || []
          ).filter(
            (item) =>
              item.recoveredAt == null &&
              item.status !== "RECOVERED" &&
              Number.isFinite(Number(item.coordinateX)) &&
              Number.isFinite(Number(item.coordinateY))
          )
        );
      })
      .catch((err) => {
        if (err.name === "AbortError") return;
        if (handleAuthError(err, navigate)) return;

        setLoadError(err.message);
      });

    return () => ctrl.abort();
  }, [horseId, navigate, reload]);

  // Di chuyển marker không ghi đè tên vùng đã được bác sĩ chỉnh sửa.
  const handleTap = (x, y, moveOnly = false) => {
    setMarker({ x, y });

    if (!moveOnly) {
      setBodyLocation(
        composeLocation(guessBodyPart(x, y), side)
      );
    }
  };

  const handleSide = (next) => {
    setSide(next);
    setBodyLocation((text) => swapSide(text, next));
  };

  const validate = (withLock) => {
    const problems = [];

    if (!examDate) {
      problems.push("Choose the exam date.");
    } else if (examDate > todayInputValue()) {
      problems.push("The exam date cannot be in the future.");
    }

    if (!diagnosis.trim()) {
      problems.push("Enter a diagnosis.");
    }

    if (diagnosis.trim().length > 255) {
      problems.push("The diagnosis must be at most 255 characters.");
    }

    // Cùng khoảng với @DecimalMin/@DecimalMax của MedicalExamRequest.weight.
    if (weight !== "") {
      const kg = Number(weight);
      if (!Number.isFinite(kg) || kg < WEIGHT_MIN || kg > WEIGHT_MAX) {
        problems.push(`Weight must be a number between ${WEIGHT_MIN} and ${WEIGHT_MAX} kg.`);
      }
    }

    if (injuryConfirmed) {
      if (!marker) {
        problems.push("Tap the model to mark the injury site.");
      } else if (!bodyLocation.trim()) {
        problems.push("Describe the marked location.");
      }

      if (restDays !== "" && !/^\d+$/.test(restDays)) {
        problems.push("Expected rest must be a whole number of days.");
      }
    }

    if (withLock) {
      const why = reason.trim() || diagnosis.trim();

      if (!why) {
        problems.push("A reason is required for the lock.");
      }

      if (why.length > 255) {
        problems.push("The lock reason must be at most 255 characters.");
      }
    }

    return problems;
  };

  const submit = async (withLock) => {
    setError(null);

    const problems = validate(withLock);

    if (problems.length) {
      setError({
        message: problems[0],
        details: problems.slice(1),
      });
      return;
    }

    setSubmitting(withLock ? "lock" : "save");

    const done = saved.current;
    const statusBefore = horse.status;

    try {
      if (!done.record) {
        done.record = await createMedicalRecord({
          horseId,
          reportId,
          recordType: "EXAM",
          examDate: examTimestamp(examDate),
          diagnosis: diagnosis.trim(),
          healthStatus: injuryConfirmed
            ? "INJURED"
            : otherStatus || null,
          injuryConfirmed,
          expectedRestDays:
            injuryConfirmed && restDays !== ""
              ? Number(restDays)
              : null,
          notes: notes.trim() || null,
          weight: weight === "" ? null : Number(weight),
        });
      }

      if (injuryConfirmed && !done.injury) {
        done.injury = await createInjury({
          recordId: done.record.recordId,
          bodyLocation: bodyLocation.trim(),
          coordinateX: marker.x,
          coordinateY: marker.y,
          severity,
        });
      }

      if (!withLock) {
        navigate("/veterinarian", {
          state: {
            notice: `Examination saved for ${horse.horseName}.`,
          },
        });
        return;
      }

      const lock = await issueLock({
        horseId,
        injuryId: done.injury?.injuryId ?? null,
        reason: (reason.trim() || diagnosis.trim()).slice(0, 255),
        lockLevel,
      });

      navigate(
        `/veterinarian/examination/${horseId}/lock-issued`,
        {
          state: {
            lock,
            record: done.record,
            injury: done.injury,
            statusBefore,
          },
        }
      );
    } catch (err) {
      if (handleAuthError(err, navigate)) return;

      const info = describeError(err);

      if (done.record) {
        info.note =
          `The examination record (#${done.record.recordId}) is already saved` +
          (done.injury
            ? ` with its injury marker (#${done.injury.injuryId})`
            : "") +
          ". Trying again only repeats the step that failed.";
      }

      setError(info);
    } finally {
      setSubmitting(null);
    }
  };

  const handleRelease = async () => {
    if (!activeLock) return;

    if (
      !window.confirm(
        `Release the current lock on ${horse.horseName}?`
      )
    ) {
      return;
    }

    setReleasing(true);
    setError(null);

    try {
      await releaseLock(activeLock.lockId);
      setReload((n) => n + 1);
    } catch (err) {
      if (handleAuthError(err, navigate)) return;
      setError(describeError(err));
    } finally {
      setReleasing(false);
    }
  };

  /* ---------- trạng thái tải ---------- */

  if (loadError || !horse) {
    return (
      <VetShell
        active="examination"
        title="Examination"
        subtitle="Loading horse…"
      >
        {loadError ? (
          <VetState
            kind="error"
            title={loadError}
            onRetry={() => setReload((n) => n + 1)}
          >
            Check that the backend is running on port 8080 and that
            you are still signed in.
          </VetState>
        ) : (
          <VetState title="Loading horse…" />
        )}
      </VetShell>
    );
  }

  const age = toAge(horse.dateOfBirth);
  const busy = submitting !== null;

  return (
    <VetShell
      active="examination"
      title={`Examination · ${horse.horseName}`}
      subtitle={[
        horse.registrationCode,
        horse.breed,
        age != null ? `${age} yrs` : null,
      ]
        .filter(Boolean)
        .join(" · ")}
    >
      <VetError error={error} />

      {report && (
        <div className="vx-banner vx-banner--warn" role="status">
          <strong>
            Incident report #{report.reportId} · {toUrgency(report.urgency).label}
          </strong>
          <span>
            {report.symptoms}
            {report.description ? ` — ${report.description}` : ""} (reported by {report.reporterName}).
            Saving this examination closes the report and lets the horse train again.
          </span>
        </div>
      )}

      <div className="vx-grid vx-grid--exam">
        <VetCard
          title="3D musculoskeletal model"
          hint={
            injuryConfirmed
              ? "Tap to mark the injury site"
              : "Marking is only needed for a confirmed injury"
          }
          tone="pink"
          className={injuryConfirmed ? "" : "vx-card--muted"}
        >
          <HorseModel
            marker={injuryConfirmed ? marker : null}
            existingMarkers={existingMarkers}
            onMark={injuryConfirmed ? handleTap : () => {}}
          />

          <div className="vx-marked">
            <span>Marked location</span>

            {injuryConfirmed && marker ? (
              <>
                <input
                  className="vx-input vx-input--location"
                  value={bodyLocation}
                  maxLength={100}
                  onChange={(e) => setBodyLocation(e.target.value)}
                  aria-label="Marked location"
                />

                <div className="vx-marked-row">
                  <div
                    className="vx-segment"
                    role="group"
                    aria-label="Side of the horse"
                  >
                    {["Left", "Right"].map((s) => (
                      <button
                        key={s}
                        type="button"
                        aria-pressed={side === s}
                        onClick={() => handleSide(s)}
                      >
                        {s}
                      </button>
                    ))}
                  </div>

                  <select
                    className="vx-input vx-input--compact"
                    value={severity}
                    onChange={(e) => setSeverity(e.target.value)}
                    aria-label="Severity"
                  >
                    {SEVERITIES.map((s) => (
                      <option key={s.value} value={s.value}>
                        {s.label}
                      </option>
                    ))}
                  </select>
                </div>
              </>
            ) : (
              <strong className="vx-marked-empty">
                {injuryConfirmed
                  ? "Nothing marked yet"
                  : "No injury to mark"}
              </strong>
            )}

            <p>
              The mark is saved with the record so progress can be
              compared at follow-ups.
            </p>
          </div>
        </VetCard>

        <div className="vx-stack">
          <VetCard title="Examination record" tone="green">
            <div className="vx-form-grid">
              <Field label="Exam date">
                <input
                  type="date"
                  className="vx-input"
                  value={examDate}
                  max={todayInputValue()}
                  onChange={(e) => setExamDate(e.target.value)}
                />
              </Field>

              <Field label="Veterinarian">
                <input
                  className="vx-input"
                  value={user?.fullName || ""}
                  readOnly
                />
              </Field>

              <Field label="Diagnosis" full>
                <input
                  className="vx-input"
                  value={diagnosis}
                  maxLength={255}
                  onChange={(e) => setDiagnosis(e.target.value)}
                  placeholder="e.g. Grade 2 tendon sheath inflammation, left hind hock"
                />
              </Field>

              <Field label="Injury confirmed">
                <select
                  className="vx-input"
                  value={confirmed}
                  onChange={(e) => setConfirmed(e.target.value)}
                >
                  <option value="yes">Yes</option>
                  <option value="no">No</option>
                </select>
              </Field>

              {injuryConfirmed ? (
                <Field label="Expected rest (days)">
                  <input
                    className="vx-input"
                    inputMode="numeric"
                    value={restDays}
                    onChange={(e) => setRestDays(e.target.value.trim())}
                    placeholder="e.g. 21"
                  />
                </Field>
              ) : (
                <Field label="Horse status after exam">
                  <select
                    className="vx-input"
                    value={otherStatus}
                    onChange={(e) => setOtherStatus(e.target.value)}
                  >
                    <option value="">No change</option>
                    <option value="MONITOR">Monitor</option>
                    <option value="QUARANTINE">Quarantine</option>
                  </select>
                </Field>
              )}

              <Field label="Weight (kg)">
                <input
                  className="vx-input"
                  inputMode="decimal"
                  value={weight}
                  onChange={(e) => setWeight(e.target.value.trim())}
                  placeholder={
                    horse?.weight
                      ? `Last recorded: ${Number(horse.weight).toFixed(1)} kg`
                      : "Not weighed yet, e.g. 480"
                  }
                />
              </Field>

              <Field label="Treatment plan / prescription" full>
                <textarea
                  className="vx-input vx-textarea"
                  rows={3}
                  value={notes}
                  onChange={(e) => setNotes(e.target.value)}
                  placeholder="Treatment, medication, rehab instructions…"
                />
              </Field>
            </div>
          </VetCard>

          <VetCard title="Training lock" tone="red">
            <p className="vx-lead">
              The lock stops the head trainer from scheduling heavy work
              for this horse until a veterinarian releases it.
            </p>

            {activeLock ? (
              <div className="vx-banner vx-banner--warn">
                <strong>
                  Already locked by {activeLock.issuedByName} (
                  {lockLevelLabel(activeLock.lockLevel)})
                </strong>

                <p>{activeLock.reason}</p>

                <button
                  type="button"
                  className="vx-link"
                  disabled={releasing}
                  onClick={handleRelease}
                >
                  {releasing ? "Releasing…" : "Release this lock"}
                </button>
              </div>
            ) : (
              <div className="vx-form-grid vx-form-grid--lock">
                <Field label="Lock level">
                  <select
                    className="vx-input"
                    value={lockLevel}
                    onChange={(e) => setLockLevel(e.target.value)}
                  >
                    {LOCK_LEVELS.map((l) => (
                      <option key={l.value} value={l.value}>
                        {l.label} — {l.hint}
                      </option>
                    ))}
                  </select>
                </Field>

                <Field label="Reason">
                  <input
                    className="vx-input"
                    value={reason}
                    maxLength={255}
                    onChange={(e) => setReason(e.target.value)}
                    placeholder="Defaults to the diagnosis"
                  />
                </Field>
              </div>
            )}

            <div className="vx-actions">
              <button
                type="button"
                className="vx-btn vx-btn--danger"
                disabled={busy || !!activeLock}
                onClick={() => submit(true)}
              >
                {submitting === "lock"
                  ? "Saving…"
                  : "Issue emergency lock"}
              </button>

              <button
                type="button"
                className="vx-btn vx-btn--ghost"
                disabled={busy}
                onClick={() => submit(false)}
              >
                {submitting === "save"
                  ? "Saving…"
                  : "Save without lock"}
              </button>
            </div>
          </VetCard>
        </div>
      </div>
    </VetShell>
  );
}
