// src/features/common/AppDialog.js
// One dialog for the whole app, in the same look as the Sign out dialog, to
// replace the browser's window.confirm / window.prompt boxes (grey, unstyled,
// and different in every browser).
//
//   const dialog = useAppDialog();
//   ...
//   if (!(await dialog.confirm({ title: "Suspend account", message: "...", confirmLabel: "Suspend", danger: true }))) return;
//   const value = await dialog.prompt({ title: "Max horses", label: "Horses", defaultValue: "10",
//                                       inputType: "number", validate: (v) => (v ? null : "Required"),
//                                       onSubmit: async (v) => { await api(v); } });
//   ...
//   return <>{dialog.element} ... </>;
//
// prompt(): validate() runs before closing; onSubmit() (optional) runs the
// real request and, if it throws, its message is shown INSIDE the dialog so the
// user can fix the value instead of starting over. Resolves with the value, or
// null when cancelled.
import { useCallback, useEffect, useRef, useState } from "react";
import ReactDOM from "react-dom";
import "./appDialog.css";

function DialogView({ config, onClose }) {
  const [value, setValue] = useState(config.defaultValue ?? "");
  const [error, setError] = useState("");
  const [busy, setBusy] = useState(false);
  const inputRef = useRef(null);
  const isPrompt = config.kind === "prompt";

  useEffect(() => {
    const onKey = (e) => { if (e.key === "Escape" && !busy) onClose(null); };
    window.addEventListener("keydown", onKey);
    return () => window.removeEventListener("keydown", onKey);
  }, [busy, onClose]);

  useEffect(() => {
    if (isPrompt && inputRef.current) {
      inputRef.current.focus();
      inputRef.current.select();
    }
  }, [isPrompt]);

  const submit = async (e) => {
    e?.preventDefault();
    if (!isPrompt) { onClose(true); return; }
    const v = String(value).trim();
    const problem = config.validate ? config.validate(v) : (v ? null : "This field is required");
    if (problem) { setError(problem); return; }
    if (config.onSubmit) {
      setBusy(true);
      setError("");
      try {
        await config.onSubmit(v);
      } catch (err) {
        setError(err?.message || "Something went wrong");
        setBusy(false);
        return;
      }
    }
    onClose(v);
  };

  return ReactDOM.createPortal(
    <div className="app-dialog-backdrop" onClick={() => !busy && onClose(isPrompt ? null : false)}
         role="dialog" aria-modal="true" aria-labelledby="app-dialog-title">
      <form className="app-dialog-card" onClick={(e) => e.stopPropagation()} onSubmit={submit} noValidate>
        <div className={`app-dialog-icon${config.danger ? " app-dialog-icon--danger" : ""}`}>
          {config.icon || "♞"}
        </div>
        <h3 id="app-dialog-title" className="app-dialog-title">{config.title}</h3>
        {config.message && <p className="app-dialog-message">{config.message}</p>}

        {isPrompt && (
          <label className="app-dialog-field">
            {config.label && <span>{config.label}</span>}
            <input
              ref={inputRef}
              type={config.inputType || "text"}
              min={config.min}
              max={config.max}
              value={value}
              disabled={busy}
              onChange={(e) => { setValue(e.target.value); setError(""); }}
            />
            {config.hint && !error && <small className="app-dialog-hint">{config.hint}</small>}
          </label>
        )}
        {error && <p className="app-dialog-error" role="alert">{error}</p>}

        <div className="app-dialog-actions">
          <button type="button" className="app-dialog-btn app-dialog-btn--cancel" disabled={busy}
                  onClick={() => onClose(isPrompt ? null : false)}>
            {config.cancelLabel || "Cancel"}
          </button>
          <button type="submit" className={`app-dialog-btn ${config.danger ? "app-dialog-btn--danger" : "app-dialog-btn--confirm"}`}
                  disabled={busy}>
            {busy ? "Saving…" : (config.confirmLabel || (isPrompt ? "Save" : "Confirm"))}
          </button>
        </div>
      </form>
    </div>,
    document.body,
  );
}

export function useAppDialog() {
  const [state, setState] = useState(null); // { config, resolve }

  const open = useCallback((kind, config) => new Promise((resolve) => {
    setState({ config: { ...config, kind }, resolve });
  }), []);

  const confirm = useCallback((config) => open("confirm", config), [open]);
  const prompt = useCallback((config) => open("prompt", config), [open]);

  const close = useCallback((result) => {
    setState((s) => {
      if (s) s.resolve(result);
      return null;
    });
  }, []);

  const element = state ? <DialogView key={state.config.title} config={state.config} onClose={close} /> : null;
  return { confirm, prompt, element };
}
