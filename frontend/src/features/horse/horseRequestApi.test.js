// Luật thời hạn, khoảng huấn luyện, giá hợp đồng, nhóm BCS và tuổi theo tháng ở trình duyệt phải khớp backend
// (HorsePolicy.stayProblems, ContractTerms.priceFor, HorsePolicy.bcsGroup, HorsePolicy.ageInMonths).
import { stayProblems, estimatePrice, toRequestStatus, isoAddMonths, trainingWeeksOf } from "./horseRequestApi";
import { toBcsGroup, toAgeInMonths, formatAge } from "./horseApi";

describe("training window (same as HorsePolicy.stayProblems)", () => {
  const today = "2026-11-23";
  const ok = { preferredStart: "2026-12-01", trainingStart: "2026-12-01", trainingEnd: "2026-12-22", contractMonths: "1" };

  test("training is at least 3 weeks", () => {
    expect(stayProblems({ ...ok, trainingEnd: "2026-12-21" }, today)).toHaveProperty("trainingEnd");
    expect(stayProblems(ok, today)).toEqual({});
    expect(trainingWeeksOf(ok.trainingStart, ok.trainingEnd)).toBe(3);
  });

  test("training starts after the horse arrives and ends inside the stay", () => {
    expect(stayProblems({ ...ok, trainingStart: "2026-11-30" }, today)).toHaveProperty("trainingStart");
    expect(stayProblems({ ...ok, trainingEnd: "2027-01-02" }, today).trainingEnd).toMatch(/must end by 2027-01-01/);
    expect(stayProblems({ ...ok, trainingEnd: "2027-01-01" }, today)).toEqual({});
  });

  test("arrival, dates and stay are required", () => {
    const e = stayProblems({ preferredStart: "", trainingStart: "", trainingEnd: "", contractMonths: "" }, today);
    expect(Object.keys(e).sort()).toEqual(["contractMonths", "preferredStart", "trainingEnd", "trainingStart"]);
    expect(stayProblems({ ...ok, preferredStart: "2026-11-22" }, today)).toHaveProperty("preferredStart");
  });

  test("a month is added like Java LocalDate.plusMonths", () => {
    expect(isoAddMonths("2027-01-31", 1)).toBe("2027-02-28");
    expect(isoAddMonths("2026-12-01", 1)).toBe("2027-01-01");
  });
});

describe("age in months (same as HorsePolicy.ageInMonths)", () => {
  const today = new Date(2026, 10, 23); // 23 Nov 2026
  test("counts whole months", () => {
    expect(toAgeInMonths("2021-04-13", today)).toBe(67);
    expect(toAgeInMonths("2026-05-24", today)).toBe(5); // 24th not reached yet
  });
  test("reads as years and months, with the month count", () => {
    expect(formatAge("2021-04-13", today)).toBe("67 months");
    expect(formatAge("2026-05-23", today)).toBe("6 months");
  });
});

describe("contract price (same as ContractTerms.priceFor)", () => {
  const pricing = { monthlyFee: 2000, discounts: { 3: 5, 6: 8, 12: 12 } };

  test("one month has no discount", () => {
    expect(estimatePrice(pricing, 1).total).toBe(2000);
  });

  test("the package discount of the highest tier reached applies", () => {
    expect(estimatePrice(pricing, 3).total).toBe(5700);
    expect(estimatePrice(pricing, 4).total).toBe(7600);
    expect(estimatePrice(pricing, 12).discountPct).toBe(12);
  });
});

describe("body condition groups (same as HorsePolicy.bcsGroup)", () => {
  test("Henneke 1-3 thin, 4-6 ideal, 7-9 fat", () => {
    expect(toBcsGroup(3)).toBe("THIN");
    expect(toBcsGroup(4)).toBe("IDEAL");
    expect(toBcsGroup(6)).toBe("IDEAL");
    expect(toBcsGroup(7)).toBe("FAT");
    expect(toBcsGroup(10)).toBeNull();
  });
});

describe("request status after approval follows the contract", () => {
  test("an approved request shows the contract step", () => {
    expect(toRequestStatus("APPROVED", "SENT").label).toMatch(/sign the contract/);
    expect(toRequestStatus("APPROVED", null).label).toBe("Approved");
  });
});
