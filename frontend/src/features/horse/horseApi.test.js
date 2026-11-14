// src/features/horse/horseApi.test.js
// Kiểm các luật thuần (không gọi API) trong horseApi.js và notificationApi.js.
//
// Những luật này được chép từ backend (HorsePolicy, WithdrawalService), nên
// lệch nhau là lỗi thật: giao diện cho bấm một việc mà backend sẽ chặn.
//
// Không test App.js: CRA dùng Jest 27, không nạp được react-router-dom v7.
import {
  AGE_RULES, ageProblem, canRequestWithdrawal, formatWeight,
  isWithdrawn, toActivity, toAge, toStage,
} from "./horseApi";
import { notificationTarget } from "../notification/notificationApi";

/** Ngày sinh (yyyy-mm-dd) để con ngựa tròn `years` tuổi hôm nay. Lùi thêm một ngày cho chắc. */
function bornYearsAgo(years) {
  const d = new Date();
  d.setFullYear(d.getFullYear() - years);
  d.setDate(d.getDate() - 1);
  return d.toISOString().slice(0, 10);
}

describe("toAge", () => {
  test("counts whole years", () => {
    expect(toAge(bornYearsAgo(5))).toBe(5);
  });

  test("returns null without a usable date", () => {
    expect(toAge(null)).toBeNull();
    expect(toAge("not a date")).toBeNull();
  });
});

describe("ageProblem (same rule as HorsePolicy.ageProblem)", () => {
  test("a pedigree record has no age limit", () => {
    expect(ageProblem("PEDIGREE", bornYearsAgo(40))).toBeNull();
  });

  test("a club horse needs a date of birth", () => {
    expect(ageProblem("ACADEMY", null)).toMatch(/date of birth is required/i);
  });

  test("a club horse above the limit is refused", () => {
    expect(ageProblem("ACADEMY", bornYearsAgo(AGE_RULES.clubMax + 1)))
      .toContain(String(AGE_RULES.clubMax));
  });

  test("a club horse of racing age is fine", () => {
    expect(ageProblem("ACADEMY", bornYearsAgo(5))).toBeNull();
  });
});

describe("toStage", () => {
  test.each([
    [{ recordType: "PEDIGREE", dateOfBirth: bornYearsAgo(20) }, "PEDIGREE"],
    [{ recordType: "ACADEMY", dateOfBirth: bornYearsAgo(1) }, "FOAL"],
    [{ recordType: "ACADEMY", dateOfBirth: bornYearsAgo(5) }, "RACING"],
    [{ recordType: "ACADEMY", dateOfBirth: bornYearsAgo(AGE_RULES.racingMax + 2) }, "RETIRED"],
    [{ recordType: "ACADEMY", dateOfBirth: null }, "UNKNOWN"],
  ])("%o -> %s", (horse, key) => {
    expect(toStage(horse).key).toBe(key);
  });
});

describe("withdrawal rules (same as WithdrawalService.submit)", () => {
  const owner = { role: "HORSE_OWNER", userId: 7 };
  const horse = { horseId: 1, ownerId: 7, recordType: "ACADEMY", activityStatus: "ACTIVE" };

  test("an owner can ask to withdraw their own club horse", () => {
    expect(canRequestWithdrawal(owner, horse)).toBe(true);
  });

  test("not when the horse has already left", () => {
    const gone = { ...horse, activityStatus: "WITHDRAWN" };
    expect(isWithdrawn(gone)).toBe(true);
    expect(canRequestWithdrawal(owner, gone)).toBe(false);
  });

  test("not for a pedigree record", () => {
    expect(canRequestWithdrawal(owner, { ...horse, recordType: "PEDIGREE" })).toBe(false);
  });

  test("not for another owner's horse, nor for staff", () => {
    expect(canRequestWithdrawal({ ...owner, userId: 8 }, horse)).toBe(false);
    expect(canRequestWithdrawal({ role: "CLUB_MANAGER", userId: 7 }, horse)).toBe(false);
  });

  test("a horse without activityStatus counts as at the club", () => {
    expect(isWithdrawn({ horseId: 2 })).toBe(false);
    expect(toActivity(undefined).label).toBe("At the club");
    expect(toActivity("WITHDRAWN").label).toBe("Withdrawn");
  });
});

describe("formatWeight", () => {
  test("shows kilograms with one decimal", () => {
    expect(formatWeight(480)).toBe("480.0 kg");
    expect(formatWeight("512.25")).toBe("512.3 kg");
  });

  test("shows a dash when the horse has not been weighed", () => {
    expect(formatWeight(null)).toBe("—");
    expect(formatWeight(0)).toBe("—");
  });
});

describe("notificationTarget", () => {
  test("a withdrawal request opens the manager's queue", () => {
    const n = { targetEntity: "HORSE_WITHDRAWAL", targetId: 3 };
    expect(notificationTarget(n, { role: "CLUB_MANAGER" })).toBe("/manager/withdrawals");
    expect(notificationTarget(n, { role: "HORSE_OWNER" })).toBeNull();
  });

  test("a horse opens the horse page of the viewer's role", () => {
    const n = { targetEntity: "HORSE", targetId: 5 };
    expect(notificationTarget(n, { role: "HORSE_OWNER" })).toBe("/horse-owner/horses/5");
    expect(notificationTarget(n, { role: "CLUB_MANAGER" })).toBe("/manager/horses/5");
  });

  test("a care task opens the groom's stable map, with or without a horse attached", () => {
    const bulk = { type: "DAILY_TASK_ASSIGNED", targetEntity: null, targetId: null };
    const single = { type: "DAILY_TASK_ASSIGNED", targetEntity: "HORSE", targetId: 6 };
    const handedOver = { type: "DAILY_TASK_REASSIGNED", targetEntity: "HORSE", targetId: 6 };
    expect(notificationTarget(bulk, { role: "GROOM" })).toBe("/groom/stable");
    expect(notificationTarget(single, { role: "GROOM" })).toBe("/groom/stable");
    expect(notificationTarget(handedOver, { role: "GROOM" })).toBe("/groom/stable");
  });

  test("notices without a target open a page chosen by their type", () => {
    const missed = { type: "DAILY_TASK_MISSED", targetEntity: null };
    expect(notificationTarget(missed, { role: "HEAD_TRAINER" })).toBe("/trainer/stable-care?tab=missed");
    expect(notificationTarget({ type: "SUPPLY_SHORTFALL" }, { role: "CLUB_MANAGER" })).toBe("/manager/supplies");
    expect(notificationTarget({ type: "SUPPLY_DIGEST" }, { role: "CLUB_MANAGER" })).toBe("/manager/supplies");
    expect(notificationTarget({ type: "WORK_HANDED_OVER" }, { role: "VETERINARIAN" })).toBe("/veterinarian");
    expect(notificationTarget({ type: "TEMP_PASSWORD_SET" }, { role: "GROOM" })).toBe("/change-password");
    expect(notificationTarget({ type: "SUPPLY_REPORTED" }, { role: "CLUB_MANAGER" })).toBe("/manager/supplies");
    expect(notificationTarget({ type: "SUPPLY_COUNT_UP" }, { role: "CLUB_MANAGER" })).toBe("/manager/supplies");
    expect(notificationTarget({ type: "SUPPLY_LOW" }, { role: "VETERINARIAN" })).toBe("/veterinarian/supplies");
  });

  test("a ration change opens the rations page of each role", () => {
    const n = { type: "RATION_CHANGED", targetEntity: "HORSE", targetId: 6 };
    expect(notificationTarget(n, { role: "GROOM" })).toBe("/groom/feeding");
    expect(notificationTarget(n, { role: "HEAD_TRAINER" })).toBe("/trainer/stable-care?tab=rations");
    expect(notificationTarget(n, { role: "VETERINARIAN" })).toBe("/veterinarian/feeding");
  });

  test("an unknown notice without a target still only marks as read", () => {
    expect(notificationTarget({ type: "SOMETHING_NEW" }, { role: "GROOM" })).toBeNull();
    expect(notificationTarget({ type: "SUPPLY_LOW" }, { role: "HORSE_OWNER" })).toBeNull();
  });
});
