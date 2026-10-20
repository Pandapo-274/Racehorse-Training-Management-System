// src/features/vet/VetLockIssuedPage.js
// Màn 18 - Training lock issued.
//
// Mở ngay sau khi ra lệnh nên nhận kết quả qua location.state; nếu tải lại
// trang (state mất) thì dựng lại từ API: lệnh khoá mới nhất của con ngựa và
// hồ sơ khám có xác nhận chấn thương gần nhất.
//
// Mục "What the system did" chỉ liệt kê những việc backend THẬT SỰ làm và trả
// về. Backend hiện không huỷ buổi tập và không gửi thông báo, nên màn này
// không hiện hai dòng đó.

import { useEffect, useState } from "react";
import { useLocation, useNavigate, useParams } from "react-router-dom";
import { handleAuthError } from "../auth/authService";
import { formatDate, formatDateTime } from "../horse/horseApi";
import VetShell, { VetCard, VetState } from "./VetShell";
import {
  getHorse,
  listLocksForHorse,
  listMedicalRecords,
  statusLabel,
  lockLevelLabel,
} from "./vetApi";

function addDays(iso, days) {
  const d = new Date(iso);
  d.setDate(d.getDate() + days);
  return d.toISOString();
}

export default function VetLockIssuedPage() {
  const { horseId } = useParams();
  const { state } = useLocation();
  const navigate = useNavigate();

  const [data, setData] = useState(null);
  const [error, setError] = useState("");
  const [reload, setReload] = useState(0);

  useEffect(() => {
    const ctrl = new AbortController();
    setError("");
    Promise.all([
      getHorse(horseId, ctrl.signal),
      listLocksForHorse(horseId, ctrl.signal),
      listMedicalRecords(horseId, ctrl.signal),
    ])
      .then(([horse, locks, records]) => setData({ horse, locks, records }))
      .catch((err) => {
        if (err.name === "AbortError") return;
        if (handleAuthError(err, navigate)) return;
        setError(err.message);
      });
    return () => ctrl.abort();
  }, [horseId, navigate, reload]);

  if (error || !data) {
    return (
      <VetShell active="examination" title="Training lock issued" subtitle="Loading…">
        {error ? (
          <VetState kind="error" title={error} onRetry={() => setReload((n) => n + 1)} />
        ) : (
          <VetState title="Loading lock details…" />
        )}
      </VetShell>
    );
  }

  const { horse, locks, records } = data;
  const lock =
    (state?.lock && locks.find((l) => l.lockId === state.lock.lockId)) ||
    locks.find((l) => l.status === "ACTIVE") ||
    locks[0] ||
    null;

  if (!lock) {
    return (
      <VetShell active="examination" title="Training lock" subtitle={horse.horseName}>
        <VetState title="This horse has no training lock.">
          <button
            type="button"
            className="vx-btn vx-btn--ghost vx-btn--inline"
            onClick={() => navigate(`/veterinarian/examination/${horseId}`)}
          >
            Back to examination
          </button>
        </VetState>
      </VetShell>
    );
  }

  const record =
    (state?.record && records.find((r) => r.recordId === state.record.recordId)) ||
    records.find((r) => r.injuryConfirmed) ||
    records[0] ||
    null;
  const location = state?.injury?.bodyLocation || lock.injuryBodyLocation;
  const active = lock.status === "ACTIVE";
  const rest = record?.expectedRestDays;

  const statusBefore = state?.statusBefore;
  const statusChanged = statusBefore && statusBefore !== horse.status;

  const levelText =
    lock.lockLevel === "FULL"
      ? "cannot schedule any training for this horse"
      : "cannot schedule heavy work for this horse";

  const steps = [
    {
      title: "Status updated",
      detail: statusChanged
        ? `${statusLabel(statusBefore)} → ${statusLabel(horse.status)}`
        : `Current status: ${statusLabel(horse.status)}`,
    },
    {
      title: active ? "Training lock in force" : "Training lock released",
      detail: active
        ? `${lockLevelLabel(lock.lockLevel)} · until a veterinarian releases it`
        : `Released by ${lock.releasedByName || "—"} on ${formatDateTime(lock.releasedAt)}`,
    },
    record && {
      title: "Examination saved",
      detail: `Record #${record.recordId}${location ? ` · ${location}` : ""}`,
    },
    {
      title: "Logged",
      detail: `Lock #${lock.lockId} · ${lock.issuedByName} · ${formatDateTime(lock.issuedAt)}`,
    },
  ].filter(Boolean);

  const plan = rest
    ? [
        {
          tone: "gold",
          label: `Days 1–${rest}`,
          text: record.notes || record.diagnosis || "Rest and treatment",
        },
        {
          tone: "green",
          label: `Day ${rest + 1}`,
          text: `Lock release assessment · around ${formatDate(addDays(record.examDate, rest + 1))}`,
        },
      ]
    : [
        {
          tone: "gold",
          label: "Until released",
          text: "No expected rest was entered. The lock stays until a veterinarian confirms recovery.",
        },
      ];

  return (
    <VetShell
      active="examination"
      title={active ? "Training lock issued" : "Training lock"}
      subtitle={`${horse.horseName} · ${horse.registrationCode}`}
    >
      <div className={`vx-hero${active ? "" : " vx-hero--released"}`}>
        <h2>
          {horse.horseName}{" "}
          {active
            ? horse.status === "INJURED"
              ? "is now marked Injured and under training lock"
              : "is now under training lock"
            : "is no longer under this training lock"}
        </h2>
        <p>
          {active
            ? `The head trainer ${levelText} until the lock is released.`
            : `This lock was released on ${formatDateTime(lock.releasedAt)}.`}
        </p>
      </div>

      <div className="vx-grid vx-grid--lock">
        <VetCard title="What the system did" tone="green">
          <ul className="vx-steps">
            {steps.map((s) => (
              <li key={s.title}>
                <span className="vx-check" aria-hidden="true">
                  ✓
                </span>
                <div>
                  <strong>{s.title}</strong>
                  <span>{s.detail}</span>
                </div>
              </li>
            ))}
          </ul>
        </VetCard>

        <VetCard title="Recovery plan" tone="gold">
          <div className="vx-plan">
            {plan.map((p) => (
              <div key={p.label} className={`vx-plan-item vx-plan-item--${p.tone}`}>
                <strong>{p.label}</strong>
                <span>{p.text}</span>
              </div>
            ))}
          </div>
        </VetCard>
      </div>

      <div className="vx-actions vx-actions--left">
        <button
          type="button"
          className="vx-btn vx-btn--green"
          onClick={() => navigate(`/veterinarian/recovery/${horse.horseId}`)}
        >
          Go to recovery tracking
        </button>
        <button type="button" className="vx-btn vx-btn--ghost" onClick={() => navigate("/veterinarian")}>
          Back to health board
        </button>
      </div>
    </VetShell>
  );
}