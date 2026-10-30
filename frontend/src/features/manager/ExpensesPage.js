// src/features/manager/ExpensesPage.js
// Club Manager ghi và xem chi phí nuôi ngựa (bảng EXPENSE_LOG).
//
// Trước đây không có đường nào ghi chi phí: báo cáo chi phí của chủ ngựa và thẻ
// chi phí trên trang tổng quan chỉ đọc dữ liệu mẫu. Mỗi khoản ghi ở đây hiện
// ngay trong báo cáo chi phí của chủ con ngựa đó.
import { useCallback, useEffect, useState } from "react";
import { useNavigate } from "react-router-dom";
import { handleAuthError } from "../auth/authService";
import HorseShell, { Card, StateBlock } from "../horse/HorseShell";
import { formatDate, isPedigreeOnly, isWithdrawn, listHorses } from "../horse/horseApi";
import {
  EXPENSE_CATEGORIES, EXPENSE_PERIODS, createExpense, deleteExpense, formatMoney,
  isoDate, listExpenses, periodRange, toCostLabel,
} from "./expenseApi";
import "./expenses.css";

/** Khớp @Digits(integer = 10, fraction = 2) của ExpenseRequest.amount. */
const AMOUNT_MAX = 9999999999.99;

const emptyForm = () => ({
  horseId: "", category: "FEEDING", amount: "", expenseDate: isoDate(new Date()), note: "",
});

function validate(form) {
  const e = {};
  if (!form.horseId) e.horseId = "Choose a horse";
  const amount = Number(form.amount);
  if (form.amount === "") e.amount = "Enter the amount";
  else if (!Number.isFinite(amount) || amount < 1) e.amount = "Amount must be at least 1";
  else if (amount > AMOUNT_MAX) e.amount = "Amount is too large";
  if (!form.expenseDate) e.expenseDate = "Enter the date";
  // So sánh chuỗi yyyy-mm-dd để khỏi dính múi giờ.
  else if (form.expenseDate > isoDate(new Date())) e.expenseDate = "The date cannot be in the future";
  if (form.note.length > 255) e.note = "255 characters at most";
  return e;
}

function Field({ id, label, error, children }) {
  return (
    <div className="hz-field">
      <label htmlFor={id}>{label}</label>
      {children}
      {error && <p className="hz-field__error">{error}</p>}
    </div>
  );
}

export default function ExpensesPage() {
  const navigate = useNavigate();

  const [horses, setHorses] = useState(null);
  const [form, setForm] = useState(emptyForm);
  const [errors, setErrors] = useState({});
  const [saving, setSaving] = useState(false);
  const [formError, setFormError] = useState("");
  const [notice, setNotice] = useState("");

  const [period, setPeriod] = useState("THIS_QUARTER");
  const [horseFilter, setHorseFilter] = useState("");
  const [categoryFilter, setCategoryFilter] = useState("");
  const [data, setData] = useState(null);
  const [loadError, setLoadError] = useState("");
  const [reloadKey, setReloadKey] = useState(0);
  const [deletingId, setDeletingId] = useState(null);

  const fail = useCallback(
    (set) => (err) => {
      if (err.name === "AbortError") return;
      if (handleAuthError(err, navigate)) return;
      set(err.message);
    },
    [navigate]
  );

  // Ngựa ghi được chi phí: ngựa của club (không phải hồ sơ PEDIGREE). Ngựa còn
  // ở học viện lên trước, ngựa đã rút xuống cuối (vẫn ghi được hoá đơn cuối).
  useEffect(() => {
    const ctrl = new AbortController();
    listHorses(ctrl.signal)
      .then((rows) =>
        setHorses(
          (rows || [])
            .filter((h) => !isPedigreeOnly(h))
            .sort((a, b) =>
              Number(isWithdrawn(a)) - Number(isWithdrawn(b)) ||
              (a.horseName || "").localeCompare(b.horseName || "")
            )
        )
      )
      .catch(fail(setLoadError));
    return () => ctrl.abort();
  }, [fail]);

  useEffect(() => {
    const ctrl = new AbortController();
    const { from, to } = periodRange(period);
    setLoadError("");
    listExpenses({ horseId: horseFilter, category: categoryFilter, from, to, signal: ctrl.signal })
      .then(setData)
      .catch(fail(setLoadError));
    return () => ctrl.abort();
  }, [period, horseFilter, categoryFilter, reloadKey, fail]);

  const change = (e) => {
    const { name, value } = e.target;
    setForm((prev) => ({ ...prev, [name]: value }));
    if (errors[name]) setErrors((prev) => ({ ...prev, [name]: undefined }));
  };

  const submit = async (e) => {
    e.preventDefault();
    setNotice("");
    setFormError("");

    const found = validate(form);
    setErrors(found);
    if (Object.keys(found).length > 0) return;

    setSaving(true);
    try {
      const saved = await createExpense(form);
      setNotice(`Saved ${formatMoney(saved.amount)} for ${saved.horseName} (${toCostLabel(saved.category)}).`);
      // Giữ ngựa, hạng mục và ngày để nhập liền nhiều khoản; chỉ xoá số tiền và ghi chú.
      setForm((prev) => ({ ...emptyForm(), horseId: prev.horseId, category: prev.category, expenseDate: prev.expenseDate }));
      setReloadKey((k) => k + 1);
    } catch (err) {
      if (handleAuthError(err, navigate)) return;
      const fieldErrors = err.fieldErrors || {};
      if (Object.keys(fieldErrors).length > 0) setErrors(fieldErrors);
      else setFormError(err.message);
    } finally {
      setSaving(false);
    }
  };

  const remove = async (row) => {
    const what = `${formatMoney(row.amount)} (${toCostLabel(row.category)}) for ${row.horseName} on ${formatDate(row.expenseDate)}`;
    if (!window.confirm(`Delete ${what}? Use this only for a cost recorded by mistake.`)) return;
    setDeletingId(row.expenseId);
    setNotice("");
    try {
      await deleteExpense(row.expenseId);
      setNotice(`Deleted ${what}.`);
      setReloadKey((k) => k + 1);
    } catch (err) {
      if (!handleAuthError(err, navigate)) setLoadError(err.message);
    } finally {
      setDeletingId(null);
    }
  };

  const filters = (
    <div className="hz-filters">
      <div className="hz-segment" role="group" aria-label="Period">
        {Object.entries(EXPENSE_PERIODS).map(([key, label]) => (
          <button key={key} type="button" aria-pressed={period === key} onClick={() => setPeriod(key)}>
            {label}
          </button>
        ))}
      </div>

      <select className="hz-search" value={horseFilter} aria-label="Filter by horse"
              onChange={(e) => setHorseFilter(e.target.value)}>
        <option value="">All horses</option>
        {(horses || []).map((h) => (
          <option key={h.horseId} value={h.horseId}>{h.horseName}</option>
        ))}
      </select>

      <select className="hz-search" value={categoryFilter} aria-label="Filter by category"
              onChange={(e) => setCategoryFilter(e.target.value)}>
        <option value="">All categories</option>
        {EXPENSE_CATEGORIES.map((c) => (
          <option key={c} value={c}>{toCostLabel(c)}</option>
        ))}
      </select>
    </div>
  );

  return (
    <HorseShell
      title="Costs"
      subtitle="Feeding, veterinary, training and other costs per horse. Owners see them in their cost report."
    >
      <form onSubmit={submit} noValidate>
        <Card title="Record a cost">
          <div className="hz-form-grid">
            <Field id="exp-horse" label="Horse" error={errors.horseId}>
              <select id="exp-horse" name="horseId" value={form.horseId} onChange={change}
                      disabled={!horses} aria-invalid={!!errors.horseId}>
                <option value="">{horses ? "Choose a horse" : "Loading horses…"}</option>
                {(horses || []).map((h) => (
                  <option key={h.horseId} value={h.horseId}>
                    {h.horseName} ({h.registrationCode}){isWithdrawn(h) ? " · withdrawn" : ""}
                  </option>
                ))}
              </select>
            </Field>

            <Field id="exp-category" label="Category" error={errors.category}>
              <select id="exp-category" name="category" value={form.category} onChange={change}>
                {EXPENSE_CATEGORIES.map((c) => (
                  <option key={c} value={c}>{toCostLabel(c)}</option>
                ))}
              </select>
            </Field>

            <Field id="exp-amount" label="Amount (VND)" error={errors.amount}>
              <input id="exp-amount" name="amount" inputMode="decimal" value={form.amount}
                     onChange={change} placeholder="e.g. 2500000" aria-invalid={!!errors.amount} />
            </Field>

            <Field id="exp-date" label="Date" error={errors.expenseDate}>
              <input id="exp-date" name="expenseDate" type="date" max={isoDate(new Date())}
                     value={form.expenseDate} onChange={change} aria-invalid={!!errors.expenseDate} />
            </Field>
          </div>

          <Field id="exp-note" label="Note (optional)" error={errors.note}>
            <input id="exp-note" name="note" maxLength={255} value={form.note} onChange={change}
                   placeholder="e.g. Hay delivery for October" />
          </Field>

          {formError && <p className="hz-form-error" role="alert">{formError}</p>}
          {notice && <p className="hz-muted hz-note" role="status">{notice}</p>}

          <div className="hz-form-footer">
            <button type="submit" className="hz-btn" disabled={saving}>
              {saving ? "Saving…" : "Save cost"}
            </button>
          </div>
        </Card>
      </form>

      <Card title="Costs" hint={data ? `${data.count} entries` : undefined} actions={filters}>
        {loadError ? (
          <StateBlock kind="error" title={loadError} onRetry={() => setReloadKey((k) => k + 1)} />
        ) : !data ? (
          <StateBlock title="Loading costs…" />
        ) : (
          <>
            <div className="hz-stat-row exp-totals">
              {EXPENSE_CATEGORIES.map((c) => (
                <div className="hz-stat" key={c}>
                  <span>{toCostLabel(c)}</span>
                  <strong>{formatMoney(data.totals?.[c])}</strong>
                </div>
              ))}
              <div className="hz-stat exp-total">
                <span>Total</span>
                <strong>{formatMoney(data.total)}</strong>
              </div>
            </div>

            {data.items.length === 0 ? (
              <p className="hz-empty">No costs match these filters.</p>
            ) : (
              <div className="hz-table-wrap">
                <table className="hz-table">
                  <thead>
                    <tr>
                      <th>Date</th>
                      <th>Horse</th>
                      <th>Category</th>
                      <th className="exp-num">Amount</th>
                      <th>Note</th>
                      <th>Recorded by</th>
                      <th aria-label="Actions" />
                    </tr>
                  </thead>
                  <tbody>
                    {data.items.map((row) => (
                      <tr key={row.expenseId}>
                        <td>{formatDate(row.expenseDate)}</td>
                        <td className="hz-name">{row.horseName}</td>
                        <td>{toCostLabel(row.category)}</td>
                        <td className="exp-num">{formatMoney(row.amount)}</td>
                        <td>{row.note || "—"}</td>
                        <td>{row.recordedByName || "—"}</td>
                        <td className="hz-row-action">
                          <button type="button" className="hz-btn hz-btn--ghost"
                                  disabled={deletingId === row.expenseId} onClick={() => remove(row)}>
                            {deletingId === row.expenseId ? "Deleting…" : "Delete"}
                          </button>
                        </td>
                      </tr>
                    ))}
                  </tbody>
                </table>
              </div>
            )}

            {data.truncated && (
              <p className="hz-muted hz-note">
                Showing the latest {data.items.length} of {data.count}. Narrow the filters to see older entries.
              </p>
            )}
          </>
        )}
      </Card>
    </HorseShell>
  );
}
