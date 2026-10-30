// src/features/horse/HorseDetailPage.js

// Hồ sơ một con ngựa, ba thẻ: Overview và Pedigree thuộc UC7, Vitals thuộc UC8.
//
// Ba endpoint được gọi riêng theo từng thẻ chứ không gọi hết một lượt khi mở
// trang: phả hệ và chỉ số sinh tồn mỗi cái là một chuỗi truy vấn nặng ở
// backend (đệ quy nhiều đời, ghép năm bảng), mà phần lớn người mở hồ sơ chỉ
// xem thẻ đầu. Đã tải rồi thì giữ lại, chuyển qua chuyển lại không gọi thêm.

import { useCallback, useEffect, useState } from "react";
import { useNavigate, useParams, Link } from "react-router-dom";

import { getUser, handleAuthError } from "../auth/authService";

import HorseShell, { Card, StateBlock, StatusChip } from "./HorseShell";
import PedigreeTree from "./PedigreeTree";
import HorsePhoto from "./HorsePhoto";
import VitalsPanel from "./VitalsPanel";
import HorseHealthPanel from "./HorseHealthPanel";
import HorseWithdrawalPanel from "./HorseWithdrawalPanel";

import {
  getHorse, getPedigree, getVitals, canEditHorse, getHorseBasePath, toStage, isPedigreeOnly, healthApplies,
  canViewPedigree, canViewVitals, isWithdrawn, toActivity,
  toStatus, toGender, toAge, formatDate, formatDateTime, formatWeight,
} from "./horseApi";

const TABS = [
  { id: "overview", label: "Overview" },
  { id: "pedigree", label: "Pedigree" },
  { id: "vitals", label: "Vitals" },
];

const GENERATION_OPTIONS = [2, 3, 4];

function Row({ label, children }) {
  return (
    <div className="hz-detail-row">
      <span>{label}</span>
      <div>{children}</div>
    </div>
  );
}

export default function HorseDetailPage() {
  const { id } = useParams();
  const navigate = useNavigate();

  const user = getUser();

  const isVet = user?.role === "VETERINARIAN";
  const mayViewPedigree = canViewPedigree(user);
  const mayViewVitals = canViewVitals(user);
  const horseBase = getHorseBasePath(user);

  const [tab, setTab] = useState("overview");
  const [horse, setHorse] = useState(null);
  const [error, setError] = useState("");

  const [generations, setGenerations] = useState(3);
  const [pedigree, setPedigree] = useState(null);
  const [pedigreeError, setPedigreeError] = useState("");

  const [vitals, setVitals] = useState(null);
  const [vitalsError, setVitalsError] = useState("");

  const fail = useCallback(
    (set) => (err) => {
      if (err.name === "AbortError") return;

      if (handleAuthError(err, navigate)) return;

      set(err.message);
    },
    [navigate]
  );

  // Load horse
  useEffect(() => {
    const ctrl = new AbortController();

    setError("");

    getHorse(id, ctrl.signal)
      .then(setHorse)
      .catch(fail(setError));

    return () => ctrl.abort();
  }, [id, fail]);

  // Load pedigree only when the Pedigree tab is opened.
  // Reload when generations changes.
  useEffect(() => {
    if (tab !== "pedigree") return undefined;

    const ctrl = new AbortController();

    setPedigreeError("");

    getPedigree(id, generations, ctrl.signal)
      .then(setPedigree)
      .catch(fail(setPedigreeError));

    return () => ctrl.abort();
  }, [tab, id, generations, fail]);

  // Load vitals only when the Vitals tab is opened.
  // Keep the loaded result when switching tabs.
  useEffect(() => {
    if (tab !== "vitals" || vitals) return undefined;

    const ctrl = new AbortController();

    setVitalsError("");

    getVitals(id, ctrl.signal)
      .then(setVitals)
      .catch(fail(setVitalsError));

    return () => ctrl.abort();
  }, [tab, id, vitals, fail]);

  // Error state
  if (error) {
    return (
      <HorseShell title="Horse">
        <StateBlock kind="error" title={error}>
          Go back to <Link to={horseBase}>the registry</Link>.
        </StateBlock>
      </HorseShell>
    );
  }

  // Loading state
  if (!horse) {
    return (
      <HorseShell title="Horse">
        <StateBlock title="Loading horse…" />
      </HorseShell>
    );
  }

  const st = toStatus(horse.status);
  const stg = toStage(horse);
  const pedigreeOnly = isPedigreeOnly(horse);
  const mayEdit = canEditHorse(user, horse);
  const age = toAge(horse.dateOfBirth);
  const withdrawn = isWithdrawn(horse);
  const act = toActivity(horse.activityStatus);

  return (
    <HorseShell
      title={horse.horseName}
      subtitle={`${horse.registrationCode} · ${toGender(
        horse.horseGender
      )}${age == null ? "" : ` · ${age} years old`}`}
      actions={
        <>
          <StatusChip tone={stg.tone} label={stg.label} title={stg.hint} />

          {withdrawn && (
            <StatusChip tone={act.tone} label={act.label} title={act.hint} />
          )}

          {healthApplies(horse) && (
            <StatusChip tone={st.tone} label={st.label} title={st.hint} />
          )}

          {mayEdit && (
            <button
              type="button" className="hz-btn"
              onClick={() => navigate(`${horseBase}/${id}/edit`)}
            >
              Edit
            </button>
          )}

          {isVet && !pedigreeOnly && !withdrawn && (
            <button
              type="button" className="hz-btn"
              onClick={() => navigate(`/veterinarian/examination/${id}`)}
            >
              Update health status
            </button>
          )}

          {isVet && !pedigreeOnly && !withdrawn && horse.status !== "ELIGIBLE" && (
            <button
              type="button" className="hz-btn hz-btn--ghost"
              onClick={() => navigate(`/veterinarian/recovery/${id}`)}
            >
              Recovery
            </button>
          )}

          <button
            type="button" className="hz-btn hz-btn--ghost"
            onClick={() => navigate(horseBase)}
          >
            Back
          </button>
        </>
      }
    >
      {/* Tabs */}
      <div className="hz-tabs" role="tablist">
        {TABS.filter((t) =>
          (t.id !== "pedigree" || mayViewPedigree) &&
          (t.id !== "vitals" || (mayViewVitals && !pedigreeOnly))
        ).map((t) => (
          <button
            key={t.id}
            type="button"
            role="tab"
            aria-selected={tab === t.id}
            className={tab === t.id ? "active" : ""}
            onClick={() => setTab(t.id)}
          >
            {t.label}
          </button>
        ))}
      </div>

      {/* =========================
          OVERVIEW
          ========================= */}
      {tab === "overview" && (
        <div className="hz-detail-grid">
          <Card title="Identity">
            <HorsePhoto horse={horse} className="hz-photo--sm" />

            <Row label="Name">
              {horse.horseName}
            </Row>

            <Row label="Registration code">
              <span className="hz-mono">
                {horse.registrationCode}
              </span>
            </Row>

            <Row label="Sex">
              {toGender(horse.horseGender)}
            </Row>

            <Row label="Date of birth">
              {formatDate(horse.dateOfBirth)}

              {age != null && (
                <span className="hz-muted">
                  {" "}
                  · {age} years
                </span>
              )}
            </Row>

            <Row label="Breed">
              {horse.breed || "—"}
            </Row>

            <Row label="Colour">
              {horse.color || "—"}
            </Row>
          </Card>

          <Card title={pedigreeOnly ? "Record" : "Stable"}>
            {pedigreeOnly ? (
              <p className="hz-muted hz-note">
                Pedigree record only. This horse is kept as a parent or ancestor, so it has no
                stall, weight, health status or training plan.
              </p>
            ) : (
              <>
                <Row label="Weight">{formatWeight(horse.weight)}</Row>

                <Row label="Stall">
                  <span className="hz-mono">{horse.stallNo || "—"}</span>
                </Row>
              </>
            )}

            <Row label="Owner">
              {horse.ownerName || `User #${horse.ownerId}`}
            </Row>

            {healthApplies(horse) && (
              <Row label="Health status">
                <StatusChip tone={st.tone} label={st.label} title={st.hint} />

                <p className="hz-muted hz-note">
                  {isVet
                    ? "Record an examination to change it, or confirm recovery to clear it."
                    : "Set by the veterinarian, not in this form."}
                </p>
              </Row>
            )}

            <Row label="Registered">
              {formatDateTime(horse.createdAt)}
            </Row>

            <Row label="Last updated">
              {formatDateTime(horse.updatedAt)}
            </Row>
          </Card>

          {!pedigreeOnly && <HorseHealthPanel horse={horse} user={user} />}

          <HorseWithdrawalPanel horse={horse} user={user} />
        </div>
      )}

      {/* =========================
          PEDIGREE
          ========================= */}
      {tab === "pedigree" && (
        <Card
          title="Pedigree"
          hint="Sire line runs along the top of each pair, dam line along the bottom"
          actions={
            <div
              className="hz-segment"
              role="group"
              aria-label="Generations to show"
            >
              {GENERATION_OPTIONS.map((g) => (
                <button
                  key={g}
                  type="button"
                  aria-pressed={g === generations}
                  onClick={() => setGenerations(g)}
                >
                  {g} gen
                </button>
              ))}
            </div>
          }
        >
          {pedigreeError ? (
            <StateBlock
              kind="error"
              title={pedigreeError}
            />
          ) : !pedigree ? (
            <StateBlock title="Loading pedigree…" />
          ) : !pedigree.sire && !pedigree.dam ? (
            <StateBlock title="No parents on record">
              {mayEdit
                ? "Name a sire or a dam in the edit form to start building the pedigree."
                : "Nobody has recorded this horse's parents yet."}
            </StateBlock>
          ) : (
            <PedigreeTree root={pedigree} />
          )}
        </Card>
      )}

      {/* =========================
          VITALS
          ========================= */}
      {tab === "vitals" &&
        (vitalsError ? (
          <StateBlock
            kind="error"
            title={vitalsError}
          />
        ) : !vitals ? (
          <StateBlock title="Loading vitals…" />
        ) : (
          <VitalsPanel vitals={vitals} />
        ))}
    </HorseShell>
  );
}