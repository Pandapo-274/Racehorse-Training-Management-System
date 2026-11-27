
/*
 * src/features/vet/VetExaminationPage.js - trang /veterinarian/examination[/:horseId] (khám ngựa, UC14-UC16).
 * Một lần bấm Save có thể gồm ba bước, chạy tuần tự:
 *   1. POST /api/medical-records (recordType EXAM): chẩn đoán, trạng thái sau khám, cân nặng, ghi chú, số ngày nghỉ;
 *      mở từ hàng chờ (?reportId=) thì backend đóng báo cáo sự cố đó (EXAMINED).
 *   2. Nếu xác nhận chấn thương: POST /api/injuries - điểm bấm trên mô hình ngựa 2D (toạ độ %) + vị trí + mức độ.
 *   3. Nếu chọn "Save and lock": POST /api/training-locks (FULL hoặc HEAVY_WORK_ONLY).
 * saved (useRef) nhớ bước nào đã thành công: lỗi ở bước sau thì bấm lại chỉ chạy lại bước hỏng, không tạo trùng hồ sơ.
 * Ngoài ra: gỡ lệnh khoá đang có (releaseLock) và chuyển viện ngoài (ReferralModal, V40).
 */
import { useEffect, useRef, useState } from "react";
import * as THREE from "three";
import { useNavigate, useParams, useSearchParams, useLocation } from "react-router-dom";
import { getUser, handleAuthError } from "../auth/authService";
import { formatAge } from "../horse/horseApi";
import VetShell, { VetCard, VetState, VetError } from "./VetShell";
import VetHorsePicker from "./VetHorsePicker";
import {
  getHorse,
  listLocksForHorse,
  listInjuries,
  createMedicalRecord,
  getIncidentReport,
  toUrgency,
  createInjury,
  issueLock,
  releaseLock,
  SEVERITIES,
  lockLevelLabel,
  todayInputValue,
  examTimestamp,
  describeError,
} from "./vetApi";

/* Khoảng cân nặng hợp lệ (kg), khớp MedicalExamRequest.weight ở backend. */
const WEIGHT_MIN = 30;
const WEIGHT_MAX = 1500;

const COMMON_INJURIES = [
  {
    id: "SDFT",
    label: "Superficial Digital Flexor Tendonitis (SDFT)",
    text: "Superficial digital flexor tendon (SDFT) micro-tear / tendonitis",
    sev: "GRADE_3",
    lock: "FULL",
  },
  {
    id: "FETLOCK_SYNOVITIS",
    label: "Fetlock Joint Synovitis (Joint Inflammation)",
    text: "Fetlock joint synovitis / mild joint capsule inflammation",
    sev: "GRADE_2",
    lock: "LIGHT",
  },
  {
    id: "EGUS",
    label: "Equine Gastric Ulcer Syndrome (EGUS)",
    text: "Equine gastric ulcer syndrome (EGUS Grade 2)",
    sev: "GRADE_2",
    lock: "LIGHT",
  },
];

/* ---------- MÔ HÌNH NGỰA 3D CHUẨN ĐỒ HOẠ BASE MESH POLYGON (THREE.JS WEBGL) ---------- */

const clamp = (n) => Math.min(100, Math.max(0, n));
const round2 = (n) => Math.round(n * 100) / 100;

/**
 * Xây dựng mô hình 3D Base Mesh Horse (polygon/wireframe) tương tự ảnh tham chiếu 3704 triangles.
 */
function buildHorse3DMesh() {
  const horseGroup = new THREE.Group();
  horseGroup.name = "HorseGroup";

  // Vật liệu Base Mesh màu xám studio, flatShading tạo hiệu ứng khối đa giác (polygon mesh)
  const clayMaterial = new THREE.MeshStandardMaterial({
    color: 0xb5bec7,
    roughness: 0.5,
    metalness: 0.08,
    flatShading: true,
  });

  // Lưới wireframe trắng tinh tế phủ lên bề mặt đa giác
  const wireframeMaterial = new THREE.LineBasicMaterial({
    color: 0xffffff,
    transparent: true,
    opacity: 0.4,
  });

  const addSegment = (geom, pos, rot, scale, partName, side) => {
    const mesh = new THREE.Mesh(geom, clayMaterial);
    if (pos) mesh.position.set(pos[0], pos[1], pos[2]);
    if (rot) mesh.rotation.set(rot[0], rot[1], rot[2]);
    if (scale) mesh.scale.set(scale[0], scale[1], scale[2]);
    mesh.castShadow = true;
    mesh.receiveShadow = true;
    mesh.userData = { bodyPart: partName, side: side || "Left" };

    // Thêm wireframe edges
    const edges = new THREE.EdgesGeometry(geom, 24);
    const line = new THREE.LineSegments(edges, wireframeMaterial);
    mesh.add(line);

    horseGroup.add(mesh);
    return mesh;
  };

  // 1. Thân mình & Lồng ngực (Torso / Barrel)
  const barrelGeo = new THREE.CylinderGeometry(15, 17, 44, 14, 6);
  addSegment(barrelGeo, [2, 9, 0], [0, 0, Math.PI / 2], [1.08, 1, 0.95], "Trunk / Barrel", "Both");

  // Vai & Xương sống (Withers & Spine)
  const withersGeo = new THREE.BoxGeometry(17, 13, 14);
  addSegment(withersGeo, [15, 19, 0], [0, 0, -0.2], [1, 1, 0.8], "Withers / Spine", "Both");

  // Hông, Xương chậu & Mông dốc hài hòa (Croup, Pelvis & Harmonious Rump)
  // Khung chậu kéo dài từ thắt lưng về phía sau, dốc tự nhiên ~20 độ
  const pelvisGeo = new THREE.CylinderGeometry(12, 14.5, 22, 12, 4);
  addSegment(pelvisGeo, [-18, 13.5, 0], [0, 0, Math.PI / 2 + 0.28], [1, 0.95, 0.9], "Croup / Pelvis", "Both");

  // Vòm mông sau & Đỉnh mông tròn đều (Rump & Buttocks curve)
  const buttockGeo = new THREE.SphereGeometry(10.5, 12, 10);
  addSegment(buttockGeo, [-26, 11, 0], [0, 0, 0], [0.92, 1.08, 0.86], "Rump / Buttocks", "Both");

  // Ngực & Lồng sườn trước (Chest & Girth)
  const chestGeo = new THREE.SphereGeometry(16.5, 12, 10);
  addSegment(chestGeo, [22, 10, 0], [0, 0, 0], [1.05, 1.1, 0.9], "Chest & Girth", "Both");

  // 2. Cổ (Neck & Crest)
  const neckGeo = new THREE.CylinderGeometry(9, 13.5, 36, 12, 5);
  addSegment(neckGeo, [35, 30, 0], [0, 0, -0.65], [1.1, 1, 0.85], "Neck & Crest", "Both");

  // 3. Đầu & Mõm (Head & Muzzle)
  const headGeo = new THREE.ConeGeometry(9.5, 28, 10);
  addSegment(headGeo, [53, 46, 0], [0, 0, 1.15], [1, 1, 0.8], "Head / Muzzle", "Both");

  // Tai (Ears)
  const earGeo = new THREE.ConeGeometry(2.5, 8, 6);
  addSegment(earGeo, [45, 58, 4.5], [0.2, 0, -0.3], [1, 1, 1], "Left Ear", "Left");
  addSegment(earGeo, [45, 58, -4.5], [-0.2, 0, -0.3], [1, 1, 1], "Right Ear", "Right");

  // 4. Đuôi (Tail) gắn tại khớp đuôi (Dock)
  const tailGeo = new THREE.CylinderGeometry(2.8, 1.0, 32, 8);
  addSegment(tailGeo, [-31, -2, 0], [0, 0, 0.18], [1, 1, 0.8], "Tail / Dock", "Both");

  // 5. Hai chân trước (Forelimbs: Trái +Z, Phải -Z)
  [-1, 1].forEach((sign) => {
    const side = sign > 0 ? "Left" : "Right";
    const z = sign * 12.5;

    // Vai (Shoulder)
    const shGeo = new THREE.SphereGeometry(8.5, 8, 8);
    addSegment(shGeo, [19, 8, z], [0, 0, 0], [1, 1.3, 0.8], `${side} forelimb, shoulder`, side);

    // Cẳng chân trên (Forearm)
    const upperGeo = new THREE.CylinderGeometry(4.8, 3.8, 22, 8);
    addSegment(upperGeo, [19, -8, z], [0, 0, 0.05], [1, 1, 1], `${side} forelimb, upper (radius)`, side);

    // Khớp khuỷu / Khớp gối trước (Carpus / Knee)
    const kneeGeo = new THREE.SphereGeometry(4.2, 8, 8);
    addSegment(kneeGeo, [18, -20, z], [0, 0, 0], [1, 1, 0.9], `${side} forelimb, knee (carpus)`, side);

    // Xương ống & Gân gấp ngón nông (Cannon & SDFT Tendon)
    const cannonGeo = new THREE.CylinderGeometry(3.2, 2.8, 20, 8);
    addSegment(cannonGeo, [18, -31, z], [0, 0, 0], [0.9, 1, 0.9], `${side} forelimb, tendon (SDFT / cannon)`, side);

    // Khớp cổ chân (Fetlock joint)
    const fetlockGeo = new THREE.SphereGeometry(3.8, 8, 8);
    addSegment(fetlockGeo, [18, -42, z], [0, 0, 0], [1, 1, 1], `${side} forelimb, fetlock joint`, side);

    // Móng & Khớp bàn (Pastern & Hoof)
    const hoofGeo = new THREE.CylinderGeometry(3.5, 5, 8, 8);
    addSegment(hoofGeo, [18, -48, z], [0, 0, 0], [1, 1, 1], `${side} forelimb, hoof & pastern`, side);
  });

  // 6. Hai chân sau (Hindlimbs: Trái +Z, Phải -Z) - Kết nối mượt mà với khung chậu & đùi
  [-1, 1].forEach((sign) => {
    const side = sign > 0 ? "Left" : "Right";
    const z = sign * 12.5;

    // Cơ đùi mông 2 bên (Gluteal Quarter & Hip flank)
    const haunchGeo = new THREE.SphereGeometry(8.5, 8, 8);
    addSegment(haunchGeo, [-20, 11, sign * 8.0], [0, 0, 0.25], [1.2, 1.1, 0.75], `${side} haunch / gluteal`, side);

    // Đùi & Khớp đùi (Thigh & Stifle)
    const thighGeo = new THREE.CylinderGeometry(7.2, 5.0, 21, 8, 2);
    addSegment(thighGeo, [-17, 2, z], [0, 0, -0.32], [1.15, 1, 0.9], `${side} hindlimb, stifle / thigh`, side);

    // Khớp bánh chè / Đầu gối sau (Stifle joint)
    const stifleGeo = new THREE.SphereGeometry(4.4, 8, 8);
    addSegment(stifleGeo, [-13, -7, z], [0, 0, 0], [1, 1, 1], `${side} hindlimb, stifle joint`, side);

    // Cẳng chân sau (Gaskin / Second thigh)
    const gaskinGeo = new THREE.CylinderGeometry(4.6, 3.6, 17, 8);
    addSegment(gaskinGeo, [-17, -14, z], [0, 0, 0.36], [1, 1, 0.9], `${side} hindlimb, gaskin`, side);

    // Khớp khoeo (Hock / Tarsus)
    const hockGeo = new THREE.SphereGeometry(4.4, 8, 8);
    addSegment(hockGeo, [-21, -22, z], [0, 0, 0], [1.1, 1, 0.9], `${side} hindlimb, hock (tarsus)`, side);

    // Xương ống sau (Cannon & Hind Tendon)
    const cannonGeo = new THREE.CylinderGeometry(3.3, 2.7, 20, 8);
    addSegment(cannonGeo, [-21, -32, z], [0, 0, 0.05], [0.9, 1, 0.9], `${side} hindlimb, tendon / cannon`, side);

    // Khớp cổ chân sau (Hind Fetlock)
    const fetlockGeo = new THREE.SphereGeometry(3.6, 8, 8);
    addSegment(fetlockGeo, [-20, -42, z], [0, 0, 0], [1, 1, 1], `${side} hindlimb, fetlock joint`, side);

    // Móng sau (Hind Hoof)
    const hoofGeo = new THREE.CylinderGeometry(3.5, 4.8, 7, 8);
    addSegment(hoofGeo, [-19, -48, z], [0, 0, 0], [1, 1, 1], `${side} hindlimb, hoof & pastern`, side);
  });

  return horseGroup;
}

/**
 * Component Mô hình 3D Three.js hoàn chỉnh với tracking chấm đỏ theo toạ độ 3D.
 */
function HorseModel({ marker, marker3D, existingMarkers = [], onMark, onLandmarkSelect }) {
  const mountRef = useRef(null);
  const sceneRef = useRef(null);
  const rendererRef = useRef(null);
  const cameraRef = useRef(null);
  const horseGroupRef = useRef(null);
  const pinGroupRef = useRef(null);

  const [cameraLabel, setCameraLabel] = useState("3/4 Perspective");
  const isDraggingRef = useRef(false);
  const lastMousePosRef = useRef({ x: 0, y: 0 });
  const downPosRef = useRef({ x: 0, y: 0 });

  // Khởi tạo Scene Three.js
  useEffect(() => {
    const container = mountRef.current;
    if (!container) return;

    const width = container.clientWidth || 360;
    const height = container.clientHeight || 360;

    // Scene
    const scene = new THREE.Scene();
    sceneRef.current = scene;

    // Camera
    const camera = new THREE.PerspectiveCamera(42, width / height, 1, 1000);
    camera.position.set(0, 12, 185);
    cameraRef.current = camera;

    // Renderer
    const renderer = new THREE.WebGLRenderer({ antialias: true, alpha: true });
    renderer.setSize(width, height);
    renderer.setPixelRatio(Math.min(window.devicePixelRatio, 2));
    renderer.shadowMap.enabled = true;
    renderer.shadowMap.type = THREE.PCFSoftShadowMap;
    container.innerHTML = "";
    container.appendChild(renderer.domElement);
    rendererRef.current = renderer;

    // Ánh sáng Studio
    const ambientLight = new THREE.AmbientLight(0xf1f5f9, 0.85);
    scene.add(ambientLight);

    const keyLight = new THREE.DirectionalLight(0xffffff, 1.3);
    keyLight.position.set(80, 120, 90);
    keyLight.castShadow = true;
    scene.add(keyLight);

    const fillLight = new THREE.DirectionalLight(0xcfd8dc, 0.65);
    fillLight.position.set(-80, 50, -70);
    scene.add(fillLight);

    // Bóng sàn (Ground shadow disk)
    const shadowGeo = new THREE.PlaneGeometry(160, 110);
    const shadowCanvas = document.createElement("canvas");
    shadowCanvas.width = 128;
    shadowCanvas.height = 128;
    const sCtx = shadowCanvas.getContext("2d");
    const sGrad = sCtx.createRadialGradient(64, 64, 10, 64, 64, 64);
    sGrad.addColorStop(0, "rgba(100, 116, 139, 0.35)");
    sGrad.addColorStop(0.6, "rgba(148, 163, 184, 0.12)");
    sGrad.addColorStop(1, "rgba(255, 255, 255, 0)");
    sCtx.fillStyle = sGrad;
    sCtx.fillRect(0, 0, 128, 128);
    const shadowTexture = new THREE.CanvasTexture(shadowCanvas);
    const shadowMat = new THREE.MeshBasicMaterial({ map: shadowTexture, transparent: true, depthWrite: false });
    const shadowMesh = new THREE.Mesh(shadowGeo, shadowMat);
    shadowMesh.rotation.x = -Math.PI / 2;
    shadowMesh.position.set(0, -52.5, 0);
    scene.add(shadowMesh);

    // Thêm mô hình ngựa 3D
    const horseGroup = buildHorse3DMesh();
    horseGroup.rotation.y = 0.65; // Mặc định góc 3/4 đẹp
    horseGroup.rotation.x = 0.12;
    scene.add(horseGroup);
    horseGroupRef.current = horseGroup;

    // Nhóm chấm đỏ Pin (GẮN TRỰC TIẾP VÀO HORSEGROUP ĐỂ TRACKING 100% KHI XOAY)
    const pinGroup = new THREE.Group();
    pinGroup.name = "ActivePinGroup";
    pinGroup.visible = false;

    // Viên ngọc đỏ phát sáng 3D
    const pinSphere = new THREE.Mesh(
      new THREE.SphereGeometry(3.2, 16, 16),
      new THREE.MeshStandardMaterial({
        color: 0xff1e38,
        emissive: 0xff002b,
        emissiveIntensity: 0.9,
        roughness: 0.2,
      })
    );
    pinGroup.add(pinSphere);

    // Vòng hào quang pulsing
    const ringGeo = new THREE.RingGeometry(4.2, 5.8, 24);
    const ringMat = new THREE.MeshBasicMaterial({
      color: 0xff4d5e,
      side: THREE.DoubleSide,
      transparent: true,
      opacity: 0.85,
    });
    const pinRing = new THREE.Mesh(ringGeo, ringMat);
    pinRing.rotation.x = Math.PI / 2;
    pinGroup.add(pinRing);

    horseGroup.add(pinGroup);
    pinGroupRef.current = pinGroup;

    // Animation Loop
    let reqId;
    let clock = 0;
    const animate = () => {
      reqId = requestAnimationFrame(animate);
      clock += 0.05;
      if (pinRing) {
        const s = 1 + Math.sin(clock * 3) * 0.18;
        pinRing.scale.set(s, s, s);
      }
      renderer.render(scene, camera);
    };
    animate();

    // Resize Handler
    const handleResize = () => {
      if (!container) return;
      const w = container.clientWidth || 360;
      const h = container.clientHeight || 360;
      camera.aspect = w / h;
      camera.updateProjectionMatrix();
      renderer.setSize(w, h);
    };
    window.addEventListener("resize", handleResize);

    return () => {
      cancelAnimationFrame(reqId);
      window.removeEventListener("resize", handleResize);
      renderer.dispose();
      if (container.contains(renderer.domElement)) {
        container.removeChild(renderer.domElement);
      }
    };
  }, []);

  // Cập nhật vị trí chấm đỏ tracking khi marker3D hoặc marker thay đổi
  useEffect(() => {
    if (!pinGroupRef.current || !horseGroupRef.current) return;
    const pin = pinGroupRef.current;

    if (marker3D && typeof marker3D.x === "number") {
      pin.visible = true;
      pin.position.set(marker3D.x, marker3D.y, marker3D.z);
    } else if (marker && typeof marker.x === "number") {
      // Ước tính vị trí 3D từ toạ độ 2D phần trăm nếu chưa có toạ độ 3D trực tiếp
      const x3d = (marker.x - 50) * 1.5;
      const y3d = (50 - marker.y) * 1.3;
      const z3d = 14;
      pin.visible = true;
      pin.position.set(x3d, y3d, z3d);
    } else {
      pin.visible = false;
    }
  }, [marker, marker3D]);

  // Xử lý kéo xoay chuột 3D Orbit
  const handlePointerDown = (e) => {
    isDraggingRef.current = true;
    lastMousePosRef.current = { x: e.clientX, y: e.clientY };
    downPosRef.current = { x: e.clientX, y: e.clientY };
  };

  const handlePointerMove = (e) => {
    if (!isDraggingRef.current || !horseGroupRef.current) return;
    const dx = e.clientX - lastMousePosRef.current.x;
    const dy = e.clientY - lastMousePosRef.current.y;
    lastMousePosRef.current = { x: e.clientX, y: e.clientY };

    horseGroupRef.current.rotation.y += dx * 0.012;
    horseGroupRef.current.rotation.x = Math.max(
      -0.45,
      Math.min(0.45, horseGroupRef.current.rotation.x + dy * 0.012)
    );
    setCameraLabel("Free Orbit");
  };

  const handlePointerUp = (e) => {
    isDraggingRef.current = false;
    const container = mountRef.current;
    if (!container || !cameraRef.current || !horseGroupRef.current) return;

    // Kiểm tra nếu là một cú CLICK thật sự (không phải drag xoay)
    const moveDist = Math.hypot(
      e.clientX - downPosRef.current.x,
      e.clientY - downPosRef.current.y
    );
    if (moveDist > 6) {
      // Người dùng đang kéo xoay mô hình -> KHÔNG ĐƯỢC THAY ĐỔI VẾT THƯƠNG HOẶC TEXT
      return;
    }

    // Bắt điểm 3D Raycasting
    const rect = container.getBoundingClientRect();
    const mouse = new THREE.Vector2(
      ((e.clientX - rect.left) / rect.width) * 2 - 1,
      -((e.clientY - rect.top) / rect.height) * 2 + 1
    );

    const raycaster = new THREE.Raycaster();
    raycaster.setFromCamera(mouse, cameraRef.current);

    const intersects = raycaster.intersectObjects(horseGroupRef.current.children, true);
    // Lọc bỏ đối tượng pin
    const validHits = intersects.filter(
      (h) => h.object.name !== "ActivePinGroup" && h.object.parent?.name !== "ActivePinGroup"
    );

    if (validHits.length > 0) {
      const hit = validHits[0];
      // Chuyển toạ độ điểm va chạm từ World Space sang Local Space của HorseGroup
      const localPt = horseGroupRef.current.worldToLocal(hit.point.clone());

      // Lấy tên giải phẫu và vị trí bên trái/phải
      const bodyPart = hit.object.userData?.bodyPart || "Trunk / Barrel";
      const detectedSide = hit.object.userData?.side || (localPt.z > 0 ? "Left" : "Right");

      // Toạ độ phần trăm 2D lưu database
      const pctX = round2(clamp(((e.clientX - rect.left) / rect.width) * 100));
      const pctY = round2(clamp(((e.clientY - rect.top) / rect.height) * 100));

      // Cập nhật toạ độ điểm 3D và gọi callback LƯU VẾT THƯƠNG
      onMark(pctX, pctY, { x: localPt.x, y: localPt.y, z: localPt.z });
      if (onLandmarkSelect) {
        onLandmarkSelect(bodyPart, detectedSide);
      }
    }
  };

  // Nút xoay camera cố định chuẩn y khoa
  const setCameraPreset = (name, targetYaw, targetPitch) => {
    if (!horseGroupRef.current) return;
    horseGroupRef.current.rotation.y = targetYaw;
    horseGroupRef.current.rotation.x = targetPitch;
    setCameraLabel(name);
  };

  return (
    <div style={{ position: "relative" }}>
      {/* Thanh điều khiển góc nhìn 3D (3D Orbit Toolbar) */}
      <div className="vx-3d-toolbar">
        <div className="vx-3d-badge">
          <span className="vx-3d-badge-dot" />
          <span>3D Mesh · {cameraLabel}</span>
        </div>
        <div className="vx-3d-cam-group">
          <button
            type="button"
            className={`vx-3d-btn ${cameraLabel.includes("Perspective") ? "vx-3d-btn--active" : ""}`}
            onClick={() => setCameraPreset("3/4 Perspective", 0.65, 0.12)}
            title="3D 3/4 Perspective"
          >
            3D 3/4
          </button>
          <button
            type="button"
            className={`vx-3d-btn ${cameraLabel.includes("Left") ? "vx-3d-btn--active" : ""}`}
            onClick={() => setCameraPreset("Left Lateral", 0, 0)}
            title="Left Lateral"
          >
            Left (0°)
          </button>
          <button
            type="button"
            className={`vx-3d-btn ${cameraLabel.includes("Right") ? "vx-3d-btn--active" : ""}`}
            onClick={() => setCameraPreset("Right Lateral", Math.PI, 0)}
            title="Right Lateral"
          >
            Right (180°)
          </button>
          <button
            type="button"
            className={`vx-3d-btn ${cameraLabel.includes("Front") ? "vx-3d-btn--active" : ""}`}
            onClick={() => setCameraPreset("Front View", Math.PI / 2, 0)}
            title="Front View (Chest & Forelimbs)"
          >
            Front (90°)
          </button>
          <button
            type="button"
            className={`vx-3d-btn ${cameraLabel.includes("Hind") ? "vx-3d-btn--active" : ""}`}
            onClick={() => setCameraPreset("Hind View", -Math.PI / 2, 0)}
            title="Hind View (Hindquarters & Hindlimbs)"
          >
            Hind (270°)
          </button>
        </div>
      </div>

      {/* Container WebGL canvas mô hình 3D */}
      <div
        ref={mountRef}
        className="vx-model-3d"
        style={{ width: "100%", aspectRatio: "1 / 1" }}
        onPointerDown={handlePointerDown}
        onPointerMove={handlePointerMove}
        onPointerUp={handlePointerUp}
      />

      <div className="vx-3d-hint">
        <span>🖱️ Drag to rotate 360° · Click directly on the 3D model to mark an injury</span>
      </div>
    </div>
  );
}

/* ---------- trang ---------- */

// Không có :horseId -> danh sách chọn ngựa. key gồm horseId + reportId để đổi ngựa / báo cáo thì form được dựng lại
// từ đầu (state cũ bị xoá).
export default function VetExaminationPage() {
  const { horseId } = useParams();
  // Mở từ hàng chờ khám: khám này trả lời báo cáo sự cố đó (backend đóng báo cáo khi lưu).
  const [searchParams] = useSearchParams();
  const reportParam = Number(searchParams.get("reportId"));
  const reportId = Number.isInteger(reportParam) && reportParam > 0 ? reportParam : null;

  if (!horseId) {
    return <VetHorsePicker mode="examination" />;
  }

  return (
    <ExaminationForm
      key={`${horseId}-${reportId || ""}`}
      horseId={Number(horseId)}
      reportId={reportId}
    />
  );
}

function Field({ label, full, children }) {
  return (
    <label className={`vx-field${full ? " vx-field--full" : ""}`}>
      <span>{label}</span>
      {children}
    </label>
  );
}


// Ba lựa chọn khoá. "800 m" khớp app.lock.light-work-max-distance trong application.properties.
const LOCK_SELECTION_OPTIONS = [
  { value: "FULL", label: "Full lock", hint: "No training at all" },
  { value: "HEAVY_WORK_ONLY", label: "No heavy work", hint: "Light work only: sessions up to 800 m" },
  { value: "NONE", label: "No lock", hint: "Normal training permitted" },
];

/*
 * Form khám của một ngựa. Props: horseId, reportId (nếu khám cho một báo cáo sự cố).
 * Tải: hồ sơ ngựa, lệnh khoá, các điểm chấn thương cũ (và báo cáo sự cố nếu có reportId).
 */
function ExaminationForm({ horseId, reportId }) {
  const navigate = useNavigate();
  const location = useLocation();
  const user = getUser();
  const [report, setReport] = useState(null);
  const [notice, setNotice] = useState(location.state?.notice || "");

  useEffect(() => {
    if (!reportId) return undefined;
    const ctrl = new AbortController();
    getIncidentReport(reportId, ctrl.signal)
      .then(setReport)
      .catch(() => {}); // banner chỉ là phụ; backend vẫn kiểm reportId khi lưu
    return () => ctrl.abort();
  }, [reportId]);

  const [horse, setHorse] = useState(null);
  const [locks, setLocks] = useState([]);
  const [existingMarkers, setExistingMarkers] = useState([]);
  const [loadError, setLoadError] = useState("");
  const [reload, setReload] = useState(0);

  const examDate = todayInputValue();
  const [diagnosis, setDiagnosis] = useState("");
  const [confirmed, setConfirmed] = useState("yes");
  const [restDays, setRestDays] = useState("");
  const [otherStatus, setOtherStatus] = useState("");
  const [notes, setNotes] = useState("");

  const [commonInjury, setCommonInjury] = useState("");
  // Weight measured during exam (kg). Leave blank if not weighed.
  const [weight, setWeight] = useState("");

  const [marker, setMarker] = useState(null);
  const [marker3D, setMarker3D] = useState(null);
  const [bodyLocation, setBodyLocation] = useState("");
  const [severity, setSeverity] = useState("GRADE_2");

  const [lockLevel, setLockLevel] = useState("FULL");
  const [reason, setReason] = useState("");

  const [submitting, setSubmitting] = useState(null);
  const [error, setError] = useState(null);
  const [releasing, setReleasing] = useState(false);

  const saved = useRef({ record: null, injury: null });

  const injuryConfirmed = confirmed === "yes";
  const activeLock = locks.find((l) => l.status === "ACTIVE") || null;

  // Trạng thái sau khám: xác nhận chấn thương -> INJURED; không thì trạng thái bác sĩ chọn hoặc giữ trạng thái cũ.
  // Lựa chọn khoá phụ thuộc vào đó: QUARANTINE bắt buộc FULL; INJURED không được "No lock".
  const effectivePostStatus = injuryConfirmed
    ? "INJURED"
    : (otherStatus || horse?.status || "ELIGIBLE");

  const availableLockOptions = (() => {
    if (effectivePostStatus === "QUARANTINE") {
      return [
        { value: "FULL", label: "Full lock", hint: "No training at all (mandatory for quarantine)" },
      ];
    }
    if (effectivePostStatus === "INJURED") {
      return [
        { value: "FULL", label: "Full lock", hint: "No training at all" },
        { value: "HEAVY_WORK_ONLY", label: "No heavy work", hint: "Light work only: sessions up to 800 m" },
      ];
    }
    return LOCK_SELECTION_OPTIONS;
  })();

  // Tự chỉnh mức khoá đang chọn khi trạng thái sau khám làm nó không còn hợp lệ.
  useEffect(() => {
    if (effectivePostStatus === "QUARANTINE" && lockLevel !== "FULL") {
      setLockLevel("FULL");
    } else if (effectivePostStatus === "INJURED" && lockLevel === "NONE") {
      setLockLevel("FULL");
    }
  }, [effectivePostStatus, lockLevel]);

  useEffect(() => {
    const ctrl = new AbortController();

    setLoadError("");

    Promise.all([
      getHorse(horseId, ctrl.signal),
      listLocksForHorse(horseId, ctrl.signal),
      listInjuries(horseId, ctrl.signal),
    ])
      .then(([h, l, injuries]) => {
        setHorse(h);
        setLocks(l);

        setExistingMarkers(
          (Array.isArray(injuries)
            ? injuries
            : injuries?.content || []
          ).filter(
            (item) =>
              item.recoveredAt == null &&
              item.status !== "RECOVERED" &&
              Number.isFinite(Number(item.coordinateX)) &&
              Number.isFinite(Number(item.coordinateY))
          )
        );
      })
      .catch((err) => {
        if (err.name === "AbortError") return;
        if (handleAuthError(err, navigate)) return;

        setLoadError(err.message);
      });

    return () => ctrl.abort();
  }, [horseId, navigate, reload]);

  // Bấm trên mô hình 3D: lưu toạ độ 2D (lưu db) và 3D point (tracking theo khối xoay).
  // Tuyệt đối không thay đổi bodyLocation khi đang kéo xoay mô hình.
  const handleTap = (x, y, pt3D) => {
    setMarker({ x, y });
    if (pt3D) {
      setMarker3D(pt3D);
    }
  };

  // Kiểm form trước khi gửi, trả mảng câu lỗi (rỗng = hợp lệ): chẩn đoán, ngày khám, cân nặng, điểm chấn thương...
  const validate = (withLock) => {
    const problems = [];

    if (!examDate) {
      problems.push("Choose the exam date.");
    } else if (examDate < todayInputValue()) {
      problems.push("The exam date cannot be in the past.");
    }

    if (!diagnosis.trim()) {
      problems.push("Enter a diagnosis.");
    }

    if (diagnosis.trim().length > 255) {
      problems.push("The diagnosis must be at most 255 characters.");
    }

    // Cùng khoảng với @DecimalMin/@DecimalMax của MedicalExamRequest.weight.
    if (weight !== "") {
      const kg = Number(weight);
      if (!Number.isFinite(kg) || kg < WEIGHT_MIN || kg > WEIGHT_MAX) {
        problems.push(`Weight must be a number between ${WEIGHT_MIN} and ${WEIGHT_MAX} kg.`);
      }
    }

    if (injuryConfirmed) {
      if (!marker) {
        problems.push("Tap the model to mark the injury site.");
      } else if (!bodyLocation.trim()) {
        problems.push("Describe the marked location.");
      }

      if (restDays !== "" && !/^\d+$/.test(restDays)) {
        problems.push("Expected rest must be a whole number of days.");
      }
    }

    if (withLock) {
      const why = reason.trim() || diagnosis.trim();

      if (!why) {
        problems.push("A reason is required for the lock.");
      }

      if (why.length > 255) {
        problems.push("The lock reason must be at most 255 characters.");
      }
    }

    if (effectivePostStatus === "QUARANTINE" && lockLevel !== "FULL") {
      problems.push("A horse in quarantine must have a Full training lock.");
    }
    if (effectivePostStatus === "INJURED" && lockLevel === "NONE") {
      problems.push("An injured horse cannot have No lock. Choose Full lock or No heavy work.");
    }

    return problems;
  };

  const submit = async (withLock) => {
    setError(null);

    const problems = validate(withLock);

    if (problems.length) {
      setError({
        message: problems[0],
        details: problems.slice(1),
      });
      return;
    }

    setSubmitting(withLock ? "lock" : "save");

    const done = saved.current;

    try {
      if (!done.record) {
        done.record = await createMedicalRecord({
          horseId,
          reportId,
          recordType: "EXAM",
          examDate: examTimestamp(examDate),
          diagnosis: diagnosis.trim(),
          healthStatus: injuryConfirmed
            ? "INJURED"
            : otherStatus || null,
          injuryConfirmed,
          expectedRestDays:
            injuryConfirmed && restDays !== ""
              ? Number(restDays)
              : null,
          notes: notes.trim() || null,
          weight: weight === "" ? null : Number(weight),
        });
      }

      if (injuryConfirmed && !done.injury) {
        done.injury = await createInjury({
          recordId: done.record.recordId,
          bodyLocation: bodyLocation.trim(),
          coordinateX: marker.x,
          coordinateY: marker.y,
          severity,
        });
      }

      let successNotice = `Examination saved for ${horse.horseName}.`;
      if (withLock) {
        await issueLock({
          horseId,
          injuryId: done.injury?.injuryId ?? null,
          reason: (reason.trim() || diagnosis.trim()).slice(0, 255),
          lockLevel,
        });
        const levelText = lockLevel === "FULL" ? "Full lock" : "No heavy work (Light work only)";
        successNotice = `Examination saved for ${horse.horseName}. Training lock is now in force (${levelText}).`;
      }

      setNotice(successNotice);
      setError(null);
      setDiagnosis("");
      setNotes("");
      setRestDays("");
      setWeight("");
      setMarker(null);
      setBodyLocation("");
      setReason("");
      setOtherStatus("");
      setConfirmed("yes");
      saved.current = { record: null, injury: null };
      setReload((r) => r + 1);
      window.scrollTo({ top: 0, behavior: "smooth" });
    // Lỗi giữa chừng: nói rõ bước nào đã lưu (hồ sơ #id, điểm chấn thương #id) để bác sĩ biết bấm lại là an toàn.
    } catch (err) {
      if (handleAuthError(err, navigate)) return;

      const info = describeError(err);

      if (done.record) {
        info.note =
          `The examination record (#${done.record.recordId}) is already saved` +
          (done.injury
            ? ` with its injury marker (#${done.injury.injuryId})`
            : "") +
          ". Trying again only repeats the step that failed.";
      }

      setError(info);
    } finally {
      setSubmitting(null);
    }
  };


  // Gỡ lệnh khoá đang có (hỏi xác nhận). Lệnh dựa trên chấn thương chưa lành sẽ bị backend từ chối: phải dùng
  // trang Recovery để xác nhận hồi phục.
  const handleRelease = async () => {
    if (!activeLock) return;

    if (
      !window.confirm(
        `Release the current lock on ${horse.horseName}?`
      )
    ) {
      return;
    }

    setReleasing(true);
    setError(null);

    try {
      await releaseLock(activeLock.lockId);
      setReload((n) => n + 1);
    } catch (err) {
      if (handleAuthError(err, navigate)) return;
      setError(describeError(err));
    } finally {
      setReleasing(false);
    }
  };

  /* ---------- trạng thái tải ---------- */

  if (loadError || !horse) {
    return (
      <VetShell
        active="examination"
        title="Examination"
        subtitle="Loading horse…"
      >
        {loadError ? (
          <VetState
            kind="error"
            title={loadError}
            onRetry={() => setReload((n) => n + 1)}
          >
            Check that the backend is running on port 8080 and that
            you are still signed in.
          </VetState>
        ) : (
          <VetState title="Loading horse…" />
        )}
      </VetShell>
    );
  }

  const age = formatAge(horse.dateOfBirth);
  const busy = submitting !== null;

  return (
    <VetShell
      active="examination"
      title={`Examination · ${horse.horseName}`}
      subtitle={[
        horse.registrationCode,
        horse.breed,
        age || null,
      ]
        .filter(Boolean)
        .join(" · ")}
    >
      <div style={{ display: "flex", justifyContent: "flex-end", marginBottom: "14px" }}>
        <button
          type="button"
          className="vx-btn vx-btn--ghost"
          style={{ fontSize: "13px", padding: "6px 14px", display: "inline-flex", alignItems: "center", gap: "6px", color: "#b32569", borderColor: "#f3c2db" }}
          onClick={() => navigate(`/veterinarian/prescriptions/history/${horseId}`)}
        >
          📋 View Lifetime Prescriptions & History →
        </button>
      </div>
      <VetError error={error} />

      {notice && (
        <div className="vx-banner vx-banner--ok" role="status" style={{ display: "flex", justifyContent: "space-between", alignItems: "center", marginBottom: "16px" }}>
          <span>{notice}</span>
          <button
            type="button"
            className="vx-link"
            style={{ border: "none", background: "none", cursor: "pointer", fontWeight: 700, padding: "0 4px" }}
            onClick={() => setNotice("")}
          >
            ✕
          </button>
        </div>
      )}

      {report && (
        <div className="vx-banner vx-banner--warn" role="status">
          <strong>
            Incident report #{report.reportId} · {toUrgency(report.urgency).label}
          </strong>
          <span>
            {report.symptoms}
            {report.description ? ` — ${report.description}` : ""} (reported by {report.reporterName}).
            Saving this examination closes the report and lets the horse train again.
          </span>
        </div>
      )}

      <div className="vx-grid vx-grid--exam">
        <VetCard
          title="3D musculoskeletal model"
          hint={
            injuryConfirmed
              ? "Tap to mark the injury site"
              : "Marking is only needed for a confirmed injury"
          }
          tone="pink"
          className={injuryConfirmed ? "" : "vx-card--muted"}
        >
          <HorseModel
            marker={injuryConfirmed ? marker : null}
            marker3D={injuryConfirmed ? marker3D : null}
            existingMarkers={existingMarkers}
            onMark={injuryConfirmed ? handleTap : () => {}}
            onLandmarkSelect={(name) => {
              if (injuryConfirmed) {
                setBodyLocation(name);
              }
            }}
          />

          <div className="vx-marked">
            <span>Marked location</span>

            {injuryConfirmed && marker ? (
              <input
                className="vx-input vx-input--location"
                value={bodyLocation}
                maxLength={100}
                onChange={(e) => setBodyLocation(e.target.value)}
                aria-label="Marked location"
              />
            ) : (
              <strong className="vx-marked-empty">
                {injuryConfirmed
                  ? "Nothing marked yet"
                  : "No injury to mark"}
              </strong>
            )}

            <p>
              The mark is saved with the record so progress can be
              compared at follow-ups.
            </p>
          </div>
        </VetCard>

        <div className="vx-stack">
          <VetCard title="Examination record" tone="green">
            <div className="vx-form-grid">
              <Field label="Exam date">
                <input
                  type="date"
                  className="vx-input"
                  value={todayInputValue()}
                  readOnly
                  disabled
                  style={{ background: "#f8f9fa", cursor: "not-allowed", color: "#495057" }}
                />
              </Field>

              <Field label="Veterinarian">
                <input
                  className="vx-input"
                  value={user?.fullName || ""}
                  readOnly
                />
              </Field>

              <Field label="Common injury preset" full>
                <select
                  className="vx-input"
                  value={commonInjury}
                  onChange={(e) => {
                    const id = e.target.value;
                    setCommonInjury(id);
                    const preset = COMMON_INJURIES.find((p) => p.id === id);
                    if (preset) {
                      setDiagnosis(preset.text);
                      setConfirmed("yes");
                      setSeverity(preset.sev);
                      setLockLevel(preset.lock);
                    }
                  }}
                >
                  <option value="">-- Select a common injury (optional) --</option>
                  {COMMON_INJURIES.map((p) => (
                    <option key={p.id} value={p.id}>
                      {p.label}
                    </option>
                  ))}
                </select>
              </Field>

              <Field label="Diagnosis" full>
                <input
                  className="vx-input"
                  value={diagnosis}
                  maxLength={255}
                  onChange={(e) => {
                    setDiagnosis(e.target.value);
                    if (commonInjury) setCommonInjury("");
                  }}
                  placeholder="e.g. Grade 2 tendon sheath inflammation, left hind hock"
                />
              </Field>

              <Field label="Injury confirmed">
                <select
                  className="vx-input"
                  value={confirmed}
                  onChange={(e) => setConfirmed(e.target.value)}
                >
                  <option value="yes">Yes</option>
                  <option value="no">No</option>
                </select>
              </Field>

              <Field label="Injury grade">
                <select
                  className="vx-input"
                  value={severity}
                  onChange={(e) => setSeverity(e.target.value)}
                  disabled={!injuryConfirmed}
                  aria-label="Injury grade"
                >
                  {SEVERITIES.map((s) => (
                    <option key={s.value} value={s.value}>
                      {s.label}
                    </option>
                  ))}
                </select>
              </Field>

              {injuryConfirmed ? (
                <Field label="Expected rest (days)">
                  <input
                    className="vx-input"
                    inputMode="numeric"
                    value={restDays}
                    onChange={(e) => setRestDays(e.target.value.trim())}
                    placeholder="e.g. 21"
                  />
                </Field>
              ) : (
                <Field label="Horse status after exam">
                  <select
                    className="vx-input"
                    value={otherStatus}
                    onChange={(e) => setOtherStatus(e.target.value)}
                  >
                    <option value="">No change</option>
                    <option value="MONITOR">Monitor</option>
                    <option value="QUARANTINE">Quarantine</option>
                  </select>
                </Field>
              )}

              <Field label="Weight (kg)">
                <input
                  className="vx-input"
                  inputMode="decimal"
                  value={weight}
                  onChange={(e) => setWeight(e.target.value.trim())}
                  placeholder={
                    horse?.weight
                      ? `Last recorded: ${Number(horse.weight).toFixed(1)} kg`
                      : "Not weighed yet, e.g. 480"
                  }
                />
              </Field>

              <Field label="Treatment plan / prescription" full>
                <textarea
                  className="vx-input vx-textarea"
                  rows={3}
                  value={notes}
                  onChange={(e) => setNotes(e.target.value)}
                  placeholder="Treatment, medication, rehab instructions…"
                />
              </Field>
            </div>
          </VetCard>

          <VetCard title="Equine Normal Vital Signs Reference" tone="teal" hint="AAEP Clinical Benchmark Standards">
            <div style={{ display: "grid", gridTemplateColumns: "repeat(auto-fit, minmax(180px, 1fr))", gap: "10px", marginTop: "4px" }}>
              <div style={{ background: "#f0fdf4", border: "1px solid #bbf7d0", borderRadius: "8px", padding: "10px" }}>
                <div style={{ fontSize: "11px", fontWeight: 700, color: "#166534", textTransform: "uppercase" }}>🌡️ Temperature (Temp)</div>
                <div style={{ fontSize: "16px", fontWeight: 800, color: "#15803d", marginTop: "2px" }}>37.2°C – 38.3°C</div>
                <div style={{ fontSize: "11.5px", color: "#166534", marginTop: "2px" }}>Fever: ≥ 38.5°C</div>
              </div>

              <div style={{ background: "#eff6ff", border: "1px solid #bfdbfe", borderRadius: "8px", padding: "10px" }}>
                <div style={{ fontSize: "11px", fontWeight: 700, color: "#1e40af", textTransform: "uppercase" }}>💓 Resting Heart Rate (HR)</div>
                <div style={{ fontSize: "16px", fontWeight: 800, color: "#1d4ed8", marginTop: "2px" }}>28 – 44 bpm</div>
                <div style={{ fontSize: "11.5px", color: "#1e40af", marginTop: "2px" }}>Foals &lt;2y: 60–80 bpm · Pain/Stress: &gt;60</div>
              </div>

              <div style={{ background: "#fdf4ff", border: "1px solid #f5d0fe", borderRadius: "8px", padding: "10px" }}>
                <div style={{ fontSize: "11px", fontWeight: 700, color: "#86198f", textTransform: "uppercase" }}>🫁 Resting Respiratory Rate (RR)</div>
                <div style={{ fontSize: "16px", fontWeight: 800, color: "#a21caf", marginTop: "2px" }}>8 – 16 bpm</div>
                <div style={{ fontSize: "11.5px", color: "#86198f", marginTop: "2px" }}>Tachypnea / Shock: &gt;25 breaths/min</div>
              </div>

              <div style={{ background: "#fffbeb", border: "1px solid #fde68a", borderRadius: "8px", padding: "10px" }}>
                <div style={{ fontSize: "11px", fontWeight: 700, color: "#92400e", textTransform: "uppercase" }}>⚖️ Standard Weight</div>
                <div style={{ fontSize: "16px", fontWeight: 800, color: "#b45309", marginTop: "2px" }}>450 – 550 kg</div>
                <div style={{ fontSize: "11.5px", color: "#92400e", marginTop: "2px" }}>Standard racing Thoroughbred</div>
              </div>
            </div>

            <div style={{ marginTop: "10px", fontSize: "12px", color: "#475569", background: "#f8fafc", padding: "8px 12px", borderRadius: "6px", border: "1px solid #e2e8f0" }}>
              💡 <strong>Clinical Guideline:</strong> When Temperature &gt; 38.5°C or Heart Rate &gt; 60 bpm, veterinarians are recommended to issue a <strong>FULL LOCK</strong> and transition the horse to <strong>MONITOR</strong> or <strong>QUARANTINE</strong> status.
            </div>
          </VetCard>

          <VetCard title="Training lock" tone="red">
            <p className="vx-lead">
              The lock stops the head trainer from scheduling heavy work
              for this horse until a veterinarian releases it.
            </p>

            {activeLock ? (
              <div className="vx-banner vx-banner--warn">
                <strong>
                  Already locked by {activeLock.issuedByName} (
                  {lockLevelLabel(activeLock.lockLevel)})
                </strong>

                <p>{activeLock.reason}</p>

                <button
                  type="button"
                  className="vx-link"
                  disabled={releasing}
                  onClick={handleRelease}
                >
                  {releasing ? "Releasing…" : "Release this lock"}
                </button>
              </div>
            ) : (
              <div className="vx-form-grid vx-form-grid--lock">
                <Field label="Lock level">
                  <select
                    className="vx-input"
                    value={lockLevel}
                    onChange={(e) => setLockLevel(e.target.value)}
                  >
                    {availableLockOptions.map((l) => (
                      <option key={l.value} value={l.value}>
                        {l.label} — {l.hint}
                      </option>
                    ))}
                  </select>
                </Field>

                <Field label="Reason">
                  <input
                    className="vx-input"
                    value={reason}
                    maxLength={255}
                    onChange={(e) => setReason(e.target.value)}
                    placeholder="Defaults to the diagnosis"
                  />
                </Field>
              </div>
            )}

            <div className="vx-actions">
              <button
                type="button"
                className="vx-btn vx-btn--danger"
                disabled={busy}
                onClick={() => submit(lockLevel !== "NONE")}
              >
                {submitting ? "Saving…" : "Confirm"}
              </button>
            </div>
          </VetCard>
        </div>
      </div>
    </VetShell>
  );
}
