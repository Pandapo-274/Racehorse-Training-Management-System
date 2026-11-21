// src/features/horse/RegistrationCodeField.js
/*
 * Ô nhập mã đăng ký cho Manager (góp ý review: "làm sao biết mã là unique khi Manager tự nhập").
 *
 *  - Nút "Suggest" (và tự điền khi autoSuggest) lấy mã kế tiếp theo định dạng TM-NNNN.
 *  - Gõ tới đâu kiểm tới đó, chờ 300 ms sau phím cuối rồi mới gọi API, để không bắn
 *    một request cho mỗi ký tự: sai định dạng / đã có ngựa dùng / còn trống.
 *
 * Đây chỉ là báo SỚM cho người dùng. Thứ thật sự giữ mã duy nhất vẫn là ràng buộc
 * UNIQUE trong DB: giữa lúc kiểm và lúc bấm nút, người khác vẫn có thể lấy mất mã;
 * khi đó backend trả lỗi đúng vào ô này.
 */
import { useEffect, useState } from "react";
import { checkRegistrationCode, nextRegistrationCode } from "./horseApi";

export default function RegistrationCodeField({ id, value, onChange, error, horseId, autoSuggest = false }) {
  const [check, setCheck] = useState(null); // { available, message } | null
  const [checking, setChecking] = useState(false);

  const suggest = () => {
    nextRegistrationCode().then((r) => onChange(r.code)).catch(() => {});
  };

  useEffect(() => {
    if (autoSuggest && !value) suggest();
    // chỉ một lần khi ô xuất hiện
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, []);

  useEffect(() => {
    const code = (value || "").trim();
    if (!code) {
      setCheck(null);
      return undefined;
    }
    const ctrl = new AbortController();
    setChecking(true);
    const timer = setTimeout(() => {
      checkRegistrationCode(code, horseId, ctrl.signal)
        .then((r) => setCheck(r))
        .catch(() => setCheck(null))
        .finally(() => setChecking(false));
    }, 300);
    return () => {
      clearTimeout(timer);
      ctrl.abort();
    };
  }, [value, horseId]);

  return (
    <div className="hz-field">
      <label htmlFor={id}>Registration code</label>
      <div className="hz-inline">
        <input id={id} className="hz-mono" value={value} maxLength={30} placeholder="TM-0751"
               onChange={(e) => onChange(e.target.value.toUpperCase())}
               aria-invalid={!!error || (check && !check.available)} />
        <button type="button" className="hz-btn hz-btn--ghost" onClick={suggest}>Suggest</button>
      </div>
      {error ? (
        <p className="hz-field__error">{error}</p>
      ) : checking ? (
        <p className="hz-field__hint">Checking…</p>
      ) : check ? (
        <p className={check.available ? "hz-field__ok" : "hz-field__error"}>
          {check.available ? `✓ ${check.code} is available` : check.message}
        </p>
      ) : (
        <p className="hz-field__hint">Format TM-0751. Unique across the club.</p>
      )}
    </div>
  );
}
