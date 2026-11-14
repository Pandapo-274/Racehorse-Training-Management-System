// src/features/race/raceApi.test.js
// Kiểm các luật thuần (không gọi API) trong raceApi.js.
//
// isResultLocked chép từ backend (app.race.result.edit-window-days, RaceService.recordResult),
// nên lệch nhau là lỗi thật: Head Trainer thấy nút "Edit result" mà backend sẽ chặn.
import {
  RESULT_EDIT_WINDOW_DAYS, errorText, formatRaceTime, isResultLocked, isTrainersHorse, parseRaceTime,
  toEntryChip,
} from "./raceApi";

/** "YYYY-MM-DD" của hôm nay + days (giờ địa phương, như todayIso). */
function isoPlus(days) {
  const d = new Date();
  d.setDate(d.getDate() + days);
  const p = (n) => String(n).padStart(2, "0");
  return `${d.getFullYear()}-${p(d.getMonth() + 1)}-${p(d.getDate())}`;
}

describe("toEntryChip", () => {
  test("a withdrawn proposal shows as scratched, not pending", () => {
    expect(toEntryChip({ state: "SCRATCHED", result: "SCRATCHED", approvalStatus: "PENDING_OWNER" }).label)
      .toBe("Scratched");
  });

  test("an expired proposal shows as expired", () => {
    expect(toEntryChip({ state: "EXPIRED", result: null, approvalStatus: "PENDING_OWNER" }).label).toBe("Expired");
  });

  test("a finished entry shows its result", () => {
    expect(toEntryChip({ state: "FINISHED", result: "WON", approvalStatus: "APPROVED" }).label).toBe("Won");
  });

  test("a proposal waiting for the owner", () => {
    expect(toEntryChip({ state: "PENDING_APPROVAL", result: null, approvalStatus: "PENDING_OWNER" }).label)
      .toBe("Pending Owner");
  });
});

describe("isResultLocked", () => {
  const today = isoPlus(0);

  test("open on the last day of the window", () => {
    expect(isResultLocked({ state: "FINISHED", result: "WON", raceDate: isoPlus(-RESULT_EDIT_WINDOW_DAYS) }, today))
      .toBe(false);
  });

  test("locked the day after the window", () => {
    expect(isResultLocked({ state: "FINISHED", result: "WON", raceDate: isoPlus(-RESULT_EDIT_WINDOW_DAYS - 1) }, today))
      .toBe(true);
  });

  test("a first result entered late is never locked", () => {
    expect(isResultLocked({ state: "AWAITING_RESULT", result: null, raceDate: isoPlus(-30) }, today)).toBe(false);
  });
});

describe("isTrainersHorse", () => {
  test("own horse and unassigned horse yes, another trainer's horse no", () => {
    expect(isTrainersHorse({ trainerId: 2 }, 2)).toBe(true);
    expect(isTrainersHorse({ trainerId: null }, 2)).toBe(true);
    expect(isTrainersHorse({ trainerId: 3 }, 2)).toBe(false);
  });
});

describe("errorText", () => {
  test("shows the first field error instead of the generic validation message", () => {
    expect(errorText({ message: "Validation failed", fieldErrors: { reason: "Reason is too long" } }))
      .toBe("Reason is too long");
  });

  test("keeps a specific message even when there are field errors", () => {
    expect(errorText({ message: "Position 2 is PLACED, not WON", fieldErrors: { finishPosition: "WON = 1st" } }))
      .toBe("Position 2 is PLACED, not WON");
    expect(errorText({ message: "Entries closed", fieldErrors: {} })).toBe("Entries closed");
  });
});

describe("race time", () => {
  test("parses and formats", () => {
    expect(parseRaceTime("1:58.4")).toBe(118400);
    expect(parseRaceTime("118.4")).toBe(118400);
    expect(parseRaceTime("")).toBeNull();
    expect(Number.isNaN(parseRaceTime("abc"))).toBe(true);
    expect(formatRaceTime(118400)).toBe("1:58.4");
  });
});
