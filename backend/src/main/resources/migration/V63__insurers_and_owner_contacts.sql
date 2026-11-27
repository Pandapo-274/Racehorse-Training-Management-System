/* =============================================================================
   V63 - Insurer and second contact are chosen, not typed (contract clause 8).

   V54 asked the owner to type the insurer, its emergency number, a second person
   and their phone on every registration, and V61 asked the manager to type the
   buyer's on every transfer. Typed numbers go wrong ("rdhdfh, 342534543"), and the
   club calls them when the horse is hurt.

     INSURER        : insurers the club accepts, with their 24/7 claims line.
                      The owner picks one; the number comes with it.
     OWNER_CONTACT  : each owner's own list of people who may decide for their
                      horses. Added once, then picked on every request, renewal
                      and transfer; the phone comes with the name.

   The request and the contract still keep the name and number as text: a contract
   freezes what was agreed, even if the list changes later. The service checks that
   what it is given is on the list.
   ============================================================================= */

IF OBJECT_ID('INSURER', 'U') IS NULL
BEGIN
    CREATE TABLE INSURER (
        insurer_id      INT IDENTITY(1,1) CONSTRAINT PK_INSURER PRIMARY KEY,
        name            NVARCHAR(100) NOT NULL CONSTRAINT UQ_INSURER_name UNIQUE,
        emergency_phone VARCHAR(20)   NOT NULL,
        sort_order      INT           NOT NULL CONSTRAINT DF_INSURER_sort DEFAULT 0,
        active          BIT           NOT NULL CONSTRAINT DF_INSURER_active DEFAULT 1
    );
END
GO

-- 24/7 hotlines as published by each insurer.
MERGE INSURER AS t
USING (VALUES
    (N'Bao Viet Insurance',        '1900 558899', 1),
    (N'PVI Insurance',             '1900 545458', 2),
    (N'BIC (BIDV Insurance)',      '1900 9456',   3),
    (N'VBI (VietinBank Insurance)', '1900 1566',  4),
    (N'Tokio Marine Vietnam',      '1900 888996', 5)
) AS s (name, emergency_phone, sort_order)
ON t.name = s.name
WHEN NOT MATCHED THEN
    INSERT (name, emergency_phone, sort_order) VALUES (s.name, s.emergency_phone, s.sort_order);
GO

IF OBJECT_ID('OWNER_CONTACT', 'U') IS NULL
BEGIN
    CREATE TABLE OWNER_CONTACT (
        contact_id   INT IDENTITY(1,1) CONSTRAINT PK_OWNER_CONTACT PRIMARY KEY,
        owner_id     INT           NOT NULL CONSTRAINT FK_OCON_owner REFERENCES APP_USER(user_id),
        full_name    NVARCHAR(100) NOT NULL,
        phone        VARCHAR(20)   NOT NULL,
        relationship NVARCHAR(50)  NULL,
        created_at   DATETIME2     NOT NULL CONSTRAINT DF_OCON_created DEFAULT SYSUTCDATETIME()
    );
    -- The same person once per owner.
    CREATE UNIQUE INDEX UX_OCON_person ON OWNER_CONTACT(owner_id, full_name, phone);
END
GO

-- People owners already typed on their requests become their first contacts.
INSERT INTO OWNER_CONTACT (owner_id, full_name, phone)
SELECT DISTINCT q.owner_id, LTRIM(RTRIM(q.emergency_name)), LTRIM(RTRIM(q.emergency_phone))
FROM HORSE_REQUEST q
WHERE q.emergency_name IS NOT NULL AND q.emergency_phone IS NOT NULL
  AND LTRIM(RTRIM(q.emergency_name)) <> '' AND LTRIM(RTRIM(q.emergency_phone)) <> ''
  AND NOT EXISTS (SELECT 1 FROM OWNER_CONTACT c
                  WHERE c.owner_id = q.owner_id
                    AND c.full_name = LTRIM(RTRIM(q.emergency_name))
                    AND c.phone = LTRIM(RTRIM(q.emergency_phone)));
GO
