/* =============================================================================
   V43 - Track heart rate readings count and sum for arithmetic average calculation
   ============================================================================= */

IF COL_LENGTH('TRAINING_SESSION', 'hr_reading_count') IS NULL
    ALTER TABLE TRAINING_SESSION ADD hr_reading_count INT NULL;
GO

IF COL_LENGTH('TRAINING_SESSION', 'hr_reading_sum') IS NULL
    ALTER TABLE TRAINING_SESSION ADD hr_reading_sum INT NULL;
GO
