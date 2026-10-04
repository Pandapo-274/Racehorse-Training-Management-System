import React from "react";
import { useNavigate } from "react-router-dom";
import "./access-denied.css";

function AccessDeniedPage() {
  const navigate = useNavigate();

  return (
    <div className="access-denied-page">

      {/* Background decoration */}
      <div className="access-bg-shape"></div>

      {/* Main card */}
      <div className="access-denied-card">

        {/* Top red/pink line */}
        <div className="access-denied-top-line"></div>

        {/* Error icon */}
        <div className="access-denied-icon">
          !
        </div>

        {/* Title */}
        <h1>Your account cannot open this section</h1>

        {/* Description */}
        <p className="access-denied-description">
          Horse records are restricted to the Academy Manager.
          Request access under Permissions, or contact the system administrator.
        </p>

        {/* Log information */}
        <p className="access-denied-log">
          This attempt was logged at 09:42 · 13 Sep 2026
        </p>

        {/* Back button */}
        <button
          className="access-denied-button"
          onClick={() => navigate("/login")}
        >
          Back to sign in
        </button>

      </div>
    </div>
  );
}

export default AccessDeniedPage;