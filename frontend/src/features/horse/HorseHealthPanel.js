// src/features/horse/HorseHealthPanel.js
// Chi tiết tình trạng sức khoẻ trên hồ sơ ngựa - CHỈ ĐỌC, mọi role đều thấy.
//
// Quy tắc nghiệp vụ: ngoài bác sĩ thú y, không role nào được đổi trạng thái
// của ngựa, nhưng ai xem được con ngựa thì xem được nó bị gì. Panel chỉ hiện
// khi ngựa không ở trạng thái ELIGIBLE. Dữ liệu lấy từ ba endpoint đọc mà
// backend đã kiểm tra quyền xem theo từng con ngựa (chủ ngựa chỉ thấy ngựa của mình).
//
// Phác đồ điều trị (notes) là thông tin chuyên môn nên chỉ bác sĩ thú y thấy.

import { useEffect, useState } from "react";
import { useNavigate } from "react-router-dom";
import { ApiError, handleAuthError } from "../auth/authService";
import { Card } from "./HorseShell";
import { formatDate, formatDateTime } from "./horseApi";
import { listInjuries, listMedicalRecords, listLocksForHorse, lockLevelLabel, statusLabel } from "../vet/vetApi";
import "./horse-health.css";

const SEVERITY = { GRADE_1: "Grade 1", GRADE_2: "Grade 2", GRADE_3: "Grade 3" };
const INJURY_STATUS = { TREATING: "Under treatment", PARTIAL: "Partly recovered", RECOVERED: "Recovered" };

export default function HorseHealthPanel({ horse, user }) {
  const navigate = useNavigate();
  const [data, setData] = useState(null);
  const [error, setError] = useState("");

  const needed = horse.status !== "ELIGIBLE";

  useEffect(() => {
    if (!needed) return undefined;
    const ctrl = new AbortController();
    setData(null);
    setError("");
    Promise.all([
      listInjuries(horse.horseId, ctrl.signal),
      listMedicalRecords(horse.horseId, ctrl.signal),
      listLocksForHorse(horse.horseId, ctrl.signal),
    ])
      .then(([injuries, records, locks]) => setData({ injuries, records, locks }))
      .catch((err) => {
        if (err.name === "AbortError") return;
        // Hết phiên thì về /login; còn 403 chỉ báo trong panel, không đá cả
        // trang hồ sơ sang access-denied vì đây là phần phụ.
        if (err instanceof ApiError && err.status === 401 && handleAuthError(err, navigate)) return;
        setError(err.message);
      });
    return () => ctrl.abort();
  }, [horse.horseId, horse.status, needed, navigate]);

  if (!needed) return null;

  const isVet = user?.role === "VETERINARIAN";
  let body;

  if (error) {
    body = <p className="hh-muted">Could not load the medical details: {error}</p>;
  } else if (!data) {
    body = <p className="hh-muted">Loading…</p>;
  } else {
    const open = data.injuries.filter((i) => i.status !== "RECOVERED");
    const exams = data.records.filter((r) => r.recordType === "EXAM");
    const latest = exams.find((r) => r.healthStatus === horse.status) || exams[0] || null;
    const lock = data.locks.find((l) => l.status === "ACTIVE") || null;

    body = (
      <>
        {open.length > 0 && (
          <ul className="hh-injuries">
            {open.map((i) => (
              <li key={i.injuryId}>
                <strong>{i.bodyLocation}</strong>
                <span>
                  {SEVERITY[i.severity] || i.severity} · {INJURY_STATUS[i.status] || i.status} · marked{" "}
                  {formatDate(i.markedAt)}
                </span>
              </li>
            ))}
          </ul>
        )}

        {latest && (
          <dl className="hh-facts">
            <div>
              <dt>Diagnosis</dt>
              <dd>{latest.diagnosis || "—"}</dd>
            </div>
            <div>
              <dt>Examined</dt>
              <dd>
                {formatDateTime(latest.examDate)} · {latest.vetName}
              </dd>
            </div>
            {latest.expectedRestDays ? (
              <div>
                <dt>Expected rest</dt>
                <dd>{latest.expectedRestDays} days</dd>
              </div>
            ) : null}
            {isVet && latest.notes && (
              <div>
                <dt>Treatment plan</dt>
                <dd>{latest.notes}</dd>
              </div>
            )}
          </dl>
        )}

        {lock && (
          <p className="hh-lock">
            <strong>Training lock · {lockLevelLabel(lock.lockLevel)}</strong>
            <span>
              {lock.reason} — {lock.issuedByName}, {formatDate(lock.issuedAt)}
            </span>
          </p>
        )}

        {open.length === 0 && !latest && !lock && (
          <p className="hh-muted">No medical details have been recorded yet.</p>
        )}
      </>
    );
  }

  return (
    <div className="hh-wide">
      <Card title="Current condition" hint={`Status: ${statusLabel(horse.status)} · read only`}>
        {body}
      </Card>
    </div>
  );
}