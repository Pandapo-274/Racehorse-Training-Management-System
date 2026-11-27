// src/features/horse/ContactPicker.js
/*
 * V63: contract clause 8 - the insurer and a second person who may decide for the horse.
 * Both are chosen; the phone number comes with the choice and is not typed.
 *  - Insurer: from the club's list (each with its 24/7 claims line).
 *  - Second person: from the owner's own list. A new person is added once ("+ Add a person")
 *    and is there to pick on every later request, renewal and transfer.
 * value = { insurerName, insurerPhone, emergencyName, emergencyPhone }; onChange(next).
 * ownerId: only for the manager (the buyer in a transfer); an owner always sees their own list.
 */
import { useEffect, useState } from "react";
import { addOwnerContact, listInsurers, listOwnerContacts } from "./contactsApi";

const ADD = "__add";

export default function ContactPicker({ value, onChange, errors = {}, ownerId, disabled = false, idPrefix = "c" }) {
  const [insurers, setInsurers] = useState(null);       // null = loading
  const [loadError, setLoadError] = useState("");       // the list could not be read: say so, do not show an empty box
  const [reload, setReload] = useState(0);
  const [people, setPeople] = useState(null);
  const [adding, setAdding] = useState(false);
  const [draft, setDraft] = useState({ fullName: "", phone: "", relationship: "" });
  const [draftErrors, setDraftErrors] = useState({});
  const [saving, setSaving] = useState(false);

  useEffect(() => {
    const ctrl = new AbortController();
    setLoadError("");
    listInsurers(ctrl.signal)
      .then((r) => setInsurers(r || []))
      .catch((err) => {
        if (err.name === "AbortError") return;
        setInsurers([]);
        setLoadError(err.status === 404
          ? "The insurer list is not on the server yet. Restart the backend so it picks up the new version (V63)."
          : `Could not load the insurer list: ${err.message}`);
      });
    return () => ctrl.abort();
  }, [reload]);

  useEffect(() => {
    if (disabled) { setPeople([]); return undefined; }
    const ctrl = new AbortController();
    listOwnerContacts(ownerId, ctrl.signal).then((r) => setPeople(r || [])).catch((err) => {
      if (err.name !== "AbortError") setPeople([]);
    });
    return () => ctrl.abort();
  }, [ownerId, disabled]);

  const insurer = (insurers || []).find((i) => i.name === value.insurerName && i.emergencyPhone === value.insurerPhone);
  const person = (people || []).find((p) => p.fullName === value.emergencyName && p.phone === value.emergencyPhone);
  const set = (patch) => onChange({ ...value, ...patch });

  const pickInsurer = (e) => {
    const i = (insurers || []).find((x) => String(x.insurerId) === e.target.value);
    set({ insurerName: i ? i.name : "", insurerPhone: i ? i.emergencyPhone : "" });
  };
  const pickPerson = (e) => {
    if (e.target.value === ADD) { setAdding(true); return; }
    const p = (people || []).find((x) => String(x.contactId) === e.target.value);
    set({ emergencyName: p ? p.fullName : "", emergencyPhone: p ? p.phone : "" });
  };

  const save = async () => {
    const e = {};
    if (!draft.fullName.trim()) e.fullName = "Give the person's full name";
    if (!/^[0-9+()\s.-]{8,20}$/.test(draft.phone.trim())) e.phone = "Enter a valid phone number";
    setDraftErrors(e);
    if (Object.keys(e).length) return;
    setSaving(true);
    try {
      const p = await addOwnerContact({ ownerId, ...draft });
      setPeople((list) => [...(list || []).filter((x) => x.contactId !== p.contactId), p]
        .sort((a, b) => a.fullName.localeCompare(b.fullName)));
      set({ emergencyName: p.fullName, emergencyPhone: p.phone });
      setAdding(false);
      setDraft({ fullName: "", phone: "", relationship: "" });
    } catch (err) {
      setDraftErrors(err.fieldErrors && Object.keys(err.fieldErrors).length ? err.fieldErrors : { fullName: err.message });
    } finally {
      setSaving(false);
    }
  };

  const insurerError = errors.insurerName || errors.insurerPhone;
  const personError = errors.emergencyName || errors.emergencyPhone;
  const unlisted = (name, found) => name && !found
    ? <p className="hz-field__hint">“{name}” is not on the list: choose again.</p> : null;

  return (
    <div className="hz-form-grid">
      <div className="hz-field">
        <label htmlFor={`${idPrefix}-insurer`}>Insurer</label>
        <select id={`${idPrefix}-insurer`} value={insurer ? insurer.insurerId : ""} onChange={pickInsurer}
                disabled={disabled || insurers === null} aria-invalid={!!insurerError || !!loadError}>
          <option value="">
            {insurers === null ? "Loading insurers…"
              : insurers.length === 0 && !loadError ? "No insurer on the club's list yet" : "Choose the insurer…"}
          </option>
          {(insurers || []).map((i) => <option key={i.insurerId} value={i.insurerId}>{i.name}</option>)}
        </select>
        {loadError ? (
          <p className="hz-field__error">
            {loadError}{" "}
            <button type="button" className="hz-btn hz-btn--ghost hz-btn--sm" onClick={() => setReload((k) => k + 1)}>
              Try again
            </button>
          </p>
        ) : insurers && insurers.length === 0 ? (
          <p className="hz-field__error">The club has no insurer on its list. Ask the Club Manager.</p>
        ) : insurerError ? <p className="hz-field__error">{insurerError}</p>
          : insurer ? <p className="hz-field__hint">Emergency line: <span className="hz-mono">{insurer.emergencyPhone}</span></p>
          : unlisted(value.insurerName, insurer)}
      </div>

      <div className="hz-field">
        <label htmlFor={`${idPrefix}-person`}>Second person to call</label>
        <select id={`${idPrefix}-person`} value={adding ? ADD : person ? person.contactId : ""} onChange={pickPerson}
                disabled={disabled || people === null} aria-invalid={!!personError}>
          <option value="">{people && people.length === 0 ? "No one yet - add a person…" : "Choose a person…"}</option>
          {(people || []).map((p) => (
            <option key={p.contactId} value={p.contactId}>
              {p.fullName}{p.relationship ? ` (${p.relationship})` : ""}
            </option>
          ))}
          <option value={ADD}>+ Add a person…</option>
        </select>
        {personError ? <p className="hz-field__error">{personError}</p>
          : person ? <p className="hz-field__hint">Phone: <span className="hz-mono">{person.phone}</span></p>
          : unlisted(value.emergencyName, person)
            || <p className="hz-field__hint">Someone who can decide for the horse if the club cannot reach the owner.</p>}
      </div>

      {adding && (
        <div className="hz-field" style={{ gridColumn: "1 / -1" }}>
          <div className="hz-form-grid">
            <div className="hz-field">
              <label htmlFor={`${idPrefix}-new-name`}>Full name</label>
              <input id={`${idPrefix}-new-name`} maxLength={100} value={draft.fullName}
                     onChange={(e) => setDraft((d) => ({ ...d, fullName: e.target.value }))} />
              {draftErrors.fullName && <p className="hz-field__error">{draftErrors.fullName}</p>}
            </div>
            <div className="hz-field">
              <label htmlFor={`${idPrefix}-new-phone`}>Phone</label>
              <input id={`${idPrefix}-new-phone`} type="tel" maxLength={20} value={draft.phone}
                     onChange={(e) => setDraft((d) => ({ ...d, phone: e.target.value }))} />
              {draftErrors.phone && <p className="hz-field__error">{draftErrors.phone}</p>}
            </div>
            <div className="hz-field">
              <label htmlFor={`${idPrefix}-new-rel`}>Relationship (optional)</label>
              <input id={`${idPrefix}-new-rel`} maxLength={50} value={draft.relationship} placeholder="e.g. sister, farm manager"
                     onChange={(e) => setDraft((d) => ({ ...d, relationship: e.target.value }))} />
            </div>
          </div>
          <div className="hz-inline">
            <button type="button" className="hz-btn" disabled={saving} onClick={save}>
              {saving ? "Saving…" : "Save and choose"}
            </button>
            <button type="button" className="hz-btn hz-btn--ghost" disabled={saving}
                    onClick={() => { setAdding(false); setDraftErrors({}); }}>Cancel</button>
          </div>
          <p className="hz-field__hint">Saved to the list, so it can be picked next time without typing.</p>
        </div>
      )}
    </div>
  );
}
