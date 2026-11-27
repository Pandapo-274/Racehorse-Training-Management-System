/* =============================================================================
   V66 - Add training_shift (MORNING / AFTERNOON) to PLAN_PHASE and drop
         CK_SESSION_video_trial so Groom can attach training videos on any
         session while Trial Run remains an Owner notification flag.
   Idempotent: safe to re-run.
   ============================================================================= */

IF COL_LENGTH('PLAN_PHASE', 'training_shift') IS NULL
BEGIN
    ALTER TABLE PLAN_PHASE
        ADD training_shift VARCHAR(20) NOT NULL CONSTRAINT DF_PHASE_shift DEFAULT 'MORNING';
END
GO

IF OBJECT_ID('CK_PHASE_training_shift', 'C') IS NULL
    ALTER TABLE PLAN_PHASE ADD CONSTRAINT CK_PHASE_training_shift
        CHECK (training_shift IN ('MORNING', 'AFTERNOON'));
GO

IF OBJECT_ID('CK_SESSION_video_trial', 'C') IS NOT NULL
    ALTER TABLE TRAINING_SESSION DROP CONSTRAINT CK_SESSION_video_trial;
GO

PRINT 'V66 phase shift and session video constraint update complete.';
GO
