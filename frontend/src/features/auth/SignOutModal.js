import { useCallback, useEffect, useState } from "react";
import ReactDOM from "react-dom";
import { logout } from "./authService";
import "./signOutModal.css";

export default function SignOutModal({ isOpen, onConfirm, onCancel }) {
  useEffect(() => {
    if (!isOpen) return;
    const handleKeyDown = (e) => {
      if (e.key === "Escape") {
        onCancel();
      }
    };
    window.addEventListener("keydown", handleKeyDown);
    return () => window.removeEventListener("keydown", handleKeyDown);
  }, [isOpen, onCancel]);

  if (!isOpen) return null;

  const modalContent = (
    <div
      className="signout-modal-backdrop"
      onClick={onCancel}
      role="dialog"
      aria-modal="true"
      aria-labelledby="signout-modal-title"
    >
      <div
        className="signout-modal-card"
        onClick={(e) => e.stopPropagation()}
      >
        <div className="signout-modal-icon">♞</div>
        <h3 id="signout-modal-title" className="signout-modal-title">
          Sign out
        </h3>
        <p className="signout-modal-message">
          Are you sure you want to sign out?
        </p>
        <div className="signout-modal-actions">
          <button
            type="button"
            className="signout-modal-btn signout-modal-btn--cancel"
            onClick={onCancel}
          >
            Cancel
          </button>
          <button
            type="button"
            className="signout-modal-btn signout-modal-btn--confirm"
            onClick={onConfirm}
          >
            Sign out
          </button>
        </div>
      </div>
    </div>
  );

  return ReactDOM.createPortal(modalContent, document.body);
}

export function useSignOutModal(navigate) {
  const [isOpen, setIsOpen] = useState(false);

  const requestSignOut = useCallback(() => {
    setIsOpen(true);
  }, []);

  const confirmSignOut = useCallback(() => {
    setIsOpen(false);
    logout();
    if (navigate) {
      navigate("/login", { replace: true });
    }
  }, [navigate]);

  const cancelSignOut = useCallback(() => {
    setIsOpen(false);
  }, []);

  const modalElement = (
    <SignOutModal
      isOpen={isOpen}
      onConfirm={confirmSignOut}
      onCancel={cancelSignOut}
    />
  );

  return {
    requestSignOut,
    modalElement,
  };
}
