// src/features/horse/HorseStayPanel.js
/*
 * Thẻ "Stay at the club" trên trang chi tiết ngựa (V48/V49).
 * Góp ý review: "chưa thể hiện thời hạn ngựa ở CLB" - thẻ này cho thấy hợp đồng đang chạy
 * (tới ngày nào, còn bao nhiêu ngày), và những gì CLB ghi lúc nhận ngựa: chiều cao, BCS,
 * năng khiếu, phát triển theo tuổi. Chủ ngựa gia hạn từ đây.
 * Ngựa đăng ký trước khi có hợp đồng thì không có thời hạn: nói rõ như vậy, không đoán.
 */
import { useEffect, useState } from "react";
import { useNavigate } from "react-router-dom";
import { Card, StatusChip } from "./HorseShell";
import { formatDate, getIntake, BCS_GROUPS } from "./horseApi";
import { toGrowth, SURFACES, DISTANCES, RUNNING_STYLES } from "./horseRequestApi";
import { contractsForHorse, toContractStatus, stayLine, REMINDER_DAYS } from "./contractApi";

function Line({ label, children }) {
  return (
    <div className="hz-detail-row">
      <span>{label}</span>
      <div>{children}</div>
    </div>
  );
}

export default function HorseStayPanel({ horse, user }) {
  const navigate = useNavigate();
  const [contracts, setContracts] = useState(null);
  const [intake, setIntake] = useState(null);

  useEffect(() => {
    const ctrl = new AbortController();
    contractsForHorse(horse.horseId, ctrl.signal).then(setContracts).catch(() => setContracts([]));
    getIntake(horse.horseId, ctrl.signal).then(setIntake).catch(() => setIntake(null));
    return () => ctrl.abort();
  }, [horse.horseId]);

  if (contracts === null) return null;

  const running = contracts.find((c) => c.status === "ACTIVE" || c.status === "EXPIRED");
  const renewal = contracts.find((c) => c.renewsContractId && ["SENT", "SIGNED", "PAID"].includes(c.status));
  const isOwner = user?.role === "HORSE_OWNER";
  const contractPath = (c) => (isOwner ? `/horse-owner/contracts/${c.contractId}` : `/manager/contracts?open=${c.contractId}`);
  const growth = intake ? toGrowth(intake.growth) : null;
  const bcs = intake?.bcsGroup ? BCS_GROUPS[intake.bcsGroup] : null;
  const endingSoon = running?.status === "ACTIVE" && running.daysLeft !== null && running.daysLeft <= REMINDER_DAYS;

  return (
    <Card title="Stay at the club" hint="From the boarding contract and the arrival check">
      {running ? (
        <>
          <Line label="Contract">
            <StatusChip tone={endingSoon ? "warning" : toContractStatus(running.status).tone}
                        label={stayLine(running, formatDate)} />
          </Line>
          <Line label="Started">{formatDate(running.startDate)} · {running.months} month{running.months === 1 ? "" : "s"}</Line>
          {running.trainingWeeks && <Line label="Training asked">{running.trainingWeeks} weeks</Line>}
        </>
      ) : (
        <p className="hz-muted hz-note">
          No contract on record: this horse was registered before stays had a term.
        </p>
      )}

      {intake && (intake.bcs || intake.heightCm || intake.surface) && (
        <>
          {intake.heightCm && <Line label="Height">{intake.heightCm} cm</Line>}
          {intake.bcs && (
            <Line label="Body condition">
              {intake.bcs}/9 {bcs && <StatusChip tone={bcs.tone} label={bcs.label} />}
            </Line>
          )}
          {growth && <Line label="Growth for age"><StatusChip tone={growth.tone} label={growth.label} /></Line>}
          {(intake.surface || intake.distancePref || intake.runningStyle) && (
            <Line label="Aptitude">
              {[SURFACES[intake.surface], DISTANCES[intake.distancePref], RUNNING_STYLES[intake.runningStyle]]
                .filter(Boolean).join(" · ")}
            </Line>
          )}
        </>
      )}

      <div className="hz-form-footer">
        {renewal && (
          <button type="button" className="hz-btn" onClick={() => navigate(contractPath(renewal))}>
            Renewal: {toContractStatus(renewal.status).label.toLowerCase()}
          </button>
        )}
        {running && !renewal && isOwner && (
          <button type="button" className="hz-btn" onClick={() => navigate(contractPath(running))}>
            Renew the stay
          </button>
        )}
        {running && user?.role === "CLUB_MANAGER" && (
          <button type="button" className="hz-btn hz-btn--ghost" onClick={() => navigate(contractPath(running))}>
            Open contract
          </button>
        )}
      </div>
    </Card>
  );
}
