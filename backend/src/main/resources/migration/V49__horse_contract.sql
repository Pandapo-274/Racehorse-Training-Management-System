/* =============================================================================
   V49 - Boarding contract: how long a horse stays, and what the owner paid.

   Review feedback (Flow 1): the system had no idea how long a horse was meant to
   stay. The stay is now a contract. Approving a registration no longer creates
   the horse; it sends the owner a contract. The owner signs it and pays it
   online (payOS). Only then does the manager receive the horse: registration
   code, stall, Head Trainer, body condition score - and the HORSE row is born.

   Why the horse is not created at approval: an approved-but-unpaid horse would
   sit in HORSE (every query would have to filter it out, the exact problem V5
   avoided), and its stall would be held forever if the owner never paid.
   Assigning the stall on arrival keeps UX_HORSE_stall doing its job unchanged.

   Status of a contract:
     SENT       approved by the club, waiting for the owner's signature
     SIGNED     signed, waiting for payment
     PAID       paid, waiting for the horse to arrive
     ACTIVE     the horse is at the club, start_date .. end_date
     EXPIRED    end_date passed and the stay was not renewed; the horse is
                still in its stall but takes no new plan until renewed or withdrawn
     ENDED      closed: the horse left, or a renewal took over
     VOID       not signed and paid before respond_by, or cancelled

   terms_snapshot is the contract text frozen when it was sent; terms_sha256 is
   its hash, stored again at signing, so a later edit of the text cannot pass
   for what the owner signed.
   ============================================================================= */

IF OBJECT_ID('HORSE_CONTRACT', 'U') IS NULL
BEGIN
    CREATE TABLE HORSE_CONTRACT (
        contract_id         INT IDENTITY(1,1) PRIMARY KEY,
        request_id          INT            NOT NULL,
        horse_id            INT            NULL,      -- set when the horse is received
        owner_id            INT            NOT NULL,
        renews_contract_id  INT            NULL,      -- a renewal points at the contract it extends

        months              INT            NOT NULL,
        training_weeks      INT            NULL,
        monthly_fee         DECIMAL(14,0)  NOT NULL,  -- VND
        discount_pct        DECIMAL(5,2)   NOT NULL CONSTRAINT DF_HCON_discount DEFAULT 0,
        total_amount        DECIMAL(14,0)  NOT NULL,  -- VND, what payOS charges
        planned_start       DATE           NULL,
        start_date          DATE           NULL,
        end_date            DATE           NULL,

        terms_snapshot      NVARCHAR(MAX)  NOT NULL,
        terms_sha256        CHAR(64)       NOT NULL,

        status              VARCHAR(10)    NOT NULL CONSTRAINT DF_HCON_status DEFAULT 'SENT',
        sent_by             INT            NULL,
        sent_at             DATETIME2      NOT NULL CONSTRAINT DF_HCON_sent DEFAULT SYSUTCDATETIME(),
        respond_by          DATE           NOT NULL,

        signed_name         NVARCHAR(100)  NULL,
        signed_at           DATETIME2      NULL,
        signed_ip           VARCHAR(45)    NULL,
        signed_sha256       CHAR(64)       NULL,

        order_code          BIGINT         NULL,      -- payOS orderCode of the current payment link
        payment_link_id     VARCHAR(64)    NULL,
        checkout_url        VARCHAR(500)   NULL,
        paid_at             DATETIME2      NULL,
        paid_amount         DECIMAL(14,0)  NULL,
        payment_ref         VARCHAR(100)   NULL,      -- bank reference from payOS

        received_by         INT            NULL,
        reminded_days       INT            NULL,      -- smallest "days left" reminder already sent
        closed_note         NVARCHAR(255)  NULL,
        updated_at          DATETIME2      NULL,

        CONSTRAINT FK_HCON_request  FOREIGN KEY (request_id) REFERENCES HORSE_REQUEST(request_id),
        CONSTRAINT FK_HCON_horse    FOREIGN KEY (horse_id)   REFERENCES HORSE(horse_id),
        CONSTRAINT FK_HCON_owner    FOREIGN KEY (owner_id)   REFERENCES APP_USER(user_id),
        CONSTRAINT FK_HCON_sender   FOREIGN KEY (sent_by)    REFERENCES APP_USER(user_id),
        CONSTRAINT FK_HCON_receiver FOREIGN KEY (received_by) REFERENCES APP_USER(user_id),
        CONSTRAINT FK_HCON_renews   FOREIGN KEY (renews_contract_id) REFERENCES HORSE_CONTRACT(contract_id),

        CONSTRAINT CK_HCON_status CHECK (status IN
            ('SENT', 'SIGNED', 'PAID', 'ACTIVE', 'EXPIRED', 'ENDED', 'VOID')),
        CONSTRAINT CK_HCON_money CHECK (months BETWEEN 1 AND 24 AND monthly_fee >= 0
            AND total_amount >= 0 AND discount_pct BETWEEN 0 AND 100),
        CONSTRAINT CK_HCON_dates CHECK (end_date IS NULL OR start_date IS NULL OR end_date > start_date),
        /* A horse at the club has its dates; a contract cannot be ACTIVE without them. */
        CONSTRAINT CK_HCON_active CHECK (status NOT IN ('ACTIVE', 'EXPIRED')
            OR (horse_id IS NOT NULL AND start_date IS NOT NULL AND end_date IS NOT NULL)),
        CONSTRAINT CK_HCON_signed CHECK (status IN ('SENT', 'VOID')
            OR (signed_at IS NOT NULL AND signed_sha256 IS NOT NULL))
    );
END
GO

/* One open contract per registration at a time (a renewal reuses the request). */
IF NOT EXISTS (SELECT 1 FROM sys.indexes WHERE name = 'UX_HCON_open')
    CREATE UNIQUE INDEX UX_HCON_open ON HORSE_CONTRACT(request_id)
        WHERE status IN ('SENT', 'SIGNED', 'PAID');
GO
/* One running stay per horse. A renewal closes the old one (ENDED) in the same
   transaction that starts the new one, so the two never overlap. */
IF NOT EXISTS (SELECT 1 FROM sys.indexes WHERE name = 'UX_HCON_running')
    CREATE UNIQUE INDEX UX_HCON_running ON HORSE_CONTRACT(horse_id)
        WHERE status IN ('ACTIVE', 'EXPIRED');
GO
/* payOS identifies a payment by orderCode; two contracts may never share one. */
IF NOT EXISTS (SELECT 1 FROM sys.indexes WHERE name = 'UX_HCON_order')
    CREATE UNIQUE INDEX UX_HCON_order ON HORSE_CONTRACT(order_code)
        WHERE order_code IS NOT NULL;
GO
IF NOT EXISTS (SELECT 1 FROM sys.indexes WHERE name = 'IX_HCON_owner')
    CREATE INDEX IX_HCON_owner ON HORSE_CONTRACT(owner_id, status);
GO

IF OBJECT_ID('HORSE_CONTRACT', 'U') IS NULL
    THROW 50490, N'V49: HORSE_CONTRACT was not created.', 1;
GO

PRINT 'V49 done.';
GO
