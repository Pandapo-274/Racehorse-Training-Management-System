// src/features/manager/AuditLogPage.js
// Club Manager: "Theo dõi nhật ký thao tác hệ thống (Audit Log)".
//
// Read-only on purpose - there is no way to edit or delete a log line, which is
// what makes the log worth trusting. Denied rows (wrong password, an attempt to
// use something the role is not allowed to) are highlighted, and the numbers at
// the top show the last 24 hours at a glance.
import { useEffect, useState } from "react";
import { useNavigate } from "react-router-dom";
import { handleAuthError } from "../auth/authService";
import HorseShell, { Card, StateBlock, StatusChip } from "../horse/HorseShell";
import {
  ACTION_GROUPS, PERIODS, formatAgo, formatFullTime, getAuditLogs, periodStart,
  describeLog, toActionText, toRoleText,
} from "./auditLogApi";
import "./audit-log.css";

// Bộ lọc ban đầu: 7 ngày gần nhất, mọi kết quả, mọi nhóm hành động, mọi người dùng.
const EMPTY_FILTERS = { period: "7D", result: "", action: "", user: "" };

// Ba ô số an toàn trong 24 giờ (backend chỉ gửi summary ở trang đầu).
function SummaryTiles({ summary, onShowDenied }) {
  if (!summary) return null;
  const tiles = [
    { key: "failed", value: summary.failedLogins24h, label: "Failed sign-ins", tone: summary.failedLogins24h ? "bad" : "ok" },
    { key: "denied", value: summary.deniedAccess24h, label: "Blocked access attempts", tone: summary.deniedAccess24h ? "bad" : "ok" },
    { key: "events", value: summary.events24h, label: "Actions logged", tone: "plain" },
  ];
  return (
    <div className="al-tiles">
      {tiles.map((t) => (
        <button
          key={t.key}
          type="button"
          className={`al-tile al-tile--${t.tone}`}
          onClick={t.key === "events" ? undefined : onShowDenied}
          disabled={t.key === "events"}
        >
          <strong>{t.value}</strong>
          <span>{t.label}</span>
          <small>last 24 hours</small>
        </button>
      ))}
    </div>
  );
}

// Bảng nhật ký: thời gian, người làm (kèm vai trò), hành động (toActionText), mô tả (describeLog), kết quả.
// Dòng DENIED được tô nổi bật.
function LogTable({ items }) {
  return (
    <div className="al-scroll">
      <table className="al-table">
        <thead>
          <tr>
            <th scope="col">Time</th>
            <th scope="col">Who</th>
            <th scope="col">Action</th>
            <th scope="col">Description</th>
            <th scope="col">Result</th>
          </tr>
        </thead>
        <tbody>
          {items.map((r) => {
            const d = describeLog(r);
            return (
            <tr key={r.logId} className={r.result === "DENIED" ? "is-denied" : ""}>
              <td className="al-time">
                {formatFullTime(r.createdAt)}
                <small>{formatAgo(r.createdAt)}</small>
              </td>
              <td>
                {r.userId == null ? (
                  <span className="al-muted">Unknown user</span>
                ) : (
                  <>
                    {r.fullName}
                    <small>@{r.username} · {toRoleText(r.role)}</small>
                  </>
                )}
              </td>
              <td title={r.action}>{toActionText(r.action, r.result)}</td>
              <td className="al-desc">
                {d.main}
                {d.extra && <small className="al-detail">{d.extra}</small>}
              </td>
              <td>
                <StatusChip
                  tone={r.result === "DENIED" ? "critical" : "good"}
                  label={r.result === "DENIED" ? "Denied" : "Success"}
                />
              </td>
            </tr>
            );
          })}
        </tbody>
      </table>
    </div>
  );
}

/*
 * Trang /manager/audit-log. Đọc GET /api/audit-logs theo bộ lọc, phân trang kiểu keyset bằng nút "Load more"
 * (nextBeforeId). Không có nút sửa / xoá: nhật ký chỉ được ghi thêm (UserRepository.writeAudit ở backend).
 */
export default function AuditLogPage() {
  const navigate = useNavigate();
  const [filters, setFilters] = useState(EMPTY_FILTERS);
  const [userInput, setUserInput] = useState("");
  const [items, setItems] = useState(null);
  const [summary, setSummary] = useState(null);
  const [nextBeforeId, setNextBeforeId] = useState(null);
  const [error, setError] = useState("");
  const [loadingMore, setLoadingMore] = useState(false);
  const [reloadKey, setReloadKey] = useState(0);

  // Gõ tên thì đợi một nhịp rồi mới lọc, khỏi gọi API theo từng phím.
  // Debounce: mỗi lần gõ thì huỷ hẹn giờ cũ và hẹn lại 350 ms; chỉ khi ngừng gõ đủ lâu filters.user mới đổi
  // và effect tải dữ liệu bên dưới mới chạy.
  useEffect(() => {
    const t = setTimeout(() => setFilters((f) => (f.user === userInput.trim() ? f : { ...f, user: userInput.trim() })), 350);
    return () => clearTimeout(t);
  }, [userInput]);

  const query = (f) => ({
    since: periodStart(f.period),
    result: f.result,
    action: f.action,
    user: f.user,
  });

  // Đổi bộ lọc -> tải lại trang đầu (beforeId = null), xoá danh sách cũ để hiện "đang tải".
  useEffect(() => {
    const ctrl = new AbortController();
    setError("");
    setItems(null);
    getAuditLogs(query(filters), null, ctrl.signal)
      .then((page) => {
        setItems(page.items);
        setSummary(page.summary);
        setNextBeforeId(page.nextBeforeId);
      })
      .catch((err) => {
        if (err.name === "AbortError") return;
        if (handleAuthError(err, navigate)) return;
        setError(err.message);
      });
    return () => ctrl.abort();
  }, [filters, navigate, reloadKey]);

  // Tải trang kế: nối thêm vào cuối danh sách đang có.
  const loadMore = () => {
    setLoadingMore(true);
    getAuditLogs(query(filters), nextBeforeId)
      .then((page) => {
        setItems((cur) => [...(cur || []), ...page.items]);
        setNextBeforeId(page.nextBeforeId);
      })
      .catch((err) => {
        if (!handleAuthError(err, navigate)) setError(err.message);
      })
      .finally(() => setLoadingMore(false));
  };

  // set("period") trả về hàm onChange cho ô chọn tương ứng (hàm sinh hàm, đỡ viết 4 handler giống nhau).
  const set = (key) => (e) => setFilters((f) => ({ ...f, [key]: e.target.value }));
  const filtered = filters.result || filters.action || filters.user || filters.period !== EMPTY_FILTERS.period;

  return (
    <HorseShell
      title="Audit log"
      subtitle="Who did what, and when. Read-only: log lines cannot be edited or deleted."
      actions={
        <button type="button" className="hz-btn hz-btn--ghost" onClick={() => setReloadKey((k) => k + 1)}>
          Refresh
        </button>
      }
    >
      <SummaryTiles
        summary={summary}
        onShowDenied={() => setFilters((f) => ({ ...f, period: "24H", result: "DENIED" }))}
      />

      <Card>
        <div className="al-filters">
          <label>
            Period
            <select value={filters.period} onChange={set("period")}>
              {PERIODS.map((p) => <option key={p.key} value={p.key}>{p.label}</option>)}
            </select>
          </label>
          <label>
            Result
            <select value={filters.result} onChange={set("result")}>
              <option value="">All results</option>
              <option value="DENIED">Denied only</option>
              <option value="SUCCESS">Success only</option>
            </select>
          </label>
          <label>
            Action
            <select value={filters.action} onChange={set("action")}>
              {ACTION_GROUPS.map((g) => <option key={g.value} value={g.value}>{g.label}</option>)}
            </select>
          </label>
          <label className="al-filters__user">
            Person
            <input
              type="search"
              value={userInput}
              onChange={(e) => setUserInput(e.target.value)}
              placeholder="Name or username"
            />
          </label>
          {filtered && (
            <button
              type="button"
              className="hz-clear"
              onClick={() => { setUserInput(""); setFilters(EMPTY_FILTERS); }}
            >
              Clear filters
            </button>
          )}
        </div>
      </Card>

      {error && (
        <StateBlock kind="error" title="Could not load the audit log" onRetry={() => setReloadKey((k) => k + 1)}>
          {error}
        </StateBlock>
      )}
      {!error && items === null && <StateBlock title="Loading the audit log…" />}

      {items && (
        <Card
          title="Log"
          hint={`${items.length}${nextBeforeId ? "+" : ""} ${items.length === 1 ? "entry" : "entries"} · newest first · times in your time zone`}
        >
          {items.length === 0 ? (
            <p className="hz-empty">No log entries match these filters.</p>
          ) : (
            <>
              <LogTable items={items} />
              {nextBeforeId && (
                <div className="al-more">
                  <button type="button" className="hz-btn hz-btn--ghost" onClick={loadMore} disabled={loadingMore}>
                    {loadingMore ? "Loading…" : "Load more"}
                  </button>
                </div>
              )}
            </>
          )}
        </Card>
      )}
    </HorseShell>
  );
}
