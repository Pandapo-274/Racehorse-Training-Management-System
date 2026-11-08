## 2026-11-08T13:22:21Z
You are a teamwork_explorer.
Your working directory is: d:\FPT Student work\Fall26\SWP391\Project\Racehorse-Training-Management-System\.agents\teamwork\teamwork_explorer_survey_frontend\
Your role is: Codebase Researcher (Frontend Architecture & UI Flows)

Authoritative Original Request:
Read ORIGINAL_REQUEST.md at: d:\FPT Student work\Fall26\SWP391\Project\Racehorse-Training-Management-System\.agents\teamwork\ORIGINAL_REQUEST.md

Mission:
Conduct an in-depth survey of the frontend codebase for Requirements R1 through R5:
1. R1: StaffPage (`frontend/src/features/manager/StaffPage.js`, `staffApi.js`):
   - How does Club Manager view and assign Head Trainer to Groom?
   - How is the assignment form or modal implemented?
   - How is the 3 Groom limit displayed, handled, and validated on frontend? What error message is shown if backend returns limit exceeded?
2. R2: HorseListPage (`frontend/src/features/horse/HorseListPage.js`, `HorseDetailPage.js`, `HorseReviewPage.js`, `horseApi.js`):
   - How does HorseListPage fetch and display horses?
   - What happens when a Head Trainer logs in? Does the page filter horses or show all 52 horses?
   - Does frontend pass trainer parameters or does the backend filter based on auth token?
3. R3: SchedulePage & TrainingPlanPage (`frontend/src/features/trainer/TrainingPlanPage.js`, `SchedulePage.js`, `SessionEvaluationPage.js`):
   - When Head Trainer creates a training session, how are Grooms fetched for the dropdown?
   - Are only the Head Trainer's managed Grooms listed? What happens if Head Trainer has 0 grooms?
   - How are Training Plans fetched for the dropdown? Are they restricted to horses managed by the Head Trainer?
4. R4: Notifications UI:
   - Does the frontend have notification components or bell icon displaying in-app notifications for Groom, Head Trainer, and Horse Owner?
5. Identify all frontend files that need modification, state adjustments, or API call fixes.

Output:
Write your structured findings and handoff report to:
`d:\FPT Student work\Fall26\SWP391\Project\Racehorse-Training-Management-System\.agents\teamwork\teamwork_explorer_survey_frontend\handoff.md`
Update your `progress.md` regularly.
When complete, send a message to orchestrator with summary and confirm handoff.md path.
