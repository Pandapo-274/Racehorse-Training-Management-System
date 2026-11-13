// src/features/horse/HorseMedicalHistoryTab.js
// Toàn bộ lịch sử y tế, bệnh án, điều trị, chấn thương, khóa tập và chuyển viện của ngựa.
// Hỗ trợ thanh lọc thời gian (All, 30 ngày, 3 tháng, 6 tháng, 1 năm, tùy chọn khoảng ngày),
// lọc theo loại sự kiện và tìm kiếm theo từ khóa.

import { useEffect, useMemo, useState } from "react";
import {
  listMedicalRecords,
  listInjuries,
  listLocksForHorse,
  listTreatmentsForHorse,
  listExternalReferrals,
  listAllIncidentReportsForHorse,
  lockLevelLabel,
  statusLabel,
} from "../vet/vetApi";
import { formatDate, formatDateTime } from "./horseApi";
import "./horse-health.css";

const EVENT_TYPE_OPTIONS = [
  { value: "ALL", label: "Tất cả sự kiện (All)" },
  { value: "EXAM", label: "Khám bệnh & Chẩn đoán (Exams)" },
  { value: "INJURY", label: "Chấn thương & Phục hồi (Injuries)" },
  { value: "TREATMENT", label: "Đơn thuốc & Điều trị (Treatments)" },
  { value: "LOCK", label: "Lệnh khoá / Mở tập (Locks)" },
  { value: "REFERRAL", label: "Chuyển viện ngoài (Referrals)" },
  { value: "INCIDENT", label: "Sự cố sân tập (Incidents)" },
];

const TIME_PRESETS = [
  { value: "all", label: "Toàn bộ (All Time)" },
  { value: "30d", label: "30 ngày gần đây" },
  { value: "90d", label: "3 tháng qua" },
  { value: "180d", label: "6 tháng qua" },
  { value: "365d", label: "1 năm qua" },
  { value: "custom", label: "Tùy chọn ngày..." },
];

const SEVERITY_LABEL = {
  GRADE_1: "Grade 1 (Nhẹ)",
  GRADE_2: "Grade 2 (Trung bình)",
  GRADE_3: "Grade 3 (Nặng)",
  GRADE_4: "Grade 4 (Đặc biệt nghiêm trọng)",
};

export default function HorseMedicalHistoryTab({ horse, user }) {
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState("");
  const [events, setEvents] = useState([]);

  // Filters
  const [timePreset, setTimePreset] = useState("all");
  const [customFrom, setCustomFrom] = useState("");
  const [customTo, setCustomTo] = useState("");
  const [typeFilter, setTypeFilter] = useState("ALL");
  const [searchQuery, setSearchQuery] = useState("");

  const isVet = user?.role === "VETERINARIAN";

  useEffect(() => {
    if (!horse?.horseId) return;
    const ctrl = new AbortController();
    setLoading(true);
    setError("");

    Promise.allSettled([
      listMedicalRecords(horse.horseId, ctrl.signal),
      listInjuries(horse.horseId, ctrl.signal),
      listLocksForHorse(horse.horseId, ctrl.signal),
      listTreatmentsForHorse(horse.horseId, ctrl.signal),
      listExternalReferrals({ horseId: horse.horseId, status: "ALL" }, ctrl.signal),
      listAllIncidentReportsForHorse(horse.horseId, ctrl.signal),
    ])
      .then(([examsRes, injuriesRes, locksRes, rxRes, referralsRes, incidentsRes]) => {
        const unified = [];

        // 1. Hồ sơ khám bệnh (Medical records / Exams)
        if (examsRes.status === "fulfilled" && Array.isArray(examsRes.value)) {
          examsRes.value.forEach((r) => {
            const date = new Date(r.examDate || r.createdAt);
            unified.push({
              id: `exam-${r.recordId}`,
              type: "EXAM",
              date,
              categoryLabel: r.recordType || "Khám bệnh",
              title: r.diagnosis ? `Chẩn đoán: ${r.diagnosis}` : "Khám lâm sàng tổng quát",
              tone: "blue",
              actorName: r.vetName ? `BS. ${r.vetName}` : "Bác sĩ thú y",
              actorRole: "Bác sĩ khám",
              status: r.healthStatus ? statusLabel(r.healthStatus) : "Đã hoàn thành",
              searchableText: `${r.diagnosis || ""} ${r.notes || ""} ${r.vetName || ""}`,
              details: {
                diagnosis: r.diagnosis || "—",
                healthStatus: r.healthStatus,
                injuryConfirmed: r.injuryConfirmed,
                expectedRestDays: r.expectedRestDays,
                weight: r.weight,
                notes: r.notes,
              },
            });
          });
        }

        // 2. Chấn thương (Injuries)
        if (injuriesRes.status === "fulfilled" && Array.isArray(injuriesRes.value)) {
          injuriesRes.value.forEach((i) => {
            const date = new Date(i.markedAt);
            const isRecovered = i.status === "RECOVERED";
            unified.push({
              id: `injury-${i.injuryId}`,
              type: "INJURY",
              date,
              categoryLabel: "Chấn thương",
              title: `Tổn thương: ${i.bodyLocation}`,
              tone: isRecovered ? "green" : "red",
              actorName: isRecovered ? `Đã hồi phục (${formatDate(i.recoveredAt)})` : "Đang điều trị",
              actorRole: "Trạng thái",
              status: isRecovered ? "Đã lành bệnh" : "Đang dưỡng thương",
              searchableText: `${i.bodyLocation} ${i.severity}`,
              details: {
                bodyLocation: i.bodyLocation,
                severity: SEVERITY_LABEL[i.severity] || i.severity,
                coordinate: `(X: ${i.coordinateX}%, Y: ${i.coordinateY}%)`,
                isRecovered,
                recoveredAt: i.recoveredAt,
              },
            });
          });
        }

        // 3. Đơn thuốc (Treatments / Prescriptions)
        if (rxRes.status === "fulfilled" && Array.isArray(rxRes.value)) {
          rxRes.value.forEach((t) => {
            const date = new Date(t.startDate);
            const isEnded = t.status === "ENDED";
            unified.push({
              id: `treatment-${t.treatmentId}`,
              type: "TREATMENT",
              date,
              categoryLabel: "Đơn thuốc",
              title: `Thuốc: ${t.medicineName}`,
              tone: "purple",
              actorName: `Liều: ${t.dosage || "—"} (${t.frequency || "—"})`,
              actorRole: "Chỉ định",
              status: isEnded ? "Đã hoàn thành đợt" : "Đang sử dụng",
              searchableText: `${t.medicineName} ${t.dosage || ""} ${t.instruction || ""}`,
              details: {
                medicineName: t.medicineName,
                dosage: t.dosage,
                frequency: t.frequency,
                startDate: t.startDate,
                endDate: t.endDate,
                instruction: t.instruction,
                isEnded,
              },
            });
          });
        }

        // 4. Lệnh khoá tập (Training Locks)
        if (locksRes.status === "fulfilled" && Array.isArray(locksRes.value)) {
          locksRes.value.forEach((l) => {
            const date = new Date(l.issuedAt);
            const isActive = l.status === "ACTIVE";
            unified.push({
              id: `lock-${l.lockId}`,
              type: "LOCK",
              date,
              categoryLabel: "Khoá luyện tập",
              title: `Lệnh khoá: ${lockLevelLabel(l.lockLevel)}`,
              tone: isActive ? "red" : "amber",
              actorName: l.issuedByName || "Quản lý / HLV / Vet",
              actorRole: "Người ra lệnh",
              status: isActive ? "Đang hiệu lực (Khoá)" : "Đã mở khoá",
              searchableText: `${l.reason || ""} ${l.lockLevel} ${l.issuedByName || ""} ${l.releasedByName || ""}`,
              details: {
                lockLevel: l.lockLevel,
                reason: l.reason,
                issuedByName: l.issuedByName,
                issuedAt: l.issuedAt,
                releasedByName: l.releasedByName,
                releasedAt: l.releasedAt,
                isActive,
              },
            });
          });
        }

        // 5. Chuyển viện ngoài (External Referrals)
        if (referralsRes.status === "fulfilled" && Array.isArray(referralsRes.value)) {
          referralsRes.value.forEach((ref) => {
            const date = new Date(ref.referredAt);
            const isHospitalized = ref.status === "HOSPITALIZED";
            unified.push({
              id: `ref-${ref.referralId}`,
              type: "REFERRAL",
              date,
              categoryLabel: "Chuyển viện ngoài",
              title: `Chuyển viện: ${ref.hospitalName}`,
              tone: "pink",
              actorName: ref.referredByName ? `BS. ${ref.referredByName}` : "Bác sĩ chỉ định",
              actorRole: "Người chỉ định",
              status: isHospitalized ? "Đang nằm viện" : "Đã xuất viện về CLB",
              searchableText: `${ref.hospitalName} ${ref.referralReason} ${ref.dischargeNotes || ""}`,
              details: {
                hospitalName: ref.hospitalName,
                urgency: ref.urgency,
                referralReason: ref.referralReason,
                expectedReturn: ref.expectedReturn,
                isHospitalized,
                dischargedAt: ref.dischargedAt,
                dischargedByName: ref.dischargedByName,
                dischargeNotes: ref.dischargeNotes,
              },
            });
          });
        }

        // 6. Sự cố sân tập (Incident reports)
        if (incidentsRes.status === "fulfilled" && Array.isArray(incidentsRes.value)) {
          incidentsRes.value.forEach((inc) => {
            const date = new Date(inc.reportedAt);
            unified.push({
              id: `inc-${inc.reportId}`,
              type: "INCIDENT",
              date,
              categoryLabel: "Sự cố tập luyện",
              title: `Báo cáo: ${inc.symptoms}`,
              tone: inc.urgency === "HIGH" ? "urgent" : "amber",
              actorName: inc.reporterName || "Nài ngựa (Groom)",
              actorRole: "Người báo cáo",
              status: inc.status,
              searchableText: `${inc.symptoms} ${inc.description || ""} ${inc.reporterName || ""} ${inc.handoverByName || ""}`,
              details: {
                symptoms: inc.symptoms,
                description: inc.description,
                urgency: inc.urgency,
                sessionId: inc.sessionId,
                reporterName: inc.reporterName,
                receivedByName: inc.receivedByName,
                handoverByName: inc.handoverByName,
                handoverNotes: inc.handoverNotes,
                dismissedByName: inc.dismissedByName,
                dismissReason: inc.dismissReason,
              },
            });
          });
        }

        // Sắp xếp sự kiện mới nhất lên trước
        unified.sort((a, b) => b.date - a.date);
        setEvents(unified);
      })
      .catch((err) => {
        if (err.name === "AbortError") return;
        setError(err.message || "Không thể tải lịch sử y tế của ngựa.");
      })
      .finally(() => setLoading(false));

    return () => ctrl.abort();
  }, [horse.horseId]);

  // Bộ lọc thời gian
  const { minDate, maxDate } = useMemo(() => {
    const now = new Date();
    let min = null;
    let max = null;
    if (timePreset === "30d") {
      min = new Date(now.getTime() - 30 * 24 * 60 * 60 * 1000);
    } else if (timePreset === "90d") {
      min = new Date(now.getTime() - 90 * 24 * 60 * 60 * 1000);
    } else if (timePreset === "180d") {
      min = new Date(now.getTime() - 180 * 24 * 60 * 60 * 1000);
    } else if (timePreset === "365d") {
      min = new Date(now.getTime() - 365 * 24 * 60 * 60 * 1000);
    } else if (timePreset === "custom") {
      if (customFrom) min = new Date(customFrom + "T00:00:00");
      if (customTo) max = new Date(customTo + "T23:59:59");
    }
    return { minDate: min, maxDate: max };
  }, [timePreset, customFrom, customTo]);

  // Lọc danh sách sự kiện theo các tiêu chí
  const filteredEvents = useMemo(() => {
    return events.filter((e) => {
      if (minDate && e.date < minDate) return false;
      if (maxDate && e.date > maxDate) return false;
      if (typeFilter !== "ALL" && e.type !== typeFilter) return false;
      if (searchQuery.trim()) {
        const q = searchQuery.trim().toLowerCase();
        const text = (
          (e.title || "") + " " +
          (e.categoryLabel || "") + " " +
          (e.actorName || "") + " " +
          (e.status || "") + " " +
          (e.searchableText || "")
        ).toLowerCase();
        if (!text.includes(q)) return false;
      }
      return true;
    });
  }, [events, minDate, maxDate, typeFilter, searchQuery]);

  // Thống kê nhanh trong khoảng thời gian đã lọc
  const stats = useMemo(() => {
    const total = filteredEvents.length;
    let exams = 0;
    let injuries = 0;
    let treatments = 0;
    let referrals = 0;
    let locks = 0;

    filteredEvents.forEach((e) => {
      if (e.type === "EXAM") exams++;
      else if (e.type === "INJURY") injuries++;
      else if (e.type === "TREATMENT") treatments++;
      else if (e.type === "REFERRAL") referrals++;
      else if (e.type === "LOCK") locks++;
    });

    return { total, exams, injuries, treatments, referrals, locks };
  }, [filteredEvents]);

  if (loading) {
    return (
      <div className="hz-card" style={{ padding: "36px", textAlign: "center" }}>
        <p className="hh-muted">Đang tải toàn bộ hồ sơ bệnh án và lịch sử điều trị của {horse.horseName}…</p>
      </div>
    );
  }

  if (error) {
    return (
      <div className="hz-card" style={{ padding: "24px" }}>
        <p className="hz-field__error">{error}</p>
      </div>
    );
  }

  return (
    <div className="mh-container">
      {/* 1. THANH BỘ LỌC THỜI GIAN & TÌM KIẾM */}
      <div className="mh-filter-card">
        <div className="mh-filter-header">
          <div>
            <h3 style={{ margin: "0 0 4px", fontSize: "16px", fontWeight: 800 }}>
              Lịch sử Y tế & Hồ sơ Chữa trị · {horse.horseName}
            </h3>
            <p style={{ margin: 0, fontSize: "13px", color: "#69728f" }}>
              Nhật ký append-only ghi nhận mọi ca khám, chấn thương, đơn thuốc, lệnh khoá và chuyển viện ngoài.
            </p>
          </div>

          <div className="mh-search-box">
            <input
              type="text"
              className="vx-input"
              style={{ width: "240px", padding: "6px 12px", fontSize: "13px" }}
              placeholder="Tìm theo triệu chứng, thuốc, bác sĩ…"
              value={searchQuery}
              onChange={(e) => setSearchQuery(e.target.value)}
            />
          </div>
        </div>

        {/* Mốc thời gian nhanh */}
        <div className="mh-filter-controls">
          <div className="mh-time-bar">
            <span className="mh-filter-label">Thời gian:</span>
            <div className="mh-segments" role="group">
              {TIME_PRESETS.map((p) => (
                <button
                  key={p.value}
                  type="button"
                  className={`mh-segment-btn ${timePreset === p.value ? "active" : ""}`}
                  onClick={() => setTimePreset(p.value)}
                >
                  {p.label}
                </button>
              ))}
            </div>
          </div>

          {/* Loại sự kiện */}
          <div className="mh-type-bar">
            <span className="mh-filter-label">Phân loại:</span>
            <select
              className="vx-input"
              value={typeFilter}
              onChange={(e) => setTypeFilter(e.target.value)}
              style={{ padding: "6px 10px", fontSize: "13px" }}
            >
              {EVENT_TYPE_OPTIONS.map((opt) => (
                <option key={opt.value} value={opt.value}>
                  {opt.label}
                </option>
              ))}
            </select>
          </div>
        </div>

        {/* Khoảng ngày tùy chọn (chỉ hiện khi chọn Custom) */}
        {timePreset === "custom" && (
          <div className="mh-custom-row">
            <span className="mh-filter-label">Chọn khoảng ngày:</span>
            <div style={{ display: "flex", alignItems: "center", gap: "8px" }}>
              <label style={{ fontSize: "12.5px", color: "#69728f" }}>Từ ngày:</label>
              <input
                type="date"
                className="vx-input"
                style={{ padding: "4px 8px", fontSize: "12.5px" }}
                value={customFrom}
                onChange={(e) => setCustomFrom(e.target.value)}
              />
              <label style={{ fontSize: "12.5px", color: "#69728f" }}>Đến ngày:</label>
              <input
                type="date"
                className="vx-input"
                style={{ padding: "4px 8px", fontSize: "12.5px" }}
                value={customTo}
                onChange={(e) => setCustomTo(e.target.value)}
              />
              {(customFrom || customTo) && (
                <button
                  type="button"
                  className="vx-btn vx-btn--ghost"
                  style={{ padding: "4px 8px", fontSize: "12px" }}
                  onClick={() => {
                    setCustomFrom("");
                    setCustomTo("");
                  }}
                >
                  Xoá chọn ngày
                </button>
              )}
            </div>
          </div>
        )}
      </div>

      {/* 2. THẺ THỐNG KÊ TỔNG QUAN TRONG KHOẢNG THỜI GIAN */}
      <div className="mh-stats-grid">
        <div className="mh-stat-box">
          <span className="mh-stat-num">{stats.total}</span>
          <span className="mh-stat-title">Tổng sự kiện</span>
        </div>
        <div className="mh-stat-box">
          <span className="mh-stat-num" style={{ color: "#3074f6" }}>{stats.exams}</span>
          <span className="mh-stat-title">Lần khám & Chẩn đoán</span>
        </div>
        <div className="mh-stat-box">
          <span className="mh-stat-num" style={{ color: "#e43e8c" }}>{stats.injuries}</span>
          <span className="mh-stat-title">Chấn thương ghi nhận</span>
        </div>
        <div className="mh-stat-box">
          <span className="mh-stat-num" style={{ color: "#8a4af3" }}>{stats.treatments}</span>
          <span className="mh-stat-title">Đơn thuốc điều trị</span>
        </div>
        <div className="mh-stat-box">
          <span className="mh-stat-num" style={{ color: "#ff9800" }}>{stats.locks}</span>
          <span className="mh-stat-title">Lệnh khoá tập</span>
        </div>
        <div className="mh-stat-box">
          <span className="mh-stat-num" style={{ color: "#f55fa9" }}>{stats.referrals}</span>
          <span className="mh-stat-title">Chuyển viện ngoài</span>
        </div>
      </div>

      {/* 3. DÒNG THỜI GIAN CHI TIẾT (TIMELINE) */}
      {filteredEvents.length === 0 ? (
        <div className="hz-card" style={{ padding: "40px", textAlign: "center" }}>
          <p className="hh-muted">
            Không tìm thấy hồ sơ điều trị nào phù hợp với bộ lọc thời gian hoặc từ khóa tìm kiếm.
          </p>
        </div>
      ) : (
        <div className="mh-timeline">
          {filteredEvents.map((evt) => (
            <div key={evt.id} className={`mh-item tone-${evt.tone}`}>
              {/* Cột mốc thời gian */}
              <div className="mh-stamp">
                <span className="mh-stamp-date">{formatDate(evt.date)}</span>
                <span className="mh-stamp-time">{formatDateTime(evt.date).split(" ")[1] || ""}</span>
              </div>

              {/* Điểm nối timeline */}
              <div className="mh-line-dot" />

              {/* Nội dung chi tiết sự kiện */}
              <div className="mh-card">
                <div className="mh-card-top">
                  <div className="mh-title-wrap">
                    <span className={`mh-tag mh-tag--${evt.tone}`}>{evt.categoryLabel}</span>
                    <h4 className="mh-event-title">{evt.title}</h4>
                  </div>
                  <div className="mh-status-badge">
                    <span className={`mh-chip mh-chip--${evt.tone}`}>{evt.status}</span>
                  </div>
                </div>

                <div className="mh-actor-line">
                  <span>{evt.actorRole}: <strong>{evt.actorName}</strong></span>
                </div>

                {/* Nội dung cụ thể từng loại */}
                <div className="mh-details-block">
                  {evt.type === "EXAM" && (
                    <div className="mh-fact-grid">
                      <div>
                        <span>Chẩn đoán:</span>
                        <strong>{evt.details.diagnosis}</strong>
                      </div>
                      {evt.details.healthStatus && (
                        <div>
                          <span>Trạng thái sau khám:</span>
                          <strong>{statusLabel(evt.details.healthStatus)}</strong>
                        </div>
                      )}
                      {evt.details.expectedRestDays && (
                        <div>
                          <span>Nghỉ ngơi chỉ định:</span>
                          <strong>{evt.details.expectedRestDays} ngày</strong>
                        </div>
                      )}
                      {evt.details.weight && (
                        <div>
                          <span>Cân nặng lúc khám:</span>
                          <strong>{evt.details.weight} kg</strong>
                        </div>
                      )}
                      {isVet && evt.details.notes && (
                        <div style={{ gridColumn: "1 / -1" }}>
                          <span>Ghi chú chuyên môn:</span>
                          <p style={{ margin: "2px 0 0", color: "#3a415a" }}>{evt.details.notes}</p>
                        </div>
                      )}
                    </div>
                  )}

                  {evt.type === "INJURY" && (
                    <div className="mh-fact-grid">
                      <div>
                        <span>Vị trí cơ thể:</span>
                        <strong>{evt.details.bodyLocation}</strong>
                      </div>
                      <div>
                        <span>Mức độ nghiêm trọng:</span>
                        <strong>{evt.details.severity}</strong>
                      </div>
                      <div>
                        <span>Toạ độ giải phẫu:</span>
                        <strong>{evt.details.coordinate}</strong>
                      </div>
                      {evt.details.isRecovered && evt.details.recoveredAt && (
                        <div>
                          <span>Ngày hồi phục:</span>
                          <strong style={{ color: "#2e7d32" }}>{formatDateTime(evt.details.recoveredAt)}</strong>
                        </div>
                      )}
                    </div>
                  )}

                  {evt.type === "TREATMENT" && (
                    <div className="mh-fact-grid">
                      <div>
                        <span>Liều lượng:</span>
                        <strong>{evt.details.dosage || "Theo đơn"}</strong>
                      </div>
                      <div>
                        <span>Tần suất dùng:</span>
                        <strong>{evt.details.frequency || "Hàng ngày"}</strong>
                      </div>
                      <div>
                        <span>Liệu trình:</span>
                        <strong>
                          {formatDate(evt.details.startDate)} → {evt.details.endDate ? formatDate(evt.details.endDate) : "Liên tục"}
                        </strong>
                      </div>
                      {evt.details.instruction && (
                        <div style={{ gridColumn: "1 / -1" }}>
                          <span>Hướng dẫn cho nài ngựa/bác sĩ:</span>
                          <p style={{ margin: "2px 0 0", color: "#3a415a" }}>{evt.details.instruction}</p>
                        </div>
                      )}
                    </div>
                  )}

                  {evt.type === "LOCK" && (
                    <div className="mh-fact-grid">
                      <div>
                        <span>Mức độ khoá:</span>
                        <strong>{lockLevelLabel(evt.details.lockLevel)}</strong>
                      </div>
                      <div>
                        <span>Lý do ra lệnh:</span>
                        <strong>{evt.details.reason}</strong>
                      </div>
                      {!evt.details.isActive && evt.details.releasedByName && (
                        <div style={{ gridColumn: "1 / -1" }}>
                          <span>Gỡ khoá bởi:</span>
                          <strong>
                            {evt.details.releasedByName} (lúc {formatDateTime(evt.details.releasedAt)})
                          </strong>
                        </div>
                      )}
                    </div>
                  )}

                  {evt.type === "REFERRAL" && (
                    <div className="mh-fact-grid">
                      <div>
                        <span>Bệnh viện tiếp nhận:</span>
                        <strong style={{ color: "#b32569" }}>{evt.details.hospitalName}</strong>
                      </div>
                      <div>
                        <span>Mức khẩn cấp:</span>
                        <strong>{evt.details.urgency}</strong>
                      </div>
                      <div>
                        <span>Dự kiến về CLB:</span>
                        <strong>{evt.details.expectedReturn ? formatDate(evt.details.expectedReturn) : "Chưa ấn định"}</strong>
                      </div>
                      <div style={{ gridColumn: "1 / -1" }}>
                        <span>Lý do chuyển viện:</span>
                        <p style={{ margin: "2px 0 0", color: "#3a415a" }}>{evt.details.referralReason}</p>
                      </div>
                      {!evt.details.isHospitalized && evt.details.dischargeNotes && (
                        <div style={{ gridColumn: "1 / -1", background: "#f8f9fc", padding: "8px 12px", borderRadius: "8px" }}>
                          <span style={{ fontWeight: 700, color: "#1976d2" }}>Biên bản tiếp nhận xuất viện:</span>
                          <p style={{ margin: "2px 0 0", color: "#3a415a" }}>{evt.details.dischargeNotes}</p>
                          <small style={{ color: "#8e96b1", display: "block", marginTop: "4px" }}>
                            Đón về ngày {formatDate(evt.details.dischargedAt)} bởi Dr. {evt.details.dischargedByName}
                          </small>
                        </div>
                      )}
                    </div>
                  )}

                  {evt.type === "INCIDENT" && (
                    <div className="mh-fact-grid">
                      <div>
                        <span>Mức độ khẩn:</span>
                        <strong>{evt.details.urgency}</strong>
                      </div>
                      {evt.details.sessionId && (
                        <div>
                          <span>Buổi tập:</span>
                          <strong>Buổi tập #{evt.details.sessionId} (Dừng sớm)</strong>
                        </div>
                      )}
                      {evt.details.handoverByName && (
                        <div style={{ gridColumn: "1 / -1", background: "#fff5f9", padding: "8px 12px", borderRadius: "8px", border: "1px solid #f8cce1" }}>
                          <span style={{ fontWeight: 700, color: "#b32569" }}>Nhật ký bàn giao ca y tế:</span>
                          <p style={{ margin: "2px 0 0", color: "#3a415a" }}>
                            Bàn giao bởi Dr. {evt.details.handoverByName}: {evt.details.handoverNotes}
                          </p>
                        </div>
                      )}
                      {evt.details.dismissedByName && (
                        <div style={{ gridColumn: "1 / -1", background: "#f5f5f5", padding: "8px 12px", borderRadius: "8px" }}>
                          <span>Huỷ bởi HLV trưởng {evt.details.dismissedByName}:</span>
                          <p style={{ margin: "2px 0 0", color: "#3a415a" }}>{evt.details.dismissReason}</p>
                        </div>
                      )}
                    </div>
                  )}
                </div>
              </div>
            </div>
          ))}
        </div>
      )}
    </div>
  );
}
