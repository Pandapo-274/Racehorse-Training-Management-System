// src/features/horse/StallField.js
/*
 * Ô chọn chuồng cho Manager: gợi ý chuồng trống thay vì gõ mò.
 *
 *  - Danh sách thả xuống (datalist) là các chuồng trống theo sơ đồ chuồng (V60),
 *    xếp theo dãy và số. Nút "Suggest" (và tự điền khi autoSuggest) lấy chuồng đầu tiên.
 *  - Vẫn gõ tay được; gõ tới đâu kiểm tới đó (chờ 300 ms): chuồng có ngựa nào,
 *    không có trong sơ đồ, hay còn trống.
 *
 * Như ô mã đăng ký, đây chỉ là báo SỚM. Giữa lúc kiểm và lúc bấm nút người khác vẫn
 * có thể xếp ngựa vào chuồng đó; UX_HORSE_stall trong DB chặn và backend trả lỗi vào ô này.
 */
import { useEffect, useState } from "react";
import { checkStall, listFreeStalls } from "./horseApi";

export default function StallField({ id, value, onChange, error, horseId, current, autoSuggest = false, label = "Stall" }) {
  const [free, setFree] = useState(null); // string[] | null while loading
  const [check, setCheck] = useState(null); // { stall, available, message } | null
  const [checking, setChecking] = useState(false);

  useEffect(() => {
    const ctrl = new AbortController();
    listFreeStalls(ctrl.signal)
      .then((rows) => {
        const list = rows || [];
        setFree(list);
        if (autoSuggest && !value && list.length) onChange(list[0]);
      })
      .catch(() => setFree([]));
    return () => ctrl.abort();
    // chỉ một lần khi ô xuất hiện
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, []);

  useEffect(() => {
    const stall = (value || "").trim();
    // Chuồng ngựa đang ở: không cần hỏi server.
    if (!stall || (current && stall.toUpperCase() === current.toUpperCase())) {
      setCheck(null);
      return undefined;
    }
    const ctrl = new AbortController();
    setChecking(true);
    const timer = setTimeout(() => {
      checkStall(stall, horseId, ctrl.signal)
        .then((r) => setCheck(r))
        .catch(() => setCheck(null))
        .finally(() => setChecking(false));
    }, 300);
    return () => {
      clearTimeout(timer);
      ctrl.abort();
    };
  }, [value, horseId, current]);

  const suggest = () => {
    if (free && free.length) onChange(free[0]);
  };
  const listId = `${id}-free`;

  return (
    <div className="hz-field">
      <label htmlFor={id}>{label}</label>
      <div className="hz-inline">
        <input id={id} className="hz-mono" value={value} maxLength={20} placeholder="A-08" list={listId}
               autoComplete="off" onChange={(e) => onChange(e.target.value.toUpperCase())}
               aria-invalid={!!error || (check && !check.available)} />
        <datalist id={listId}>
          {(free || []).map((s) => <option key={s} value={s} />)}
        </datalist>
        <button type="button" className="hz-btn hz-btn--ghost" onClick={suggest}
                disabled={!free || free.length === 0}>Suggest</button>
      </div>
      {error ? (
        <p className="hz-field__error">{error}</p>
      ) : current && (value || "").trim().toUpperCase() === current.toUpperCase() ? (
        <p className="hz-field__hint">Current stall. Pick a free one from the list to move the horse.</p>
      ) : checking ? (
        <p className="hz-field__hint">Checking…</p>
      ) : check ? (
        <p className={check.available ? "hz-field__ok" : "hz-field__error"}>
          {check.available ? `✓ ${check.stall} is free` : check.message}
        </p>
      ) : (
        <p className="hz-field__hint">
          {free === null ? "Loading free stalls…"
            : free.length === 0 ? "No free stall on the stable plan"
            : `${free.length} free stall${free.length === 1 ? "" : "s"}. Pick one from the list or press Suggest.`}
        </p>
      )}
    </div>
  );
}
