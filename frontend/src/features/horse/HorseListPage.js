// src/features/horse/HorseListPage.js
// UC7 - Horse Management, màn danh sách.
//
// Lọc và tìm kiếm làm ở trình duyệt, không gọi lại API: GET /api/horses trả về
// toàn bộ đàn một lần (vài chục con), nên lọc tại chỗ cho phản hồi tức thì và
// đỡ một vòng mạng mỗi lần gõ phím. Khi đàn lớn tới mức phải phân trang thì
// backend sẽ phải nhận tham số lọc, và chỗ cần sửa là hàm `visible` bên dưới.
import { useCallback, useEffect, useMemo, useState } from "react";
import { useNavigate } from "react-router-dom";
import { getUser, handleAuthError } from "../auth/authService";
import HorseShell, { Card, StateBlock, StatusChip } from "./HorseShell";
import {
  listHorses, canEditHorses, toStatus, toGender,
  toAge, formatWeight, STATUS_OPTIONS,
} from "./horseApi";

export default function HorseListPage() {
  const navigate = useNavigate();
  const user = getUser();
  const mayEdit = canEditHorses(user);

  const [horses, setHorses] = useState(null);
  const [error, setError] = useState("");
  const [loading, setLoading] = useState(true);
  const [query, setQuery] = useState("");
  const [status, setStatus] = useState("ALL");

  const load = useCallback(
    (signal) => {
      setLoading(true);
      setError("");
      listHorses(signal)
        .then(setHorses)
        .catch((err) => {
          if (err.name === "AbortError") return;
          if (handleAuthError(err, navigate)) return;
          setError(err.message);
        })
        .finally(() => setLoading(false));
    },
    [navigate]
  );

  useEffect(() => {
    const ctrl = new AbortController();
    load(ctrl.signal);
    return () => ctrl.abort();
  }, [load]);

  const visible = useMemo(() => {
    const q = query.trim().toLowerCase();
    return (horses || []).filter((h) => {
      if (status !== "ALL" && h.status !== status) return false;
      if (!q) return true;
      return (
        (h.horseName || "").toLowerCase().includes(q) ||
        (h.registrationCode || "").toLowerCase().includes(q) ||
        (h.ownerName || "").toLowerCase().includes(q) ||
        (h.stallNo || "").toLowerCase().includes(q)
      );
    });
  }, [horses, query, status]);

  // Đếm theo trạng thái để dán số lên từng nút lọc. Đếm trên toàn đàn, không
  // phải trên kết quả đang hiển thị - nếu không, bấm sang bộ lọc khác sẽ thấy
  // số nhảy lung tung.
  const counts = useMemo(() => {
    const c = { ALL: (horses || []).length };
    STATUS_OPTIONS.forEach((s) => {
      c[s] = (horses || []).filter((h) => h.status === s).length;
    });
    return c;
  }, [horses]);

  const actions = mayEdit ? (
    <button type="button" className="hz-btn" onClick={() => navigate("/horses/new")}>
      Add a horse
    </button>
  ) : null;

  return (
    <HorseShell
      title="Horses"
      subtitle={
        user?.role === "HORSE_OWNER"
          ? "The horses registered in your name"
          : "Every horse registered at the academy"
      }
      actions={actions}
    >
      {error && (
        <StateBlock kind="error" title={error} onRetry={() => load()}>
          Check that the backend is running on port 8080 and that you are still signed in.
        </StateBlock>
      )}

      {loading && !horses && <StateBlock title="Loading horses…" />}

      {horses && (
        <Card
          title="Registry"
          hint={
            visible.length === horses.length
              ? `${horses.length} horses`
              : `${visible.length} of ${horses.length} horses`
          }
          actions={
            <div className="hz-filters">
              <input
                className="hz-search"
                type="search"
                value={query}
                onChange={(e) => setQuery(e.target.value)}
                placeholder="Name, code, owner, stall…"
                aria-label="Search horses"
              />

              <div className="hz-segment" role="group" aria-label="Filter by status">
                <button
                  type="button"
                  aria-pressed={status === "ALL"}
                  onClick={() => setStatus("ALL")}
                >
                  All <b>{counts.ALL}</b>
                </button>

                {STATUS_OPTIONS.map((s) => (
                  <button
                    key={s}
                    type="button"
                    aria-pressed={status === s}
                    onClick={() => setStatus(s)}
                  >
                    {toStatus(s).label} <b>{counts[s]}</b>
                  </button>
                ))}
              </div>
            </div>
          }
        >
          {horses.length === 0 ? (
            <StateBlock title="No horses yet">
              {mayEdit
                ? "Use “Add a horse” to register the first one."
                : "Nothing has been registered in your name yet."}
            </StateBlock>
          ) : visible.length === 0 ? (
            <StateBlock title="Nothing matches those filters">
              Clear the search box or pick a different status.
            </StateBlock>
          ) : (
            <div className="hz-table-wrap">
              <table className="hz-table">
                <thead>
                  <tr>
                    <th>Horse</th>
                    <th>Code</th>
                    <th>Sex</th>
                    <th>Age</th>
                    <th>Weight</th>
                    <th>Stall</th>
                    <th>Owner</th>
                    <th>Status</th>
                    <th aria-label="Actions" />
                  </tr>
                </thead>

                <tbody>
                  {visible.map((h) => {
                    const st = toStatus(h.status);
                    const age = toAge(h.dateOfBirth);

                    return (
                      <tr
                        key={h.horseId}
                        className="hz-row"
                        tabIndex={0}
                        role="link"
                        onClick={() => navigate(`/horses/${h.horseId}`)}
                        onKeyDown={(e) => {
                          if (e.key === "Enter" || e.key === " ") {
                            e.preventDefault();
                            navigate(`/horses/${h.horseId}`);
                          }
                        }}
                      >
                        <td className="hz-name">{h.horseName}</td>
                        <td className="hz-mono">{h.registrationCode}</td>
                        <td>{toGender(h.horseGender)}</td>
                        <td>{age == null ? "—" : `${age}y`}</td>
                        <td>{formatWeight(h.weight)}</td>
                        <td className="hz-mono">{h.stallNo || "—"}</td>
                        <td>{h.ownerName || `#${h.ownerId}`}</td>
                        <td>
                          <StatusChip tone={st.tone} label={st.label} title={st.hint} />
                        </td>
                        <td className="hz-row-action">
                          {mayEdit && (
                            <button
                              type="button"
                              className="hz-btn hz-btn--ghost"
                              onClick={(e) => {
                                e.stopPropagation(); // nếu không, dòng cũng bắt được cú bấm
                                navigate(`/horses/${h.horseId}/edit`);
                              }}
                            >
                              Edit
                            </button>
                          )}
                        </td>
                      </tr>
                    );
                  })}
                </tbody>
              </table>
            </div>
          )}
        </Card>
      )}
    </HorseShell>
  );
}
