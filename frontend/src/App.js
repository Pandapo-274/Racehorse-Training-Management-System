import { BrowserRouter, Routes, Route, Navigate } from "react-router-dom";
import LoginPage from "./features/auth/LoginPage";
import AccessDeniedPage from "./features/access/AccessDeniedPage";
import ManagerDashboard from "./features/manager/ManagerDashboard"
import TrainerDashboard from "./features/trainer/TrainerDashboard"
import VetDashboard from "./features/vet/VetDashboard";
import GroomDashboard from "./features/groom/GroomDashboard";
import HorseOwnerDashboard from "./features/horse-owner/HorseOwnerDashboard";
import LandingPage from "./features/landingpage/LandingPage";
import RegisterPage from "./features/auth/RegisterPage";
import { getUser } from "./features/auth/authService";
import HorseListPage from "./features/horse/HorseListPage";
import HorseDetailPage from "./features/horse/HorseDetailPage";
import HorseFormPage from "./features/horse/HorseFormPage";
import ProfilePage from "./features/profile/ProfilePage";
import ChangePasswordPage from "./features/password/ChangePasswordPage";
import ForgotPasswordPage from "./features/password/ForgotPasswordPage";
import ResetPasswordPage from "./features/password/ResetPasswordPage";

/* UC4 - mọi vai trò đều có hồ sơ của mình, nên liệt kê cả năm thay vì thêm một
   loại guard thứ hai. Backend lấy userId từ token nên không có /profile/:id:
   một người chỉ đọc và sửa được hồ sơ của chính họ. */
const EVERY_ROLE = ["CLUB_MANAGER", "HEAD_TRAINER", "VETERINARIAN", "GROOM", "HORSE_OWNER"];
function RoleRoute({ roles, children }) {
  const user = getUser();
  if (!user) return <Navigate to="/login" replace />;
  if (!roles.includes(user.role)) return <Navigate to="/access-denied" replace />;
  return children;
}

export default function App() {
  return (
    <BrowserRouter>
      <Routes>
        {/* Landing Page */}
        <Route path="/" element={<LandingPage />} />
        {/* Authentication */}
        <Route path="/login" element={<LoginPage />} />
        <Route path="/register" element={<RegisterPage />} />
        <Route path="/access-denied" element={<AccessDeniedPage />} />
        {/* Club Manager */}
        <Route path="/manager" element={<ManagerDashboard/>} />
        {/* Head Trainer */}
        <Route path="/trainer" element={<TrainerDashboard />} />
        {/* Veterinarian */}
        <Route path="/veterinarian" element={<VetDashboard />} />
        {/* Groom */}
        <Route path="/groom" element={<GroomDashboard/>} />
        {/* Horse Owner */}
        <Route path="/horse-owner" element={<HorseOwnerDashboard/>} />

        {/* UC4 - Profile */}
        <Route path="/profile" element={<RoleRoute roles={EVERY_ROLE}><ProfilePage /></RoleRoute>} />

        {/* UC5 - Password. /forgot-password và /reset-password cố ý KHÔNG bọc
            RoleRoute: người quên mật khẩu thì chưa đăng nhập được, bọc guard
            vào là đá họ về /login - đúng cái trang họ không vào nổi.
            Tên /reset-password do backend quyết định, nó sinh liên kết
            {app.frontend-base-url}/reset-password?token=... nên không đổi được. */}
        <Route
          path="/change-password"
          element={<RoleRoute roles={EVERY_ROLE}><ChangePasswordPage /></RoleRoute>}
        />
        <Route path="/forgot-password" element={<ForgotPasswordPage />} />
        <Route path="/reset-password" element={<ResetPasswordPage />} />

        {/* UC7 + UC8 - shared horse feature, entered from each role dashboard */}
        <Route path="/manager/horses" element={<RoleRoute roles={["CLUB_MANAGER"]}><HorseListPage /></RoleRoute>} />
        <Route path="/manager/horses/new" element={<RoleRoute roles={["CLUB_MANAGER"]}><HorseFormPage /></RoleRoute>} />
        <Route path="/manager/horses/:id" element={<RoleRoute roles={["CLUB_MANAGER"]}><HorseDetailPage /></RoleRoute>} />
        <Route path="/manager/horses/:id/edit" element={<RoleRoute roles={["CLUB_MANAGER"]}><HorseFormPage /></RoleRoute>} />

        <Route path="/trainer/horses" element={<RoleRoute roles={["HEAD_TRAINER"]}><HorseListPage /></RoleRoute>} />
        <Route path="/trainer/horses/new" element={<RoleRoute roles={["HEAD_TRAINER"]}><HorseFormPage /></RoleRoute>} />
        <Route path="/trainer/horses/:id" element={<RoleRoute roles={["HEAD_TRAINER"]}><HorseDetailPage /></RoleRoute>} />
        <Route path="/trainer/horses/:id/edit" element={<RoleRoute roles={["HEAD_TRAINER"]}><HorseFormPage /></RoleRoute>} />

        <Route path="/veterinarian/horses" element={<RoleRoute roles={["VETERINARIAN"]}><HorseListPage /></RoleRoute>} />
        <Route path="/veterinarian/horses/:id" element={<RoleRoute roles={["VETERINARIAN"]}><HorseDetailPage /></RoleRoute>} />

        <Route path="/groom/horses" element={<RoleRoute roles={["GROOM"]}><HorseListPage /></RoleRoute>} />
        <Route path="/groom/horses/:id" element={<RoleRoute roles={["GROOM"]}><HorseDetailPage /></RoleRoute>} />

        <Route path="/horse-owner/horses" element={<RoleRoute roles={["HORSE_OWNER"]}><HorseListPage /></RoleRoute>} />
        <Route path="/horse-owner/horses/:id" element={<RoleRoute roles={["HORSE_OWNER"]}><HorseDetailPage /></RoleRoute>} />

        <Route path="*" element={<Navigate to="/login" replace />} />
      </Routes>
    </BrowserRouter>
  );
}
