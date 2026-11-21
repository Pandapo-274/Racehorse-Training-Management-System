/*
 * ĐIỂM VÀO của ứng dụng React (Create React App tự tìm file src/index.js).
 *  - Nạp CSS toàn cục: token.css (biến màu, font, khoảng cách) và ui.css (nút, ô nhập dùng chung).
 *  - ReactDOM.createRoot(...).render(...) gắn cây component vào <div id="root"> trong public/index.html.
 *  - React.StrictMode: chế độ kiểm tra khi chạy dev (gọi effect hai lần để lộ lỗi), không ảnh hưởng bản build.
 *  - GoogleOAuthProvider (thư viện @react-oauth/google) cung cấp context cho nút "Sign in with Google";
 *    chỉ bọc khi có REACT_APP_GOOGLE_CLIENT_ID.
 */
import React from "react";
import ReactDOM from "react-dom/client";
import "./styles/token.css";
import "./components/ui/ui.css";
import { GoogleOAuthProvider } from "@react-oauth/google";
import App from "./App";
import { GOOGLE_CLIENT_ID } from "./features/auth/authService";

const root = ReactDOM.createRoot(document.getElementById("root"));
root.render(
  <React.StrictMode>
    {/* Chỉ bọc khi đã cấu hình Client ID (xem frontend/.env.example). Thiếu thì
        nút Google tự ẩn và đăng nhập thường vẫn chạy bình thường. */}
    {GOOGLE_CLIENT_ID ? (
      <GoogleOAuthProvider clientId={GOOGLE_CLIENT_ID}>
        <App />
      </GoogleOAuthProvider>
    ) : (
      <App />
    )}
  </React.StrictMode>
);