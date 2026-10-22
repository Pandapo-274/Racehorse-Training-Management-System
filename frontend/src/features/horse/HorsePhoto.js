// src/features/horse/HorsePhoto.js
// Ảnh ngựa, hoặc chữ viết tắt tên khi chưa có ảnh / ảnh không tải được
// (file đã bị xoá trên máy chủ, đổi máy chạy backend...).

import { useEffect, useState } from "react";
import { toInitials } from "./horseApi";

export default function HorsePhoto({ horse, className = "" }) {
  const [broken, setBroken] = useState(false);

  // Ảnh mới thì thử tải lại, đừng giữ trạng thái lỗi của ảnh cũ.
  useEffect(() => setBroken(false), [horse?.photoUrl]);

  if (!horse?.photoUrl || broken) {
    return (
      <div className={`hz-photo hz-photo--empty ${className}`} aria-hidden="true">
        {toInitials(horse?.horseName)}
      </div>
    );
  }

  return (
    <img
      className={`hz-photo ${className}`}
      src={horse.photoUrl}
      alt={horse.horseName}
      onError={() => setBroken(true)}
    />
  );
}
