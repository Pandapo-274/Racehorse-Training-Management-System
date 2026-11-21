/* =============================================================================
   V48 - What the club needs to see before it approves a horse.

   Review feedback (Flow 1): the manager approved a request without a photo, with
   no weight or height, and with no idea how long the horse would stay. The
   request now carries all of that, so the review page can show it and the
   service can refuse an approval that is missing it.

   HORSE_REQUEST gains:
     photo_url            same /uploads/horses/... path as HORSE.photo_url (V9)
     weight, height_cm    measured by the owner; the manager re-weighs on arrival
     surface, distance_pref, running_style
                          the owner's aptitude guess (pre-filled from the breed,
                          editable), so the club can see what the horse is for
     preferred_start      when the owner wants to bring the horse in
     training_weeks       how long the owner wants it trained (3 weeks minimum)
     contract_months      how long it stays at the club (1 month minimum); this
                          is the term of the contract built at approval (V49)
     preferred_trainer_id the Head Trainer the owner would like; a wish, not a
                          reservation - the manager confirms or overrides it

   HORSE gains height_cm, bcs (Henneke body condition score 1-9, scored by the
   club when the horse arrives) and the three aptitude columns, copied from the
   request when the horse is received.

   Every statement checks first, so the file can run twice.
   ============================================================================= */

IF COL_LENGTH('HORSE_REQUEST', 'photo_url') IS NULL
    ALTER TABLE HORSE_REQUEST ADD photo_url VARCHAR(255) NULL;
GO
IF COL_LENGTH('HORSE_REQUEST', 'weight') IS NULL
    ALTER TABLE HORSE_REQUEST ADD weight DECIMAL(6,2) NULL;
GO
IF COL_LENGTH('HORSE_REQUEST', 'height_cm') IS NULL
    ALTER TABLE HORSE_REQUEST ADD height_cm DECIMAL(5,1) NULL;
GO
IF COL_LENGTH('HORSE_REQUEST', 'surface') IS NULL
    ALTER TABLE HORSE_REQUEST ADD surface VARCHAR(10) NULL;
GO
IF COL_LENGTH('HORSE_REQUEST', 'distance_pref') IS NULL
    ALTER TABLE HORSE_REQUEST ADD distance_pref VARCHAR(10) NULL;
GO
IF COL_LENGTH('HORSE_REQUEST', 'running_style') IS NULL
    ALTER TABLE HORSE_REQUEST ADD running_style VARCHAR(10) NULL;
GO
IF COL_LENGTH('HORSE_REQUEST', 'preferred_start') IS NULL
    ALTER TABLE HORSE_REQUEST ADD preferred_start DATE NULL;
GO
IF COL_LENGTH('HORSE_REQUEST', 'training_weeks') IS NULL
    ALTER TABLE HORSE_REQUEST ADD training_weeks INT NULL;
GO
IF COL_LENGTH('HORSE_REQUEST', 'contract_months') IS NULL
    ALTER TABLE HORSE_REQUEST ADD contract_months INT NULL;
GO
IF COL_LENGTH('HORSE_REQUEST', 'preferred_trainer_id') IS NULL
    ALTER TABLE HORSE_REQUEST ADD preferred_trainer_id INT NULL;
GO

IF OBJECT_ID('FK_HREQ_pref_trainer', 'F') IS NULL
    ALTER TABLE HORSE_REQUEST ADD CONSTRAINT FK_HREQ_pref_trainer
        FOREIGN KEY (preferred_trainer_id) REFERENCES APP_USER(user_id);
GO
IF OBJECT_ID('CK_HREQ_measures', 'C') IS NULL
    ALTER TABLE HORSE_REQUEST ADD CONSTRAINT CK_HREQ_measures CHECK (
        (weight IS NULL OR weight BETWEEN 30 AND 1000)
        AND (height_cm IS NULL OR height_cm BETWEEN 60 AND 200));
GO
IF OBJECT_ID('CK_HREQ_aptitude', 'C') IS NULL
    ALTER TABLE HORSE_REQUEST ADD CONSTRAINT CK_HREQ_aptitude CHECK (
        (surface IS NULL OR surface IN ('TURF', 'DIRT'))
        AND (distance_pref IS NULL OR distance_pref IN ('SPRINT', 'MEDIUM', 'LONG'))
        AND (running_style IS NULL OR running_style IN ('FRONT', 'MIDDLE', 'BACK')));
GO
/* Training must fit inside the stay: 3 weeks minimum, and no longer than the
   contract (a month counted as 4 weeks, the short side, so the rule never lets
   through a plan longer than the paid stay). */
IF OBJECT_ID('CK_HREQ_term', 'C') IS NULL
    ALTER TABLE HORSE_REQUEST ADD CONSTRAINT CK_HREQ_term CHECK (
        (contract_months IS NULL OR contract_months BETWEEN 1 AND 24)
        AND (training_weeks IS NULL OR training_weeks >= 3)
        AND (training_weeks IS NULL OR contract_months IS NULL
             OR training_weeks <= contract_months * 4));
GO

IF COL_LENGTH('HORSE', 'height_cm') IS NULL
    ALTER TABLE HORSE ADD height_cm DECIMAL(5,1) NULL;
GO
IF COL_LENGTH('HORSE', 'bcs') IS NULL
    ALTER TABLE HORSE ADD bcs TINYINT NULL;
GO
IF COL_LENGTH('HORSE', 'surface') IS NULL
    ALTER TABLE HORSE ADD surface VARCHAR(10) NULL;
GO
IF COL_LENGTH('HORSE', 'distance_pref') IS NULL
    ALTER TABLE HORSE ADD distance_pref VARCHAR(10) NULL;
GO
IF COL_LENGTH('HORSE', 'running_style') IS NULL
    ALTER TABLE HORSE ADD running_style VARCHAR(10) NULL;
GO
IF OBJECT_ID('CK_HORSE_bcs', 'C') IS NULL
    ALTER TABLE HORSE ADD CONSTRAINT CK_HORSE_bcs CHECK (bcs IS NULL OR bcs BETWEEN 1 AND 9);
GO
IF OBJECT_ID('CK_HORSE_aptitude', 'C') IS NULL
    ALTER TABLE HORSE ADD CONSTRAINT CK_HORSE_aptitude CHECK (
        (surface IS NULL OR surface IN ('TURF', 'DIRT'))
        AND (distance_pref IS NULL OR distance_pref IN ('SPRINT', 'MEDIUM', 'LONG'))
        AND (running_style IS NULL OR running_style IN ('FRONT', 'MIDDLE', 'BACK')));
GO

IF COL_LENGTH('HORSE_REQUEST', 'preferred_trainer_id') IS NULL
   OR COL_LENGTH('HORSE', 'bcs') IS NULL
    THROW 50480, N'V48: registration review columns were not created.', 1;
GO

PRINT 'V48 done.';
GO
