"""Kiểm thử end-to-end 3 flow bắt buộc (hồ sơ ngựa, giáo án, y tế), rút ngựa và tài khoản.

Chạy qua HTTP API đúng như người dùng thật, mỗi bước bằng đúng vai trò. Gồm cả
luồng đúng lẫn các ca ngoại lệ hệ thống phải chặn.

CHỈ chạy trên một database thử nghiệm riêng: script ghi dữ liệu thật và dời
ngày bằng SQL. Xem README.md cùng thư mục để dựng database và backend thử.

    python flows_1_3_e2e.py --base http://localhost:8081 --db rtms_e2e
"""
import argparse
import base64
import json
import subprocess
import sys
import time
import urllib.error
import urllib.request
import uuid
from datetime import date, datetime, timedelta

sys.stdout.reconfigure(encoding="utf-8")
ap = argparse.ArgumentParser()
ap.add_argument("--base", default="http://localhost:8081")
ap.add_argument("--db", default="rtms_e2e", help="database THỬ NGHIỆM, tên phải chứa e2e hoặc test")
ap.add_argument("--sql-server", default="localhost,1433")
ARGS = ap.parse_args()
if "e2e" not in ARGS.db.lower() and "test" not in ARGS.db.lower():
    sys.exit(f"Refusing to run against '{ARGS.db}': use a throwaway database whose name contains e2e or test.")

BASE = ARGS.base
TODAY = date.today()
NOW = datetime.now().replace(microsecond=0)
RUN = uuid.uuid4().hex[:4].upper()          # để chạy lại nhiều lần trên cùng DB
results = []
flow = "setup"


# ------------------------------------------------------------------ helpers
def call(method, path, token=None, body=None, raw=None, ctype=None):
    data = raw if raw is not None else (json.dumps(body).encode() if body is not None else None)
    req = urllib.request.Request(BASE + path, data=data, method=method)
    req.add_header("Content-Type", ctype or "application/json")
    if token:
        req.add_header("Authorization", "Bearer " + token)
    try:
        with urllib.request.urlopen(req, timeout=30) as r:
            p = r.read()
            return r.status, (json.loads(p) if p.strip() else None)
    except urllib.error.HTTPError as e:
        p = e.read()
        try:
            return e.code, json.loads(p)
        except Exception:
            return e.code, p.decode("utf-8", "replace")[:300]


def check(name, ok, detail=""):
    results.append((flow, name, bool(ok), detail))
    print(("  ok   " if ok else "  FAIL ") + name + (f"  -> {detail}" if detail and not ok else ""))
    return ok


def expect(name, status, body, want):
    wants = want if isinstance(want, (list, tuple, set)) else [want]
    msg = body.get("message") if isinstance(body, dict) else body
    return check(name, status in wants, f"HTTP {status}: {msg}")


def login(username, password="123456"):
    st, body = call("POST", "/api/auth/login", body={"username": username, "password": password})
    return (body["token"] if st == 200 else None), st, body


def notified(token, ntype, contains=None):
    st, rows = call("GET", "/api/notifications?limit=100", token)
    return st == 200 and any(r["type"] == ntype and (contains is None or contains in
                             (r.get("message") or "") + (r.get("title") or "")) for r in rows)


def sql(query):
    out = subprocess.run(["sqlcmd", "-S", ARGS.sql_server, "-E", "-C", "-I", "-b", "-d", ARGS.db,
                          "-W", "-h", "-1", "-Q", "SET NOCOUNT ON; " + query], capture_output=True, text=True)
    return out.returncode, (out.stdout + out.stderr).strip()


def at(d, hh="09:00:00"):
    return f"{d.isoformat()}T{hh}"


def dob(years):
    return (TODAY.replace(year=TODAY.year - years) - timedelta(days=10)).isoformat()


PNG = base64.b64decode("iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJAAAADUlEQVR42mNk+M9QDwADhgGAWjR9awAAAABJRU5ErkJggg==")


def multipart(fields, file_field=None, filename=None, content=None):
    b = "----e2e" + uuid.uuid4().hex
    parts = [f'--{b}\r\nContent-Disposition: form-data; name="{k}"\r\n\r\n{v}\r\n'.encode() for k, v in fields.items()]
    if file_field:
        parts.append((f'--{b}\r\nContent-Disposition: form-data; name="{file_field}"; filename="{filename}"\r\n'
                      f'Content-Type: image/png\r\n\r\n').encode() + content + b"\r\n")
    parts.append(f"--{b}--\r\n".encode())
    return b"".join(parts), f"multipart/form-data; boundary={b}"


def register_horse(name, gender, years, code, stall):
    st, rq = call("POST", "/api/horse-requests", T["owner"], {"horseName": name, "horseGender": gender,
                                                               "dateOfBirth": dob(years), "recordType": "ACADEMY"})
    st, ok = call("PUT", f"/api/horse-requests/{rq['requestId']}/approve", T["manager"],
                  {"registrationCode": code, "stallNo": stall})
    return ok["horseId"]


def new_plan(horse, name, spw=3, days=60):
    return call("POST", "/api/training-plans", T["trainer"], {
        "horseId": horse, "planName": name, "startDate": TODAY.isoformat(),
        "endDate": (TODAY + timedelta(days=days)).isoformat(),
        "phases": [{"phaseName": "Base", "distance": 1600, "workload": "Moderate", "surface": "TURF",
                    "sessionsPerWeek": spw, "heartRateCeiling": 180, "startDate": TODAY.isoformat(),
                    "endDate": (TODAY + timedelta(days=30)).isoformat()}]})


def schedule(phase, when, groom=None, dist=None):
    return call("POST", "/api/training-sessions", T["trainer"],
                {"phaseId": phase, "assignedTo": groom or GROOM_ID, "sessionDate": when, "plannedDistance": dist})


# ------------------------------------------------------------------ setup
print(f"== setup (run {RUN}, {BASE}, db {ARGS.db})")
T = {}
for role, u in [("manager", "hoa.tm"), ("trainer", "thang.nd"), ("vet", "ha.pt"),
                ("owner", "anh.dq"), ("owner2", "son.dv")]:
    tok, st, body = login(u)
    check(f"login {role} ({u})", tok, f"HTTP {st}")
    T[role] = tok
# Mỗi lần chạy một Groom riêng: lịch của Groom dùng chung giữa các lần chạy sẽ làm
# luật chống trùng giờ chặn (đúng) các buổi của lần sau.
g_user = f"e2e.groom{RUN.lower()}"
st, g = call("POST", "/api/staff", T["manager"], {"username": g_user, "password": "Temp1234", "fullName": f"E2E Groom {RUN}",
                                                   "email": f"{g_user}@example.com", "phone": "", "roleName": "GROOM"})
tok, _, _ = login(g_user, "Temp1234")
call("PUT", "/api/auth/change-password", tok, {"currentPassword": "Temp1234", "newPassword": "Groom1234"})
T["groom"], st, _ = login(g_user, "Groom1234")
check(f"(setup) dedicated groom {g_user}", T["groom"], f"HTTP {st}")
GROOM_ID = call("GET", "/api/auth/me", T["groom"])[1]["userId"]
NAME = f"E2E Thunder {RUN}"

# ================================================================== FLOW 1
flow = "Flow 1 - Horse records"
print("== " + flow)
st, req1 = call("POST", "/api/horse-requests", T["owner"], {
    "horseName": NAME, "breed": "Thoroughbred", "horseGender": "COLT", "dateOfBirth": dob(4),
    "color": "Bay", "note": "e2e", "recordType": "ACADEMY"})
expect("owner submits a registration request", st, req1, 201)
check("manager is notified of the request", notified(T["manager"], "HORSE_REQUEST_SUBMITTED", NAME))
st, b = call("POST", "/api/horse-requests", T["owner"], {"horseName": NAME, "dateOfBirth": dob(4)})
expect("duplicate pending request is refused", st, b, 409)
st, b = call("POST", "/api/horse-requests", T["owner"], {"horseName": f"Too old {RUN}", "dateOfBirth": dob(30)})
expect("club horse older than 25 is refused", st, b, 400)

st, ok = call("PUT", f"/api/horse-requests/{req1['requestId']}/approve", T["manager"],
              {"registrationCode": f"E2E-{RUN}", "stallNo": f"Z-{RUN}"})
expect("manager approves with a code and a stall", st, ok, 200)
HORSE = ok.get("horseId") if isinstance(ok, dict) else None
check("owner is notified of the approval", notified(T["owner"], "HORSE_REQUEST_APPROVED", NAME))
st, h = call("GET", f"/api/horses/{HORSE}", T["owner"])
check("new horse is ACTIVE, ELIGIBLE, in its stall", st == 200 and h["activityStatus"] == "ACTIVE"
      and h["status"] == "ELIGIBLE" and h["stallNo"] == f"Z-{RUN}", str(h)[:150])
expect("another owner cannot open it", *call("GET", f"/api/horses/{HORSE}", T["owner2"]), 403)

st, b = call("PUT", f"/api/horses/{HORSE}", T["owner"], {"horseName": NAME, "breed": "Thoroughbred",
                                                          "horseGender": "COLT", "dateOfBirth": dob(4),
                                                          "color": "Dark bay", "sireId": None, "damId": None})
expect("owner edits the basics", st, b, 200)
YOUNG = register_horse(f"E2E Young {RUN}", "COLT", 2, f"E2Y-{RUN}", f"Y-{RUN}")
st, b = call("PUT", f"/api/horses/{HORSE}", T["owner"], {"horseName": NAME, "horseGender": "COLT",
                                                          "dateOfBirth": dob(4), "sireId": YOUNG, "damId": None})
expect("a 2-year-old cannot be the sire of a 4-year-old", st, b, 400)
for sub in ("pedigree", "vitals"):
    expect(f"owner views {sub}", *call("GET", f"/api/horses/{HORSE}/{sub}", T["owner"]), 200)
raw, ct = multipart({}, "file", "h.png", PNG)
expect("owner uploads a horse photo", *call("POST", f"/api/horses/{HORSE}/photo", T["owner"], raw=raw, ctype=ct), 200)

st, b = call("PUT", f"/api/horses/{HORSE}/identity", T["manager"], {"registrationCode": f"E2E-{RUN}",
                                                                    "stallNo": f"Y-{RUN}"})
expect("manager cannot move the horse into an occupied stall", st, b, 400)
st, b = call("PUT", f"/api/horses/{HORSE}/identity", T["manager"], {"registrationCode": f"E2E-{RUN}",
                                                                    "stallNo": f"W-{RUN}"})
check("manager corrects the stall", st == 200 and b.get("stallNo") == f"W-{RUN}", f"HTTP {st}")
expect("owner cannot change the club records", *call("PUT", f"/api/horses/{HORSE}/identity", T["owner"],
                                                     {"registrationCode": "X", "stallNo": "X"}), 403)

st, rq2 = call("POST", "/api/horse-requests", T["owner"], {"horseName": f"E2E Cancel {RUN}", "recordType": "PEDIGREE"})
st, b = call("PUT", f"/api/horse-requests/{rq2['requestId']}/cancel", T["owner"])
check("owner cancels a pending request", st == 200 and b.get("status") == "CANCELLED")
expect("a cancelled request cannot be approved", *call("PUT", f"/api/horse-requests/{rq2['requestId']}/approve",
                                                       T["manager"], {"registrationCode": f"X-{RUN}"}), 409)
st, rq3 = call("POST", "/api/horse-requests", T["owner"], {"horseName": f"E2E Reject {RUN}", "dateOfBirth": dob(4)})
expect("reject without a reason is refused", *call("PUT", f"/api/horse-requests/{rq3['requestId']}/reject",
                                                   T["manager"], {"note": ""}), 400)
expect("manager rejects with a reason", *call("PUT", f"/api/horse-requests/{rq3['requestId']}/reject",
                                              T["manager"], {"note": "Papers missing"}), 200)
check("owner sees the reason", notified(T["owner"], "HORSE_REQUEST_REJECTED", "Papers missing"))

# ================================================================== FLOW 2
flow = "Flow 2 - Training"
print("== " + flow)
st, plan = new_plan(HORSE, f"E2E plan {RUN}", spw=6)
expect("trainer creates a plan with a phase", st, plan, 201)
PLAN, PHASE = plan["planId"], plan["phases"][0]["phaseId"]
expect("plan DRAFT -> ACTIVE", *call("PUT", f"/api/training-plans/{PLAN}/status", T["trainer"], {"status": "ACTIVE"}), 200)
st, p2 = new_plan(HORSE, f"E2E second {RUN}")
expect("a second ACTIVE plan for the same horse is refused",
       *call("PUT", f"/api/training-plans/{p2['planId']}/status", T["trainer"], {"status": "ACTIVE"}), 409)

OTHER = register_horse(f"E2E Other {RUN}", "FILLY", 4, f"E2O-{RUN}", f"O-{RUN}")
st, old = new_plan(OTHER, f"E2E expired {RUN}")
rc, out = sql(f"UPDATE PLAN_PHASE SET start_date = DATEADD(DAY,-50,start_date), end_date = DATEADD(DAY,-50,end_date) "
              f"WHERE plan_id = {old['planId']}; UPDATE TRAINING_PLAN SET start_date = DATEADD(DAY,-80,start_date), "
              f"end_date = DATEADD(DAY,-80,end_date) WHERE plan_id = {old['planId']}")
check("(test DB) move a draft plan into the past", rc == 0, out)
expect("a plan past its end date cannot be activated",
       *call("PUT", f"/api/training-plans/{old['planId']}/status", T["trainer"], {"status": "ACTIVE"}), 409)
st, po = new_plan(OTHER, f"E2E other {RUN}")
call("PUT", f"/api/training-plans/{po['planId']}/status", T["trainer"], {"status": "ACTIVE"})
OTHER_PHASE = po["phases"][0]["phaseId"]

st, s1 = schedule(PHASE, at(TODAY, "08:00:00"))
expect("trainer schedules a session today", st, s1, 201)
S1 = s1["sessionId"]
check("planned distance defaults to the phase", s1.get("plannedDistance") == 1600)
check("groom is notified", notified(T["groom"], "TRAINING_SESSION_ASSIGNED", NAME))
expect("session in the past is refused", *schedule(PHASE, at(TODAY - timedelta(days=1))), 400)
expect("same horse 30 minutes later is refused", *schedule(PHASE, at(TODAY, "08:30:00")), 409)
expect("same groom with another horse at the same time is refused", *schedule(OTHER_PHASE, at(TODAY, "08:00:00")), 409)
expect("trainer reschedules", *call("PUT", f"/api/training-sessions/{S1}/schedule", T["trainer"],
                                    {"assignedTo": GROOM_ID, "sessionDate": at(TODAY, "10:30:00")}), 200)
monday = TODAY - timedelta(days=TODAY.weekday()) + timedelta(days=14)
week = [schedule(OTHER_PHASE, at(monday + timedelta(days=i))) for i in range(4)]
check("phase limit of 3 sessions/week: 3 accepted, the 4th refused",
      [w[0] for w in week] == [201, 201, 201, 409], str([w[0] for w in week]))

call("PUT", f"/api/training-plans/{PLAN}/status", T["trainer"], {"status": "PAUSED"})
expect("a session of a paused plan cannot start", *call("PUT", f"/api/training-sessions/{S1}/start", T["groom"]), 409)
call("PUT", f"/api/training-plans/{PLAN}/status", T["trainer"], {"status": "ACTIVE"})
st, fut = schedule(PHASE, at(TODAY + timedelta(days=1), "06:00:00"))
expect("tomorrow's session cannot be started today", *call("PUT", f"/api/training-sessions/{fut['sessionId']}/start",
                                                           T["groom"]), 409)
st, sx = schedule(PHASE, at(TODAY, "13:00:00"))
SX = sx["sessionId"]
expect("groom starts the session", *call("PUT", f"/api/training-sessions/{S1}/start", T["groom"]), 200)
expect("a second session of the same horse cannot run at the same time",
       *call("PUT", f"/api/training-sessions/{SX}/start", T["groom"]), 409)
st, v = call("POST", f"/api/sessions/{S1}/vitals", T["groom"], {"heartRate": 150, "velocity": 15.2})
check("normal vitals raise no alert", st in (200, 201) and not v.get("alertRaised"))
st, v = call("POST", f"/api/sessions/{S1}/vitals", T["groom"], {"heartRate": 150, "velocity": 19.5})
check("speed over 18 m/s raises an alert", st in (200, 201) and v.get("alertRaised"), str(v)[:120])
st, v = call("POST", f"/api/sessions/{S1}/vitals", T["groom"], {"heartRate": 205})
check("heart rate 205 over a 180 ceiling raises a HIGH alert", v.get("alertRaised") and v.get("severity") == "HIGH")
expect("trainer resolves with VET_CALLED", *call("PUT", f"/api/alerts/{v['alertId']}/resolve", T["trainer"],
                                                 {"actionTaken": "VET_CALLED"}), 200)
check("...and the vet is notified", notified(T["vet"], "ALERT_VET_CALLED", NAME))
expect("trainer evaluates the session", *call("PUT", f"/api/sessions/{S1}/evaluation", T["trainer"], {
    "actualDistance": 1600, "durationMinutes": 30, "avgHeartRate": 160, "peakHeartRate": 205,
    "recoveryHeartRate": 95, "peakVelocity": 19.5, "evaluation": "GOOD", "remarks": "e2e", "status": "COMPLETED"}), 200)
expect("peak heart rate below average is refused", *call("PUT", f"/api/sessions/{S1}/evaluation", T["trainer"], {
    "actualDistance": 1600, "durationMinutes": 30, "avgHeartRate": 160, "peakHeartRate": 150,
    "evaluation": "GOOD"}), 400)
expect("trainer marks a trial run with a video", *call("PUT", f"/api/sessions/{S1}/trial-run", T["trainer"],
                                                       {"trialRun": True, "videoUrl": "https://example.com/v.mp4"}), 200)
st, own = call("GET", "/api/sessions", T["owner"])
check("owner sees the evaluation and the video", st == 200 and any(
    s["sessionId"] == S1 and s.get("evaluation") == "GOOD" and s.get("videoUrl") for s in own))

expect("13:00 session starts once the first one is over", *call("PUT", f"/api/training-sessions/{SX}/start", T["groom"]), 200)
st, v = call("POST", f"/api/sessions/{SX}/vitals", T["groom"], {"heartRate": 210})
call("PUT", f"/api/alerts/{v['alertId']}/resolve", T["trainer"], {"actionTaken": "STOP_SESSION"})
st, sxs = call("GET", f"/api/training-sessions/{SX}", T["trainer"])
check("STOP_SESSION stops the session", sxs.get("status") == "STOPPED_EARLY", sxs.get("status"))
check("...and the groom is notified", notified(T["groom"], "TRAINING_SESSION_STOPPED", NAME))

st, b = call("PUT", f"/api/horses/{HORSE}", T["owner"], {"horseName": NAME, "horseGender": "GELDING",
                                                          "dateOfBirth": dob(4), "sireId": None, "damId": None})
expect("sex can no longer change once the horse has trained", st, b, 400)

# ================================================================== FLOW 3
flow = "Flow 3 - Medical & injury"
print("== " + flow)
st, s2 = schedule(PHASE, at(TODAY, "15:00:00"))
S2 = s2.get("sessionId") if isinstance(s2, dict) else None
call("PUT", f"/api/training-sessions/{S2}/start", T["groom"])
st, stop = call("PUT", f"/api/training-sessions/{S2}/stop", T["groom"],
                {"symptoms": "Lame on left fore", "description": "e2e", "urgency": "HIGH"})
check("groom stops a running session and reports", st in (200, 201) and stop.get("sessionStatus") == "STOPPED_EARLY",
      f"HTTP {st} {str(stop)[:120]}")
REPORT = stop["report"]["reportId"]
check("vet is notified", notified(T["vet"], "INCIDENT_REPORTED", NAME))
expect("trainer cannot schedule while the report is open", *schedule(PHASE, at(TODAY + timedelta(days=3))), 409)
expect("vet receives the report", *call("PUT", f"/api/incident-reports/{REPORT}/receive", T["vet"]), 200)
expect("receiving it twice is refused", *call("PUT", f"/api/incident-reports/{REPORT}/receive", T["vet"]), 409)
expect("confirmed injury without INJURED status is refused", *call("POST", "/api/medical-records", T["vet"], {
    "horseId": HORSE, "recordType": "EXAM", "examDate": NOW.isoformat(), "diagnosis": "x",
    "healthStatus": "MONITOR", "injuryConfirmed": True}), 400)
st, rec = call("POST", "/api/medical-records", T["vet"], {
    "horseId": HORSE, "reportId": REPORT, "recordType": "EXAM", "examDate": NOW.isoformat(),
    "diagnosis": "Tendon strain", "healthStatus": "INJURED", "injuryConfirmed": True,
    "expectedRestDays": 14, "notes": "Rest and ice", "weight": 482.5})
expect("vet records the exam (injury, 14 days rest, weight)", st, rec, [200, 201])
RECORD = rec["recordId"]
h = call("GET", f"/api/horses/{HORSE}", T["vet"])[1]
check("horse is INJURED, weight 482.5", h["status"] == "INJURED" and float(h["weight"]) == 482.5)
check("owner is told", notified(T["owner"], "HEALTH_STATUS_CHANGED", NAME))
st, inj = call("POST", "/api/injuries", T["vet"], {"recordId": RECORD, "bodyLocation": "Left fore tendon",
                                                   "coordinateX": 31.5, "coordinateY": 58, "severity": "GRADE_2"})
expect("vet marks the injury", st, inj, [200, 201])
INJURY = inj["injuryId"]
st, lock = call("POST", "/api/training-locks", T["vet"], {"horseId": HORSE, "injuryId": INJURY,
                                                           "reason": "Tendon strain", "lockLevel": "FULL"})
expect("vet issues a full training lock", st, lock, [200, 201])
expect("a second lock is refused", *call("POST", "/api/training-locks", T["vet"], {
    "horseId": HORSE, "reason": "again", "lockLevel": "FULL"}), 400)
expect("trainer cannot schedule an injured, locked horse", *schedule(PHASE, at(TODAY + timedelta(days=3))), 409)
expect("lock cannot be released while the injury is open",
       *call("POST", f"/api/training-locks/{lock['lockId']}/release", T["vet"]), 409)
expect("injured horse with an open injury cannot be moved to MONITOR", *call("POST", "/api/medical-records", T["vet"], {
    "horseId": HORSE, "recordType": "EXAM", "examDate": NOW.isoformat(), "diagnosis": "Better",
    "healthStatus": "MONITOR", "injuryConfirmed": False}), 400)
st, trf = call("POST", "/api/treatments", T["vet"], {"recordId": RECORD, "medicineName": "Bute",
                                                      "startDate": (TODAY + timedelta(days=5)).isoformat(),
                                                      "endDate": (TODAY + timedelta(days=10)).isoformat()})
expect("a treatment that has not started cannot end today",
       *call("PUT", f"/api/treatments/{trf['treatmentId']}/end", T["vet"]), 409)
expect("vet prescribes a treatment from today", *call("POST", "/api/treatments", T["vet"], {
    "recordId": RECORD, "medicineName": "Phenylbutazone", "dosage": "2 g", "frequency": "Daily",
    "startDate": TODAY.isoformat(), "endDate": (TODAY + timedelta(days=5)).isoformat()}), [200, 201])
expect("recovery is refused while the injury is open", *call("POST", "/api/recovery-confirmations", T["vet"], {
    "horseId": HORSE, "recoveredInjuryIds": [], "examDate": NOW.isoformat(), "diagnosis": "Too early"}), 400)
expect("recovery before the 14 rest days without a reason is refused", *call("POST", "/api/recovery-confirmations",
       T["vet"], {"horseId": HORSE, "recoveredInjuryIds": [INJURY], "examDate": NOW.isoformat(), "diagnosis": "Healed"}), 400)
expect("recovery with a reason is accepted", *call("POST", "/api/recovery-confirmations", T["vet"], {
    "horseId": HORSE, "recoveredInjuryIds": [INJURY], "examDate": NOW.isoformat(), "diagnosis": "Healed",
    "notes": "Ultrasound clear, healed faster than expected"}), [200, 201])
h = call("GET", f"/api/horses/{HORSE}", T["vet"])[1]
check("horse is ELIGIBLE again", h["status"] == "ELIGIBLE", h["status"])
check("lock was released", all(l["status"] != "ACTIVE" for l in call("GET", f"/api/training-locks?horseId={HORSE}", T["vet"])[1]))
check("owner is told the horse is cleared", notified(T["owner"], "HEALTH_CLEARED", NAME))
st, hv = call("POST", "/api/training-locks", T["vet"], {"horseId": HORSE, "reason": "Easy week",
                                                         "lockLevel": "HEAVY_WORK_ONLY"})
expect("vet issues a light-work-only lock", st, hv, [200, 201])
expect("a 2000 m session is refused under the light-work lock",
       *schedule(PHASE, at(TODAY + timedelta(days=3), "07:00:00"), dist=2000), 409)
st, light = schedule(PHASE, at(TODAY + timedelta(days=3), "07:00:00"), dist=800)
expect("an 800 m session is allowed under the light-work lock", st, light, 201)
expect("light-work lock (no injury) can be released", *call("POST", f"/api/training-locks/{hv['lockId']}/release", T["vet"]), 200)

# ================================================================== FLOW 1 end - withdrawal
flow = "Withdrawal"
print("== " + flow)
st, event = call("POST", "/api/races/events", T["trainer"], {
    "raceName": f"E2E Cup {RUN}", "raceDate": (TODAY + timedelta(days=25)).isoformat(), "distanceM": 1600,
    "venue": "Tracen", "entryDeadline": (TODAY + timedelta(days=20)).isoformat(), "entryFee": 0, "maxEntries": 12})
st2, entry = call("POST", "/api/races", T["trainer"], {"horseId": HORSE, "raceId": (event or {}).get("raceId")}) \
    if st in (200, 201) else (st, event)
has_race = st2 in (200, 201)
check("(setup) horse entered in a future race", has_race, f"HTTP {st2}: {entry if not has_race else ''}")
call("POST", "/api/daily-tasks", T["trainer"], {"horseId": HORSE, "assignedTo": GROOM_ID,
                                                "taskDate": (TODAY + timedelta(days=1)).isoformat(),
                                                "taskTypes": ["BATHING"], "note": "e2e"})
raw, ct = multipart({"horseId": HORSE, "symptoms": "Off feed", "urgency": "LOW"}, "photo", "x.png", PNG)
st, inc = call("POST", "/api/incident-reports", T["groom"], raw=raw, ctype=ct)
check("(setup) open stable incident", st in (200, 201), f"HTTP {st}")
st, wd = call("POST", "/api/horse-withdrawals", T["owner"], {"horseId": HORSE, "reason": "Moving stables"})
expect("owner asks to withdraw the horse", st, wd, 201)
expect("approve", *call("PUT", f"/api/horse-withdrawals/{wd['withdrawalId']}/approve", T["manager"], {}), 200)
h = call("GET", f"/api/horses/{HORSE}", T["manager"])[1]
check("horse is WITHDRAWN with no stall", h["activityStatus"] == "WITHDRAWN" and not h.get("stallNo"))
check("plan was completed", call("GET", f"/api/training-plans/{PLAN}", T["trainer"])[1]["status"] == "COMPLETED")
check("scheduled sessions were cancelled",
      call("GET", f"/api/training-sessions/{light['sessionId']}", T["trainer"])[1]["status"] == "CANCELLED")
check("groom's schedule hides the horse", not any(s["horseId"] == HORSE for s in call("GET", "/api/training-sessions", T["groom"])[1]))
check("groom's tasks hide the horse", not any(t["horseId"] == HORSE for t in
      call("GET", f"/api/daily-tasks?date={TODAY + timedelta(days=1)}", T["groom"])[1]))
check("vet's incident queue hides the horse", not any(r["horseId"] == HORSE for r in call("GET", "/api/incident-reports", T["vet"])[1]))
if has_race:
    races = call("GET", "/api/races", T["trainer"])[1]
    check("future race entry was scratched", any(r.get("horseId") == HORSE and r.get("state") == "SCRATCHED" for r in races))
expect("vet cannot examine a withdrawn horse", *call("POST", "/api/medical-records", T["vet"], {
    "horseId": HORSE, "recordType": "EXAM", "examDate": NOW.isoformat(), "diagnosis": "x", "injuryConfirmed": False}), 400)
expect("vet cannot lock a withdrawn horse", *call("POST", "/api/training-locks", T["vet"], {
    "horseId": HORSE, "reason": "x", "lockLevel": "FULL"}), 400)
expect("no new plan for a withdrawn horse", *new_plan(HORSE, "late"), 400)

# ================================================================== accounts
flow = "Accounts & access"
print("== " + flow)
uname = f"e2e.g{RUN.lower()}"
st, staff = call("POST", "/api/staff", T["manager"], {"username": uname, "password": "Temp1234", "fullName": "E2E Groom",
                                                       "email": f"{uname}@example.com", "phone": "", "roleName": "GROOM"})
expect("manager creates a staff account", st, staff, 201)
tok, st, _ = login(uname, "Temp1234")
expect("temporary password blocks other APIs (428)", *call("GET", "/api/horses", tok), 428)
expect("staff changes the temporary password", *call("PUT", "/api/auth/change-password", tok,
       {"currentPassword": "Temp1234", "newPassword": "NewPass123"}), [200, 204])
tok, st, _ = login(uname, "NewPass123")
expect("the account works after that", *call("GET", "/api/horses", tok), 200)
call("PUT", f"/api/staff/{staff['userId']}/role", T["manager"], {"roleName": "VETERINARIAN"})
expect("old token stops working after a role change", *call("GET", "/api/horses", tok), 401)
tok, st, _ = login(uname, "NewPass123")
call("PUT", f"/api/staff/{staff['userId']}/status", T["manager"], {"status": "SUSPENDED"})
expect("token stops working at once after suspension", *call("GET", "/api/horses", tok), 401)
check("suspended account cannot sign in", login(uname, "NewPass123")[0] is None)
tok, _, _ = login(g_user, "Groom1234")
expect("logout", *call("POST", "/api/auth/logout", tok), 200)
expect("token rejected after logout", *call("GET", "/api/horses", tok), 401)
expect("groom cannot open staff management", *call("GET", "/api/staff", T["groom"]), 403)
expect("unknown path parameter type gives 400, not 500", *call("GET", "/api/horses/abc", T["manager"]), 400)

# ------------------------------------------------------------------ summary
print("\n== summary")
by = {}
for f, n, ok, d in results:
    by.setdefault(f, [0, 0])[0 if ok else 1] += 1
for f, (g, b) in by.items():
    print(f"  {f}: {g} ok, {b} failed")
fails = [(f, n, d) for f, n, ok, d in results if not ok]
print(f"\nTOTAL {len(results) - len(fails)}/{len(results)} passed")
for f, n, d in fails:
    print(f"  FAIL [{f}] {n}: {d}")
sys.exit(1 if fails else 0)
