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
const Placeholder = ({ text }) => <h1 style={{ padding: 24 }}>{text}</h1>;

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
        <Route path="*" element={<Navigate to="/login" replace />} />
      </Routes>
    </BrowserRouter>
  );
}
