/* =============================================================================
   V28 - Medicine Pricing, Supply Link, and Treatment Consent.

   PROBLEM
   1. SUPPLY_STOCK had no unit_price, so medicine costs could not be computed
      from stock catalog.
   2. TREATMENT had no link to SUPPLY_STOCK, no quantity, unit_price, or total_cost,
      leaving owners with unexplained bulk medical charges.
   3. Major treatments lacked an explicit owner consent requirement.

   FIX
   - Add unit_price to SUPPLY_STOCK.
   - Add supply_id, quantity, unit_price, requires_consent, consent_status,
     consented_at to TREATMENT.
   - Link supply_id to SUPPLY_STOCK(supply_id).
   ============================================================================= */

IF COL_LENGTH('SUPPLY_STOCK', 'unit_price') IS NULL
    ALTER TABLE SUPPLY_STOCK ADD unit_price DECIMAL(12,2) NULL;
GO

-- Seed some realistic default prices for existing medicine stock
UPDATE SUPPLY_STOCK
SET unit_price = 150000.00
WHERE category = 'MEDICINE' AND unit_price IS NULL;
GO

IF COL_LENGTH('TREATMENT', 'supply_id') IS NULL
    ALTER TABLE TREATMENT ADD supply_id INT NULL;
GO

IF COL_LENGTH('TREATMENT', 'quantity') IS NULL
    ALTER TABLE TREATMENT ADD quantity DECIMAL(8,2) NULL;
GO

IF COL_LENGTH('TREATMENT', 'unit_price') IS NULL
    ALTER TABLE TREATMENT ADD unit_price DECIMAL(12,2) NULL;
GO

IF COL_LENGTH('TREATMENT', 'requires_consent') IS NULL
    ALTER TABLE TREATMENT ADD requires_consent BIT NOT NULL CONSTRAINT DF_TRT_req_consent DEFAULT 0;
GO

IF COL_LENGTH('TREATMENT', 'consent_status') IS NULL
    ALTER TABLE TREATMENT ADD consent_status VARCHAR(20) NOT NULL CONSTRAINT DF_TRT_consent DEFAULT 'NOT_REQUIRED';
GO

IF COL_LENGTH('TREATMENT', 'consented_at') IS NULL
    ALTER TABLE TREATMENT ADD consented_at DATETIME2 NULL;
GO

IF OBJECT_ID('FK_TRT_supply', 'F') IS NULL
    ALTER TABLE TREATMENT ADD CONSTRAINT FK_TRT_supply
        FOREIGN KEY (supply_id) REFERENCES SUPPLY_STOCK(supply_id);
GO

IF OBJECT_ID('CK_TRT_consent', 'C') IS NULL
    ALTER TABLE TREATMENT ADD CONSTRAINT CK_TRT_consent
        CHECK (consent_status IN ('NOT_REQUIRED', 'WAITING_CONSENT', 'CONSENTED', 'REJECTED'));
GO

PRINT 'V28 done.';
GO
