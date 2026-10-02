import { BrowserRouter, Routes, Route, Navigate } from "react-router-dom";
import LoginPage from "./features/auth/LoginPage";

const Placeholder = ({ text }) => <h1 style={{ padding: 24 }}>{text}</h1>;

export default function App() {
  return (
    <BrowserRouter>
      <Routes>
        <Route path="/login" element={<LoginPage />} />
        <Route path="/access-denied" element={<Placeholder text="Từ chối truy cập (sắp làm)" />} />
        <Route path="/manager/horses" element={<Placeholder text="Danh sách chiến mã (sắp làm)" />} />
        <Route path="/trainer/progress" element={<Placeholder text="HLV (flow 2)" />} />
        <Route path="*" element={<Navigate to="/login" replace />} />
      </Routes>
    </BrowserRouter>
  );
}