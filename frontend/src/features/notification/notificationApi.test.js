// src/features/notification/notificationApi.test.js
// Race notices open the races page, where the owner or trainer can act on them.
import { notificationTarget } from "./notificationApi";

const owner = { role: "HORSE_OWNER", userId: 7 };
const trainer = { role: "HEAD_TRAINER", userId: 2 };

describe("notificationTarget for race notices", () => {
  test.each(["RACE_REGISTERED", "RACE_WITHDRAW_ADVICE", "RACE_SCRATCHED", "RACE_RESULT", "RACE_AT_RISK"])(
    "%s opens the owner's races page",
    (type) => {
      expect(notificationTarget({ type, targetEntity: "HORSE", targetId: 10 }, owner)).toBe("/horse-owner/races");
    },
  );

  test("the trainer goes to the trainer's races page", () => {
    expect(notificationTarget({ type: "RACE_APPROVED", targetEntity: "HORSE", targetId: 10 }, trainer))
      .toBe("/trainer/races");
  });
});
