// src/features/vet/VetCallsPage.js
// Màn Vet calls: Head Trainer gọi bác sĩ từ một cảnh báo buổi tập (V27).
//
//   /veterinarian/vet-calls          hàng chờ: ca chờ trả lời (gấp nhất trước) và ca đã trả lời
//   /veterinarian/vet-calls/:callId  một ca: HLV thấy gì, và form trả lời
//
// Chuông "Veterinarian needed at training" mở thẳng trang của ca đó.
// Mỗi ca chỉ trả lời một lần; hai bác sĩ cùng trả lời thì người sau nhận lỗi
// "already answered" và trang tải lại câu trả lời của người trước.

import { useEffect, useState } from "react";
import { useNavigate, useParams } from "react-router-dom";
import { handleAuthError } from "../auth/authService";
import { formatDateTime } from "../horse/horseApi";
import VetShell, { VetCard, VetState, VetError } from "./VetShell";
import { describeError } from "./vetApi";
import {
  listVetCalls,
  getVetCall,
  answerVetCall,
  decisionLabel,
  urgencyLabel,
  metricText,
  VET_DECISIONS,
} from "../vetcall/vetCallApi";
import "./vet-care.css";
import "./vet-call.css";

const POLL_MS = 10000;

const SESSION_TEXT = {
  RUNNING: "Still running",
  STOPPED_EARLY: "Stopped",
  COMPLETED: "Completed",
  SCHEDULED: "Scheduled",
  CANCELLED: "Cancelled",
};

function UrgencyPill({ urgency }) {
  return <i className={`vcall-pill vcall-pill--${String(urgency).toLowerCase()}`}>{urgencyLabel(urgency)}</i>;
}

export default function VetCallsPage() {
  const { callId } = useParams();
  return callId ? <VetCallDetail callId={callId} /> : <VetCallQueue />;
}

/* ---------------- hàng chờ ---------------- */

function VetCallQueue() {
  const navigate = useNavigate();
  const [calls, setCalls] = useState(null);
  const [error, setError] = useState("");
  const [reload, setReload] = useState(0);

  useEffect(() => {
    const ctrl = new AbortController();
    const load = () =>
      listVetCalls({ status: "ALL", limit: 40, signal: ctrl.signal })
        .then((data) => {
          setCalls(data);
          setError("");
        })
        .catch((err) => {
          if (err.name === "AbortError") return;
          if (handleAuthError(err, navigate)) return;
          setError(err.message);
        });
    load();
    const timer = setInterval(load, POLL_MS);
    return () => {
      ctrl.abort();
      clearInterval(timer);
    };
  }, [navigate, reload]);

  const pending = (calls || []).filter((c) => c.status === "PENDING");
  const answered = (calls || []).filter((c) => c.status === "ANSWERED");

  return (
    <VetShell active="vetcalls" title="Vet calls" subtitle="Head trainers and grooms asking for veterinary help during training">
      {error && (
        <VetState kind="error" title={error} onRetry={() => setReload((n) => n + 1)}>
          Check that the backend is running and that you are still signed in.
        </VetState>
      )}
      {!error && !calls && <VetState title="Loading vet calls…" />}

      {calls && (
        <>
          <VetCard
            title="Waiting for your answer"
            hint={pending.length === 0 ? "Nothing waiting" : `${pending.length} waiting · most urgent first`}
            tone="red"
          >
            {pending.length === 0 ? (
              <div className="vet-inline-state">No trainer or groom is waiting for advice right now.</div>
            ) : (
              <div className="vcall-list">
                {pending.map((c) => (
                  <CallRow key={c.callId} call={c} onOpen={() => navigate(`/veterinarian/vet-calls/${c.callId}`)} />
                ))}
              </div>
            )}
          </VetCard>

          <VetCard title="Answered" hint="Most recent first" tone="green" className="vcall-gap">
            {answered.length === 0 ? (
              <div className="vet-inline-state">No answered calls yet.</div>
            ) : (
              <div className="vcall-list">
                {answered.map((c) => (
                  <CallRow key={c.callId} call={c} onOpen={() => navigate(`/veterinarian/vet-calls/${c.callId}`)} />
                ))}
              </div>
            )}
          </VetCard>
        </>
      )}
    </VetShell>
  );
}

function CallRow({ call, onOpen }) {
  const pending = call.status === "PENDING";
  return (
    <button type="button" className={`vcall-row${pending ? " vcall-row--pending" : ""}`} onClick={onOpen}>
      <div className="vcall-row-main">
        <strong>
          {call.horseName} <small>· Stall {call.stallNo || "—"} · Session #{call.sessionId}</small>
        </strong>
        <p>{call.reason}</p>
        <small>
          {metricText(call)} · called by {call.calledByName} · {formatDateTime(call.calledAt)}
        </small>
      </div>
      <div className="vcall-row-side">
        {pending ? <UrgencyPill urgency={call.urgency} /> : <i className="vx-pill vx-pill--ok">{decisionLabel(call.decision)}</i>}
        <span className="vcall-open">{pending ? "Answer →" : "View →"}</span>
      </div>
    </button>
  );
}

/* ---------------- một ca ---------------- */

function VetCallDetail({ callId }) {
  const navigate = useNavigate();
  const [call, setCall] = useState(null);
  const [error, setError] = useState("");
  const [reload, setReload] = useState(0);
  const [justAnswered, setJustAnswered] = useState(false);

  useEffect(() => {
    const ctrl = new AbortController();
    setError("");
    getVetCall(callId, ctrl.signal)
      .then(setCall)
      .catch((err) => {
        if (err.name === "AbortError") return;
        if (handleAuthError(err, navigate)) return;
        setError(err.message);
      });
    return () => ctrl.abort();
  }, [callId, navigate, reload]);

  // Chuông mở ca khác trong lúc đang xem một ca: bỏ thông báo "đã gửi" của ca cũ.
  useEffect(() => setJustAnswered(false), [callId]);

  if (error) {
    return (
      <VetShell active="vetcalls" title="Vet call">
        <VetState kind="error" title={error} onRetry={() => setReload((n) => n + 1)} />
      </VetShell>
    );
  }
  if (!call) {
    return (
      <VetShell active="vetcalls" title="Vet call">
        <VetState title="Loading the call…" />
      </VetShell>
    );
  }

  const pending = call.status === "PENDING";

  return (
    <VetShell
      active="vetcalls"
      title={`Vet call · ${call.horseName}`}
      subtitle={`Called by ${call.calledByName} · ${formatDateTime(call.calledAt)}`}
    >
      {justAnswered && (
        <div className="vx-banner vx-banner--ok" role="status">
          <strong>Your advice was sent to {call.calledByName} and the groom.</strong>
          {call.decision === "EMERGENCY_STOP" && (
            <p>The horse is now in your examination queue and cannot train until you examine it.</p>
          )}
        </div>
      )}

      <button type="button" className="vcall-back" onClick={() => navigate("/veterinarian/vet-calls")}>
        ← All vet calls
      </button>

      <div className="vx-grid vx-grid--recovery">
        <VetCard title="What was reported" tone={pending ? "red" : "green"}>
          <dl className="vcall-facts">
            <div><dt>Horse</dt><dd>{call.horseName} · Stall {call.stallNo || "—"}</dd></div>
            <div><dt>Alert</dt><dd>{call.metricType ? `${metricText(call)} · ${String(call.severity).toLowerCase()}` : metricText(call)}</dd></div>
            <div><dt>Urgency</dt><dd><UrgencyPill urgency={call.urgency} /></dd></div>
            <div><dt>Reason</dt><dd>{call.reason}</dd></div>
            <div><dt>Signs seen</dt><dd>{call.observations || "None noted"}</dd></div>
            <div>
              <dt>Session</dt>
              <dd>
                #{call.sessionId} · {SESSION_TEXT[call.sessionStatus] || call.sessionStatus}
                {call.sessionStopped ? " (stopped when the call was made)" : ""} · Groom {call.groomName}
              </dd>
            </div>
          </dl>
        </VetCard>

        {pending ? (
          <AnswerForm
            call={call}
            onAnswered={(updated) => {
              setCall(updated);
              setJustAnswered(true);
            }}
            onStale={() => setReload((n) => n + 1)}
          />
        ) : (
          <VetCard title="Your advice" tone="green">
            <div className="vcall-answer">
              <strong>{decisionLabel(call.decision)}</strong>
              <p>{call.instructions}</p>
              {call.medication && <p><b>Give now:</b> {call.medication}</p>}
              <small>{call.answeredByName} · {formatDateTime(call.answeredAt)}</small>
            </div>
            {call.reportId && (
              <button
                type="button"
                className="vx-btn vx-btn--danger vx-btn--wide"
                onClick={() => navigate(`/veterinarian/examination/${call.horseId}`)}
              >
                Examine {call.horseName} now
              </button>
            )}
          </VetCard>
        )}
      </div>
    </VetShell>
  );
}

function AnswerForm({ call, onAnswered, onStale }) {
  const navigate = useNavigate();
  const [decision, setDecision] = useState(call.urgency === "HIGH" ? "" : "CONTINUE_LIGHT");
  const [instructions, setInstructions] = useState("");
  const [medication, setMedication] = useState("");
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState(null);

  const needsMedication = decision === "TREAT_ON_SITE";

  const submit = async () => {
    setBusy(true);
    setError(null);
    try {
      const updated = await answerVetCall(call.callId, {
        decision,
        instructions: instructions.trim(),
        medication: medication.trim() || null,
      });
      onAnswered(updated);
    } catch (err) {
      if (handleAuthError(err, navigate)) return;
      if (err.status === 409) {
        onStale(); // một bác sĩ khác vừa trả lời: tải lại để thấy câu trả lời đó
        return;
      }
      setError(describeError(err));
      setBusy(false);
    }
  };

  return (
    <VetCard title="Your advice" hint="The caller and the groom see it straight away" tone="pink">
      <VetError error={error} />
      <fieldset className="vcall-decisions">
        <legend>Decision</legend>
        {VET_DECISIONS.map((d) => (
          <label key={d.value} className={`vcall-decision vcall-decision--${d.value.toLowerCase()}`}>
            <input
              type="radio"
              name="decision"
              value={d.value}
              checked={decision === d.value}
              onChange={() => setDecision(d.value)}
            />
            <span>
              <strong>{d.label}</strong>
              <small>{d.hint}</small>
            </span>
          </label>
        ))}
      </fieldset>

      <label className="vx-field vx-field--full vcall-gap-sm">
        <span>What should the trainer / groom do?</span>
        <textarea
          className="vx-input vx-textarea"
          rows={3}
          maxLength={1000}
          placeholder="e.g. Walk her for 10 minutes, cold-hose the left fore, check heart rate again in 15 minutes"
          value={instructions}
          onChange={(e) => setInstructions(e.target.value)}
        />
      </label>

      <label className="vx-field vx-field--full vcall-gap-sm">
        <span>Medication to give now{needsMedication ? "" : " (optional)"}</span>
        <textarea
          className="vx-input vx-textarea"
          rows={2}
          maxLength={500}
          placeholder="e.g. Flunixin 1.1 mg/kg IV once, from the stable kit"
          value={medication}
          onChange={(e) => setMedication(e.target.value)}
        />
      </label>

      <div className="vx-actions">
        <button
          type="button"
          className={`vx-btn ${decision === "EMERGENCY_STOP" ? "vx-btn--danger" : "vx-btn--green"}`}
          disabled={busy || !decision || !instructions.trim() || (needsMedication && !medication.trim())}
          onClick={submit}
        >
          {busy ? "Sending…" : "Send advice"}
        </button>
      </div>
    </VetCard>
  );
}
