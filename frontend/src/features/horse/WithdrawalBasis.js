// src/features/horse/WithdrawalBasis.js
/*
 * V62: what a withdrawal means, before anyone decides - shown to the owner while choosing the
 * collection day, and to the manager before approving (same review-feedback point as the
 * registration "Review basis": a decision needs the facts on screen).
 *
 * Contract clause 9: 15 days' notice; months that start after it are refunded.
 */
import { StatusChip } from "./HorseShell";
import { formatDate } from "./horseApi";
import { formatVnd } from "./horseRequestApi";
import { plural } from "./withdrawalApi";

function Line({ label, children }) {
  return (
    <div className="hz-detail-row">
      <span>{label}</span>
      <div>{children}</div>
    </div>
  );
}

function stayText(b) {
  switch (b.stay) {
    case "ACTIVE":
      return `Contract #${b.contractId} · until ${formatDate(b.stayEnd)}`
        + (b.daysLeft != null ? ` (${plural(b.daysLeft, "day")} left)` : "");
    case "EXPIRED":
      return `Ended on ${formatDate(b.stayEnd)}, not renewed`;
    case "TAKEOVER":
      return "Changed owner; the new owner has not signed yet";
    default:
      return "No contract: registered before stays had a term";
  }
}

export default function WithdrawalBasis({ basis: b }) {
  if (!b) return null;
  const races = [];
  if (b.feesRefunded > 0) races.push(`${plural(b.feesRefunded, "fee")} cancelled`);
  if (b.feesRefundedByClub > 0) races.push(`${plural(b.feesRefundedByClub, "fee")} paid online, refunded by the club`);
  if (b.feesKept > 0) races.push(`${plural(b.feesKept, "fee")} kept: entries closed`);

  return (
    <div className="hz-withdraw-basis">
      <Line label="Stay">{stayText(b)}</Line>
      {b.stay === "ACTIVE" && (
        <>
          <Line label="Paid until">
            {formatDate(b.noticeUntil)}
            <p className="hz-field__hint">15 days' notice from the request, or the collection day if later (clause 9)</p>
          </Line>
          <Line label="Refund">
            {b.refundAmount > 0
              ? <StatusChip tone="good" label={`${formatVnd(b.refundAmount)} for ${plural(b.refundMonths, "month")} not started`} />
              : <span className="hz-muted">None: every month has started by then</span>}
          </Line>
        </>
      )}
      {b.renewalOpen && (
        <Line label="Renewal"><StatusChip tone="warning" label="A renewal not signed or paid yet is cancelled" /></Line>
      )}
      <Line label="Unpaid costs">
        {b.unpaidCosts > 0
          ? <StatusChip tone="warning" label={`${plural(b.unpaidCosts, "cost")}, ${formatVnd(b.unpaidAmount)} - still owed after leaving`} />
          : <span className="hz-muted">None</span>}
        {b.disputedCosts > 0 && <p className="hz-field__hint">{plural(b.disputedCosts, "cost")} disputed</p>}
      </Line>
      {b.activeLock && (
        <Line label="Vet lock">
          <StatusChip tone="critical" label="Under a training lock" />
          <p className="hz-field__hint">{b.activeLock}. Ask the vet before the horse travels.</p>
        </Line>
      )}
      <Line label="Called off">
        {plural(b.scheduledSessions, "scheduled session")} · {plural(b.openPlans, "training plan")}
        {" · "}{plural(b.upcomingRaces, "race entry").replace("entrys", "entries")}
        {races.length > 0 && <p className="hz-field__hint">Race fees: {races.join("; ")}</p>}
      </Line>
      {b.inSessionNow && (
        <p className="hz-field__error">In a training session right now: approve after it ends.</p>
      )}
    </div>
  );
}
