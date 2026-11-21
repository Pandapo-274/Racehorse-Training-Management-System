// Luật thời hạn, giá hợp đồng và nhóm BCS ở trình duyệt phải khớp backend
// (HorsePolicy.termProblems, ContractTerms.priceFor, HorsePolicy.bcsGroup).
import { termProblems, estimatePrice, toRequestStatus } from "./horseRequestApi";
import { toBcsGroup } from "./horseApi";

describe("stay rules (same as HorsePolicy.termProblems)", () => {
  test("training is at least 3 weeks", () => {
    expect(termProblems("2", "1")).toHaveProperty("trainingWeeks");
    expect(termProblems("3", "1")).toEqual({});
  });

  test("training cannot run longer than the stay", () => {
    expect(termProblems("6", "1").trainingWeeks).toMatch(/longer than the stay/);
    expect(termProblems("8", "2")).toEqual({});
  });

  test("the stay is at least one month", () => {
    expect(termProblems("3", "")).toHaveProperty("contractMonths");
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
