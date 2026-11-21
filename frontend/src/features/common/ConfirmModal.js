import { useEffect } from "react";
import ReactDOM from "react-dom";
import "./confirmModal.css";

/*
 * Hộp thoại xác nhận dùng chung ("Bạn chắc chắn chứ?"). Điều khiển hoàn toàn bằng props:
 *   isOpen      - có hiện không
 *   onConfirm   - bấm nút xác nhận; onCancel - bấm Huỷ, bấm ra ngoài nền mờ, hoặc nhấn phím Escape
 *   confirmTone - "danger" thì nút xác nhận màu đỏ (xoá, huỷ...)
 * ReactDOM.createPortal vẽ hộp thoại thẳng vào <body> (ngoài cây DOM của trang) để không bị
 * overflow/z-index của phần tử cha cắt mất.
 */
export default function ConfirmModal({
  isOpen,
  title = "Confirm action",
  message = "Are you sure you want to proceed?",
  confirmText = "Confirm",
  cancelText = "Cancel",
  confirmTone = "primary", // "primary" | "danger"
  icon,
  onConfirm,
  onCancel,
}) {
  // Lắng nghe phím Escape khi hộp thoại mở; hàm return trong useEffect gỡ listener khi đóng (cleanup).
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

  const defaultIcon = confirmTone === "danger" ? "⚠️" : "♞";

  const modalContent = (
    <div
      className="confirm-modal-backdrop"
      onClick={onCancel}
      role="dialog"
      aria-modal="true"
      aria-labelledby="confirm-modal-title"
    >
      <div
        className="confirm-modal-card"
        onClick={(e) => e.stopPropagation()}
      >
        <div className={`confirm-modal-icon ${confirmTone === "danger" ? "confirm-modal-icon--danger" : ""}`}>
          {icon || defaultIcon}
        </div>
        <h3 id="confirm-modal-title" className="confirm-modal-title">
          {title}
        </h3>
        <p className="confirm-modal-message">
          {message}
        </p>
        <div className="confirm-modal-actions">
          <button
            type="button"
            className="confirm-modal-btn confirm-modal-btn--cancel"
            onClick={onCancel}
          >
            {cancelText}
          </button>
          <button
            type="button"
            className={`confirm-modal-btn confirm-modal-btn--${confirmTone}`}
            onClick={onConfirm}
          >
            {confirmText}
          </button>
        </div>
      </div>
    </div>
  );

  return ReactDOM.createPortal(modalContent, document.body);
}