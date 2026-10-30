// src/features/horse/VitalsPanel.js
// UC8 - ảnh chụp tình trạng hiện tại của một con ngựa.
//
// Thứ tự trên màn đúng bằng thứ tự cần biết khi quyết định có cho con ngựa
// tập hay không:
//   1. có đang bị khoá huấn luyện không  -> chặn mọi việc xếp lịch
//   2. chấn thương chưa lành              -> lý do y tế
//   3. cảnh báo chưa xử lý                -> việc cần làm
//   4. buổi tập gần nhất                  -> bối cảnh
//
// Về biểu đồ nhịp tim: thang đo cố định 0-240 nhịp/phút chứ không co giãn theo
// dữ liệu. Nếu để thang tự co, cùng một cột dài bằng nhau lại mang nghĩa khác
// nhau giữa hai con ngựa, và so sánh hai hồ sơ cạnh nhau sẽ cho kết luận sai.
// 240 là mốc trên hợp lý của nhịp tim ngựa khi gắng sức tối đa.
import { Card, StatusChip } from "./HorseShell";
import { toSeverity, toMetric, toEnumLabel, formatDateTime } from "./horseApi";

const HR_MAX = 240;

const EVALUATION_TONE = {
  EXCELLENT: "good",
  GOOD: "good",
  FAIR: "warning",
  POOR: "critical",
};

/**
 * Thanh nhịp tim đỉnh so với ngưỡng trần của giáo án.
 *
 * Ngưỡng vẽ thành một vạch dọc trên cùng thang, nên "vượt ngưỡng" đọc được
 * bằng hình. Nhưng kết luận vẫn phải viết thành chữ bên cạnh: màu vàng và màu
 * xanh lá trong bảng màu của dự án gần như trùng nhau dưới mắt mù màu đỏ-lục,
 * nên không được để màu một mình mang nghĩa.
 */
function HeartRateMeter({ peak, ceiling }) {
  if (peak == null) return null;

  const pct = Math.min(100, (peak / HR_MAX) * 100);
  const ceilPct = ceiling != null ? Math.min(100, (ceiling / HR_MAX) * 100) : null;
  const over = ceiling != null && peak > ceiling;

  return (
    <div className="hz-meter">
      <div className="hz-meter__head">
        <span>Peak heart rate</span>
        <strong>
          {peak} <small>bpm</small>
        </strong>
      </div>

      <div
        className="hz-meter__track"
        role="img"
        aria-label={
          ceiling != null
            ? `Peak ${peak} beats per minute against a ceiling of ${ceiling}, on a scale to ${HR_MAX}`
            : `Peak ${peak} beats per minute on a scale to ${HR_MAX}`
        }
      >
        <div
          className={`hz-meter__fill${over ? " hz-meter__fill--over" : ""}`}
          style={{ width: `${pct}%` }}
        />
        {ceilPct != null && (
          <div className="hz-meter__ceiling" style={{ left: `${ceilPct}%` }} />
        )}
      </div>

      <div className="hz-meter__foot">
        <span>0</span>
        {ceiling != null && (
          <span className="hz-meter__verdict">
            {over ? (
              <StatusChip tone="critical" label={`Over ceiling by ${peak - ceiling}`} />
            ) : (
              <StatusChip tone="good" label={`Within ceiling (${ceiling})`} />
            )}
          </span>
        )}
        <span>{HR_MAX}</span>
      </div>
    </div>
  );
}

function Stat({ label, value, unit }) {
  return (
    <div className="hz-stat">
      <span>{label}</span>
      <strong>
        {value == null ? "—" : value}
        {value != null && unit && <small> {unit}</small>}
      </strong>
    </div>
  );
}

export default function VitalsPanel({ vitals }) {
  if (!vitals) return null;

  const { activeLock, latestSession, openAlerts = [], openInjuries = [] } = vitals;
  const nothingWrong = !activeLock && openAlerts.length === 0 && openInjuries.length === 0;

  return (
    <>
      {/* 1. Khoá huấn luyện. Đặt ngoài thẻ Card và chiếm hết chiều ngang vì nó
             phủ quyết mọi thứ bên dưới - biết con ngựa khoẻ cũng vô nghĩa nếu
             nó đang bị cấm tập. */}
      {activeLock && (
        <div className="hz-lock" role="alert">
          <div className="hz-lock__mark" aria-hidden="true">⊘</div>
          <div>
            <strong>Training lock in force — {activeLock.lockLevel}</strong>
            <p>{activeLock.reason || "No reason recorded."}</p>
            <small>Issued {formatDateTime(activeLock.issuedAt)}</small>
          </div>
        </div>
      )}

      {nothingWrong && (
        <div className="hz-clear">
          <StatusChip tone="good" label="Nothing outstanding" />
          <span>No training lock, no open injury and no unresolved alert.</span>
        </div>
      )}

      <div className="hz-vitals-grid">
        {/* 2. Chấn thương chưa lành */}
        <Card
          title="Open injuries"
          hint={openInjuries.length === 0 ? "None recorded" : `${openInjuries.length} unresolved`}
        >
          {openInjuries.length === 0 ? (
            <p className="hz-empty">No unresolved injury on file.</p>
          ) : (
            <ul className="hz-list">
              {openInjuries.map((inj, i) => {
                const sev = toSeverity(inj.severity);
                return (
                  <li key={`${inj.bodyLocation}-${i}`}>
                    <div>
                      <strong>{inj.bodyLocation || "Unspecified site"}</strong>
                      <p>
                        {toEnumLabel(inj.status)} · marked {formatDateTime(inj.markedAt)}
                      </p>
                    </div>
                    <StatusChip tone={sev.tone} label={sev.label} />
                  </li>
                );
              })}
            </ul>
          )}
        </Card>

        {/* 3. Cảnh báo chưa xử lý */}
        <Card
          title="Open alerts"
          hint={openAlerts.length === 0 ? "None raised" : `${openAlerts.length} unresolved`}
        >
          {openAlerts.length === 0 ? (
            <p className="hz-empty">No threshold has been breached.</p>
          ) : (
            <ul className="hz-list">
              {openAlerts.map((a) => {
                const sev = toSeverity(a.severity);
                return (
                  <li key={a.alertId}>
                    <div>
                      <strong>{toMetric(a.metricType)}</strong>
                      <p>
                        Reached {Number(a.actualValue)}, ceiling {Number(a.thresholdValue)} ·{" "}
                        {formatDateTime(a.triggeredAt)}
                      </p>
                    </div>
                    <StatusChip tone={sev.tone} label={sev.label} />
                  </li>
                );
              })}
            </ul>
          )}
        </Card>
      </div>

      {/* 4. Buổi tập gần nhất */}
      <Card
        title="Latest training session"
        hint={latestSession ? formatDateTime(latestSession.sessionDate) : "No session recorded"}
        actions={
          latestSession?.evaluation ? (
            <StatusChip
              tone={EVALUATION_TONE[latestSession.evaluation] || "neutral"}
              label={
                latestSession.evaluation.charAt(0) +
                latestSession.evaluation.slice(1).toLowerCase()
              }
            />
          ) : null
        }
      >
        {!latestSession ? (
          <p className="hz-empty">This horse has not been through a session yet.</p>
        ) : (
          <>
            <HeartRateMeter
              peak={latestSession.peakHeartRate}
              ceiling={latestSession.heartRateCeiling}
            />

            <div className="hz-stat-row">
              <Stat label="Average heart rate" value={latestSession.avgHeartRate} unit="bpm" />
              <Stat
                label="Recovery heart rate"
                value={latestSession.recoveryHeartRate}
                unit="bpm"
              />
              <Stat
                label="Peak speed"
                value={
                  latestSession.peakVelocity == null
                    ? null
                    : Number(latestSession.peakVelocity).toFixed(1)
                }
                unit="m/s"
              />
              <Stat label="Session status" value={toEnumLabel(latestSession.status)} />
            </div>
          </>
        )}
      </Card>
    </>
  );
}
