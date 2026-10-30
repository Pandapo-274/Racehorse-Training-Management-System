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