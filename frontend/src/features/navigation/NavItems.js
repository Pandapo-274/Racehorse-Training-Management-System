// src/features/navigation/NavItems.js
// Các nút menu của một vai trò, vẽ từ ROLE_NAV. Mỗi sidebar vẫn giữ khung và
// CSS riêng của nó (tiền tố lớp khác nhau: owner-, manager-, hz-...), chỉ phần
// danh sách mục là dùng chung.
//
// Dùng bên trong <nav className="xxx-nav">:
//   <NavItems role={user.role} prefix="owner" icon={<HorseshoeIcon />} />
//
// prefix  - lớp của nút là `${prefix}-nav-item`, thêm " active" khi là trang hiện tại
// badges  - { [key]: số }: số > 0 thì hiện cạnh nhãn, vd { registrations: 3 }

import { useEffect } from "react";
import { useLocation, useNavigate } from "react-router-dom";
import { ROLE_NAV, isNavActive, navTarget } from "./roleNav";

export default function NavItems({ role, prefix, icon, badges = {} }) {
  const navigate = useNavigate();
  const { pathname } = useLocation();

  return (ROLE_NAV[role] || []).map((item) => {
    const active = isNavActive(item, pathname);
    const count = badges[item.key];

    return (
      <button
        key={item.key}
        type="button"
        className={`${prefix}-nav-item${active ? " active" : ""}`}
        aria-current={active ? "page" : undefined}
        onClick={item.path ? () => navigate(navTarget(item)) : undefined}
      >
        {icon}
        {item.label}
        {count > 0 ? ` (${count})` : ""}
      </button>
    );
  });
}

/**
 * Cuộn tới phần tử theo #hash của URL. Dùng ở trang có mục menu kiểu lối tắt
 * (xem `hash` trong roleNav.js): bấm từ trang khác sang thì trang đích chỉ vừa
 * mới dựng, nên phải đợi `ready` (dữ liệu đã có, phần tử đã nằm trên trang)
 * rồi mới cuộn. location.key đổi mỗi lần bấm nên bấm lại cùng một mục vẫn cuộn.
 */
export function useScrollToHash(ready = true) {
  const { hash, key } = useLocation();

  useEffect(() => {
    if (!ready || !hash) return;
    document.getElementById(hash.slice(1))?.scrollIntoView({ behavior: "smooth", block: "start" });
  }, [hash, key, ready]);
}
