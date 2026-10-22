// src/features/notification/NotificationBell.js
// Nút chuông ở góc phải trên header, dùng chung cho cả năm dashboard.
//
// Giống ProfileCircle: mỗi dashboard truyền lớp CSS riêng của nó qua
// className (manager-notification, vet-notification, ...) nên kích thước và
// màu của nút không đổi. Chấm đỏ dùng lớp `${className}-dot` sẵn có, nhưng
// giờ chỉ hiện khi thật sự có thông báo chưa đọc.
//
// Số chưa đọc được hỏi lại mỗi 30 giây và mỗi khi người dùng quay lại tab.
// Danh sách chỉ tải khi mở bảng, để chuông không tải 20 thông báo mỗi 30 giây
// cho người không bao giờ bấm vào.
import { useCallback, useEffect, useRef, useState } from "react";
import { useNavigate } from "react-router-dom";
import { getUser, handleAuthError } from "../auth/authService";
import {
  getUnreadCount,
  listNotifications,
  markAllRead,
  markRead,
  notificationTarget,
  timeAgo,
} from "./notificationApi";
import "./notification.css";

const POLL_MS = 30000;

export default function NotificationBell({ className = "" }) {
  const navigate = useNavigate();
  const wrapRef = useRef(null);

  const [open, setOpen] = useState(false);
  const [unread, setUnread] = useState(0);
  const [items, setItems] = useState([]);
  const [loading, setLoading] = useState(false);
  const [error, setError] = useState("");

  /* Lỗi 401/403 thì đưa người dùng đi (hết phiên); lỗi khác của việc hỏi
     định kỳ thì bỏ qua lặng lẽ - chuông không nên bật hộp lỗi mỗi 30 giây
     chỉ vì mạng chập chờn. */
  const refreshCount = useCallback(
    (signal) =>
      getUnreadCount(signal)
        .then(setUnread)
        .catch((err) => {
          if (err.name !== "AbortError") handleAuthError(err, navigate);
        }),
    [navigate]
  );

  useEffect(() => {
    const controller = new AbortController();
    refreshCount(controller.signal);

    const timer = setInterval(() => {
      if (document.visibilityState === "visible") refreshCount();
    }, POLL_MS);
    const onFocus = () => refreshCount();
    window.addEventListener("focus", onFocus);

    return () => {
      controller.abort();
      clearInterval(timer);
      window.removeEventListener("focus", onFocus);
    };
  }, [refreshCount]);

  /* Mở bảng thì tải danh sách, và hỏi lại số chưa đọc cho khớp với nó. */
  useEffect(() => {
    if (!open) return undefined;
    const controller = new AbortController();
    setLoading(true);
    setError("");
    listNotifications(20, controller.signal)
      .then((list) => setItems(list || []))
      .catch((err) => {
        if (err.name === "AbortError" || handleAuthError(err, navigate)) return;
        setError(err.message || "Could not load notifications");
      })
      .finally(() => {
        if (!controller.signal.aborted) setLoading(false);
      });
    refreshCount(controller.signal);
    return () => controller.abort();
  }, [open, navigate, refreshCount]);

  /* Bấm ra ngoài hoặc nhấn Esc thì đóng. */
  useEffect(() => {
    if (!open) return undefined;
    const onDown = (e) => {
      if (wrapRef.current && !wrapRef.current.contains(e.target)) setOpen(false);
    };
    const onKey = (e) => {
      if (e.key === "Escape") setOpen(false);
    };
    document.addEventListener("mousedown", onDown);
    document.addEventListener("keydown", onKey);
    return () => {
      document.removeEventListener("mousedown", onDown);
      document.removeEventListener("keydown", onKey);
    };
  }, [open]);

  const openItem = (n) => {
    if (!n.read) {
      // Cập nhật giao diện trước, gửi sau: người dùng đang rời trang, không
      // nên bắt họ chờ. Gửi lỗi thì lần hỏi số chưa đọc tiếp theo sẽ sửa lại.
      setItems((list) => list.map((x) => (x.notificationId === n.notificationId ? { ...x, read: true } : x)));
      setUnread((c) => Math.max(0, c - 1));
      markRead(n.notificationId).catch(() => refreshCount());
    }
    const target = notificationTarget(n, getUser());
    if (target) {
      setOpen(false);
      navigate(target);
    }
  };

  const readAll = () => {
    setItems((list) => list.map((x) => ({ ...x, read: true })));
    setUnread(0);
    markAllRead().catch((err) => {
      if (!handleAuthError(err, navigate)) refreshCount();
    });
  };

  const label = unread > 0 ? `Notifications, ${unread} unread` : "Notifications";

  return (
    <div className="notif" ref={wrapRef}>
      <button
        type="button"
        className={className}
        onClick={() => setOpen((v) => !v)}
        aria-label={label}
        title={label}
        aria-haspopup="true"
        aria-expanded={open}
      >
        {unread > 0 && <span className={`${className}-dot`} />}
      </button>

      {open && (
        <div className="notif-panel" role="dialog" aria-label="Notifications">
          <div className="notif-panel__head">
            <strong>Notifications</strong>
            {unread > 0 && (
              <button type="button" className="notif-panel__readall" onClick={readAll}>
                Mark all as read
              </button>
            )}
          </div>

          {loading && items.length === 0 ? (
            <p className="notif-panel__state">Loading…</p>
          ) : error ? (
            <p className="notif-panel__state notif-panel__state--error">{error}</p>
          ) : items.length === 0 ? (
            <p className="notif-panel__state">You're all caught up.</p>
          ) : (
            <ul className="notif-list">
              {items.map((n) => (
                <li key={n.notificationId}>
                  <button
                    type="button"
                    className={`notif-item${n.read ? "" : " notif-item--unread"}`}
                    onClick={() => openItem(n)}
                  >
                    <span className="notif-item__title">{n.title}</span>
                    {n.message && <span className="notif-item__message">{n.message}</span>}
                    <span className="notif-item__time">{timeAgo(n.createdAt)}</span>
                  </button>
                </li>
              ))}
            </ul>
          )}
        </div>
      )}
    </div>
  );
}
