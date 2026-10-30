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
});
