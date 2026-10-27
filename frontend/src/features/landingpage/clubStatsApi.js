// src/features/landingpage/clubStatsApi.js
// Ba con số giới thiệu câu lạc bộ (ngựa đang tập, giáo án đang chạy, số giải
// đua trong năm), dùng ở trang chủ và khung thương hiệu của trang đăng nhập /
// đăng ký.
//
// GET /api/public/club-stats là API công khai: hai trang này mở trước khi đăng
// nhập, nên gọi bằng fetch thường, không cần token.
import { useEffect, useState } from "react";

export function getClubStats(signal) {
  return fetch("/api/public/club-stats", { signal }).then((res) => {
    if (!res.ok) throw new Error(`Request failed (HTTP ${res.status})`);
    return res.json();
  });
}

/**
 * { horsesInTraining, activePlans, racesThisSeason, season }, hoặc null khi
 * đang tải hay không gọi được backend. Lỗi thì im lặng: đây chỉ là số giới
 * thiệu, không đáng chặn người dùng đăng nhập.
 */
export function useClubStats() {
  const [stats, setStats] = useState(null);

  useEffect(() => {
    const ctrl = new AbortController();
    getClubStats(ctrl.signal)
      .then(setStats)
      .catch(() => {});
    return () => ctrl.abort();
  }, []);

  return stats;
}

/** Số để hiển thị; chưa có thì gạch ngang thay vì một con số bịa. */
export function formatStat(n) {
  return n == null ? "—" : String(n);
}
