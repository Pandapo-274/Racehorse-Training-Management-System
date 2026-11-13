// src/features/common/ResultLine.js
// The outcome of an action, shown right under the button that did it:
// green with a tick when it worked, red with a cross when it did not.
// The text always says what happened and to whom, e.g.
// "Transferred Thunder Bay to Tran Duc Minh." / "Could not transfer Thunder Bay to Tran Duc Minh: ..."
import "./resultLine.css";

export default function ResultLine({ result }) {
  if (!result || !result.text) return null;
  return (
    <p className={`result-line ${result.ok ? "result-line--ok" : "result-line--fail"}`}
       role={result.ok ? "status" : "alert"}>
      <span className="result-line__icon" aria-hidden="true">{result.ok ? "✓" : "✕"}</span>
      <span>{result.text}</span>
    </p>
  );
}

/** Small helpers so every screen words results the same way. */
export const ok = (text) => ({ ok: true, text });
export const fail = (text) => ({ ok: false, text });
