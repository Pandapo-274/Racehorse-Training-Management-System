/* =============================================================================
   V11 - Ảnh demo cho ngựa và tài khoản.

   VẤN ĐỀ
   Ảnh tải lên qua giao diện nằm trong backend/uploads/ (không đưa lên git) và
   đường dẫn của nó chỉ nằm trong database của máy đã tải. Máy khác pull code về
   nên không thấy ảnh nào.

   CÁCH XỬ LÝ
   Ảnh demo được đặt trong frontend/public/seed/, nên đi cùng git và được
   frontend phục vụ trực tiếp. File này gắn đường dẫn vào đúng ngựa (theo mã đăng
   ký) và đúng tài khoản (theo username).

   - Chỉ gắn khi ô ảnh còn trống: ai đã tự tải ảnh riêng thì giữ nguyên.
   - Dòng nào không khớp ngựa / tài khoản nào thì không làm gì cả.
   - Chạy lại được.
   ============================================================================= */

/* ---------- Ảnh ngựa ---------- */

UPDATE HORSE SET photo_url = '/seed/horses/TM-0298.webp' WHERE registration_code = 'TM-0298' AND photo_url IS NULL;
UPDATE HORSE SET photo_url = '/seed/horses/TM-0332.webp' WHERE registration_code = 'TM-0332' AND photo_url IS NULL;
UPDATE HORSE SET photo_url = '/seed/horses/TM-0344.webp' WHERE registration_code = 'TM-0344' AND photo_url IS NULL;
UPDATE HORSE SET photo_url = '/seed/horses/TM-0410.webp' WHERE registration_code = 'TM-0410' AND photo_url IS NULL;
UPDATE HORSE SET photo_url = '/seed/horses/TM-0455.jpg' WHERE registration_code = 'TM-0455' AND photo_url IS NULL;
UPDATE HORSE SET photo_url = '/seed/horses/TM-0481.webp' WHERE registration_code = 'TM-0481' AND photo_url IS NULL;
UPDATE HORSE SET photo_url = '/seed/horses/TM-0523.webp' WHERE registration_code = 'TM-0523' AND photo_url IS NULL;
UPDATE HORSE SET photo_url = '/seed/horses/TM-0612.jpg' WHERE registration_code = 'TM-0612' AND photo_url IS NULL;
UPDATE HORSE SET photo_url = '/seed/horses/TM-0618.webp' WHERE registration_code = 'TM-0618' AND photo_url IS NULL;
UPDATE HORSE SET photo_url = '/seed/horses/TM-0624.jpg' WHERE registration_code = 'TM-0624' AND photo_url IS NULL;
UPDATE HORSE SET photo_url = '/seed/horses/TM-0631.webp' WHERE registration_code = 'TM-0631' AND photo_url IS NULL;
UPDATE HORSE SET photo_url = '/seed/horses/TM-0637.webp' WHERE registration_code = 'TM-0637' AND photo_url IS NULL;
UPDATE HORSE SET photo_url = '/seed/horses/TM-0645.jpg' WHERE registration_code = 'TM-0645' AND photo_url IS NULL;
UPDATE HORSE SET photo_url = '/seed/horses/TM-0652.webp' WHERE registration_code = 'TM-0652' AND photo_url IS NULL;
UPDATE HORSE SET photo_url = '/seed/horses/TM-0715.jpg' WHERE registration_code = 'TM-0715' AND photo_url IS NULL;
UPDATE HORSE SET photo_url = '/seed/horses/TM-0736.webp' WHERE registration_code = 'TM-0736' AND photo_url IS NULL;
UPDATE HORSE SET photo_url = '/seed/horses/TM-0743.jpg' WHERE registration_code = 'TM-0743' AND photo_url IS NULL;
UPDATE HORSE SET photo_url = '/seed/horses/TM-0750.webp' WHERE registration_code = 'TM-0750' AND photo_url IS NULL;
GO

/* ---------- Ảnh đại diện tài khoản ---------- */

UPDATE APP_USER SET avatar_url = '/seed/avatars/hoa.tm.jpg' WHERE username = 'hoa.tm' AND avatar_url IS NULL;
UPDATE APP_USER SET avatar_url = '/seed/avatars/thang.nd.jpg' WHERE username = 'thang.nd' AND avatar_url IS NULL;
UPDATE APP_USER SET avatar_url = '/seed/avatars/lam.tb.jpg' WHERE username = 'lam.tb' AND avatar_url IS NULL;
UPDATE APP_USER SET avatar_url = '/seed/avatars/ha.pt.webp' WHERE username = 'ha.pt' AND avatar_url IS NULL;
UPDATE APP_USER SET avatar_url = '/seed/avatars/binh.lv.jpg' WHERE username = 'binh.lv' AND avatar_url IS NULL;
UPDATE APP_USER SET avatar_url = '/seed/avatars/anh.dq.jpg' WHERE username = 'anh.dq' AND avatar_url IS NULL;
UPDATE APP_USER SET avatar_url = '/seed/avatars/trang.lt.jpg' WHERE username = 'trang.lt' AND avatar_url IS NULL;
UPDATE APP_USER SET avatar_url = '/seed/avatars/khanh.nv.jpg' WHERE username = 'khanh.nv' AND avatar_url IS NULL;
UPDATE APP_USER SET avatar_url = '/seed/avatars/mai.ht.jpg' WHERE username = 'mai.ht' AND avatar_url IS NULL;
UPDATE APP_USER SET avatar_url = '/seed/avatars/son.dv.jpg' WHERE username = 'son.dv' AND avatar_url IS NULL;
UPDATE APP_USER SET avatar_url = '/seed/avatars/yen.pt.jpg' WHERE username = 'yen.pt' AND avatar_url IS NULL;
UPDATE APP_USER SET avatar_url = '/seed/avatars/tuan.ng.jpg' WHERE username = 'tuan.ng' AND avatar_url IS NULL;
UPDATE APP_USER SET avatar_url = '/seed/avatars/linh.vt.jpg' WHERE username = 'linh.vt' AND avatar_url IS NULL;
UPDATE APP_USER SET avatar_url = '/seed/avatars/nam.tq.jpg' WHERE username = 'nam.tq' AND avatar_url IS NULL;
UPDATE APP_USER SET avatar_url = '/seed/avatars/hieu.lm.jpg' WHERE username = 'hieu.lm' AND avatar_url IS NULL;
UPDATE APP_USER SET avatar_url = '/seed/avatars/huong.nt.jpg' WHERE username = 'huong.nt' AND avatar_url IS NULL;
UPDATE APP_USER SET avatar_url = '/seed/avatars/dung.pv.jpg' WHERE username = 'dung.pv' AND avatar_url IS NULL;
UPDATE APP_USER SET avatar_url = '/seed/avatars/thao.lk.jpg' WHERE username = 'thao.lk' AND avatar_url IS NULL;
UPDATE APP_USER SET avatar_url = '/seed/avatars/quan.vm.jpg' WHERE username = 'quan.vm' AND avatar_url IS NULL;
UPDATE APP_USER SET avatar_url = '/seed/avatars/nga.bt.jpg' WHERE username = 'nga.bt' AND avatar_url IS NULL;
UPDATE APP_USER SET avatar_url = '/seed/avatars/kien.hm.jpg' WHERE username = 'kien.hm' AND avatar_url IS NULL;
UPDATE APP_USER SET avatar_url = '/seed/avatars/oanh.tt.jpg' WHERE username = 'oanh.tt' AND avatar_url IS NULL;
GO

PRINT 'V11 hoan tat.';
GO
