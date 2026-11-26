/* =============================================================================
   V57 - The owner pays separately billed costs online (payOS).

   The monthly boarding fee (HORSE_CONTRACT) covers the stall, feed, daily care
   and training. Medical costs (MEDICAL) and race entry fees and other extras
   (OTHER) are billed on top of it in EXPENSE_LOG. Until now they were only
   recorded; the owner could not pay them in the system.

   COST_PAYMENT       one payOS payment of an owner, covering several cost lines
     PENDING          link created, waiting for the transfer
     PAID             money received (webhook, or the owner came back and the
                      server asked payOS)
     CANCELLED        replaced by a newer payment, or the link expired unpaid
   COST_PAYMENT_ITEM  the cost lines and amounts a payment was created for,
                      frozen at that moment
   EXPENSE_LOG        paid_at / paid_payment_id mark a cost line as paid

   An owner has at most one PENDING payment (UX_CPAY_open), so a cost line can
   never be offered in two open payments at once. No existing row is changed.
   Every statement checks first, so the file can run twice.
   ============================================================================= */

IF OBJECT_ID('COST_PAYMENT', 'U') IS NULL
BEGIN
    CREATE TABLE COST_PAYMENT (
        payment_id       INT IDENTITY(1,1) PRIMARY KEY,
        owner_id         INT            NOT NULL,
        total_amount     DECIMAL(14,0)  NOT NULL,     -- VND, what payOS charges
        status           VARCHAR(10)    NOT NULL CONSTRAINT DF_CPAY_status DEFAULT 'PENDING',
        order_code       BIGINT         NULL,         -- payOS orderCode
        payment_link_id  VARCHAR(64)    NULL,
        checkout_url     VARCHAR(500)   NULL,
        created_at       DATETIME2      NOT NULL CONSTRAINT DF_CPAY_created DEFAULT SYSUTCDATETIME(),
        paid_at          DATETIME2      NULL,
        paid_amount      DECIMAL(14,0)  NULL,
        payment_ref      VARCHAR(100)   NULL,         -- bank reference from payOS
        CONSTRAINT FK_CPAY_owner  FOREIGN KEY (owner_id) REFERENCES APP_USER(user_id),
        CONSTRAINT CK_CPAY_status CHECK (status IN ('PENDING', 'PAID', 'CANCELLED')),
        CONSTRAINT CK_CPAY_total  CHECK (total_amount > 0),
        CONSTRAINT CK_CPAY_paid   CHECK (status <> 'PAID' OR (paid_at IS NOT NULL AND paid_amount IS NOT NULL))
    );
END
GO

IF NOT EXISTS (SELECT 1 FROM sys.indexes WHERE name = 'UX_CPAY_open')
    CREATE UNIQUE INDEX UX_CPAY_open ON COST_PAYMENT(owner_id) WHERE status = 'PENDING';
GO
IF NOT EXISTS (SELECT 1 FROM sys.indexes WHERE name = 'UX_CPAY_order')
    CREATE UNIQUE INDEX UX_CPAY_order ON COST_PAYMENT(order_code) WHERE order_code IS NOT NULL;
GO

IF OBJECT_ID('COST_PAYMENT_ITEM', 'U') IS NULL
BEGIN
    CREATE TABLE COST_PAYMENT_ITEM (
        payment_id  INT           NOT NULL,
        expense_id  INT           NOT NULL,
        amount      DECIMAL(12,2) NOT NULL,
        CONSTRAINT PK_CPAY_item     PRIMARY KEY (payment_id, expense_id),
        CONSTRAINT FK_CPAY_item_pay FOREIGN KEY (payment_id) REFERENCES COST_PAYMENT(payment_id),
        CONSTRAINT FK_CPAY_item_exp FOREIGN KEY (expense_id) REFERENCES EXPENSE_LOG(expense_id)
    );
END
GO

IF COL_LENGTH('EXPENSE_LOG', 'paid_at') IS NULL
    ALTER TABLE EXPENSE_LOG ADD paid_at DATETIME2 NULL;
GO
IF COL_LENGTH('EXPENSE_LOG', 'paid_payment_id') IS NULL
    ALTER TABLE EXPENSE_LOG ADD paid_payment_id INT NULL;
GO
IF OBJECT_ID('FK_EXP_paid_payment', 'F') IS NULL
    ALTER TABLE EXPENSE_LOG ADD CONSTRAINT FK_EXP_paid_payment
        FOREIGN KEY (paid_payment_id) REFERENCES COST_PAYMENT(payment_id);
GO

IF OBJECT_ID('COST_PAYMENT_ITEM', 'U') IS NULL OR COL_LENGTH('EXPENSE_LOG', 'paid_payment_id') IS NULL
    THROW 50570, N'V57: cost payment tables were not created.', 1;
GO

PRINT 'V57 done.';
GO
