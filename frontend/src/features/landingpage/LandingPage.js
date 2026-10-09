import React from "react";
import { useNavigate } from "react-router-dom";
import "./landing.css";

const flows = [
  {
    number: "01",
    title: "Records & pedigree",
    description:
      "The academy manager creates horse records, enters pedigree and assigns owners. Every action passes a permission check and lands in the audit log.",
    color: "pink",
    route: "/manager/horses",
  },
  {
    number: "02",
    title: "Training plans",
    description:
      "The head trainer splits a plan into phases, assigns the stable, watches live heart rate and receives threshold alerts.",
    color: "gold",
    route: "/head-trainer",
  },
  {
    number: "03",
    title: "Medical & injury",
    description:
      "The stable reports an incident with photos, the vet examines, marks the site on a 3D model and issues a training lock.",
    color: "red",
    route: "/veterinarian",
  },
];
function Crest() {
  return (
    <img className="landing-logo" src="/logo.svg" alt="Tenma Racing Academy" />
  );
}

function SpeedLines() {
  return <div className="landing-speed-lines"></div>;
}

function LandingPage() {
  const navigate = useNavigate();

  return (
    <div className="landing-page">
      {/* =====================================================
          HERO
      ===================================================== */}
      <section className="landing-hero">
        <SpeedLines />

        <div className="hero-glow"></div>

        <div className="hero-band"></div>

        <div className="hero-content">
          <Crest />

          <div className="hero-kicker">RACING ACADEMY · ESTABLISHED 1998</div>

          <h1>TENMA</h1>

          <div className="hero-tagline">Every stride, on the record</div>

          <div className="gold-rule"></div>

          <p className="hero-description">
            Pedigree records, phased training plans, live heart-rate telemetry,
            medical files and training locks — the full training lifecycle of a
            racehorse in one system.
          </p>

          <div className="hero-buttons">
            <button
              className="landing-btn landing-btn-primary"
              onClick={() => navigate("/login")}
            >
              Login
            </button>

            <button
              className="landing-btn landing-btn-secondary"
              onClick={() => navigate("/register")}
            >
              No Account? Register Here
            </button>
          </div>
        </div>

        {/* Feature horse card */}
        <div className="hero-feature">
          <div className="feature-frame">
            <div className="feature-photo">
              <img src="/symbolirudoff.jpg" alt="Symboli Rudolf" />
            </div>

            <div className="feature-bottom">
              <div>
                <h2>SYMBOLI RUDOLF</h2>

                <div className="feature-stars">★★★★★</div>
              </div>

              <div className="g1-badge">G1</div>
            </div>
          </div>
        </div>
      </section>

      {/* =====================================================
          FLOWS
      ===================================================== */}
      <section className="landing-flows" id="operational-flows">
        <div className="section-title-ribbon">Three main features</div>

        <div className="flow-grid">
          {flows.map((flow) => (
            <article
              className={`flow-card flow-${flow.color}`}
              key={flow.number}
            >
              <div className="flow-number">{flow.number}</div>

              <h2>{flow.title}</h2>

              <div className="flow-gold-line"></div>

              <p>{flow.description}</p>
            </article>
          ))}
        </div>
      </section>

      {/* =====================================================
          STATISTICS
      ===================================================== */}
      <section className="landing-statistics">
        <SpeedLines />

        <div className="statistics-grid">
          <div className="stat-item">
            <strong>48</strong>
            <span>Horses in training</span>
          </div>

          <div className="stat-divider"></div>
         <div className="stat-item">
            <strong>12</strong>
            <span>Active plans</span>
          </div>

          <div className="stat-divider"></div>

          <div className="stat-item">
            <strong>7</strong>
            <span>G1 races this season</span>
          </div>
        </div>

        <div className="landing-footer-line"></div>

        <div className="landing-footer">
          <div className="footer-brand">
            <Crest />

            <span>Tenma Academy · Racehorse training management system</span>
          </div>

          <span className="footer-project">Coursework project · 2026</span>
        </div>
      </section>
    </div>
  );
}

export default LandingPage;
