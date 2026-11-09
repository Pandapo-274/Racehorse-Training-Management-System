/* =============================================================================
   V39 - Account security for Flow 1 (no new table).
   (V37 and V38 are taken by feature/new-training-flow, hence the gap on main.)

   1. failed_login_count, locked_until
      Five wrong passwords in a row lock the sign-in for 15 minutes
      (AuthService.login). Before this, a password could be guessed without limit.
      A correct sign-in, a password reset, a temporary password set by the Club
      Manager, or re-activating the account clears the lock.

   2. tokens_valid_after
      "Every token of this user issued before this moment is dead": written when
      the password is changed or reset, the role changes or the account is
      suspended. Until now that cut-off lived only in memory (TokenBlacklist),
      so after a backend restart a token issued BEFORE a password change worked
      again until it expired. AuthInterceptor already reads APP_USER on every
      request, so this costs no extra query.

   Single-device logout stays in memory on purpose: persisting it would need a
   table of revoked token ids, and the ERD is held to its current entities.
   ============================================================================= */

IF COL_LENGTH('APP_USER', 'failed_login_count') IS NULL
    ALTER TABLE APP_USER ADD failed_login_count INT NOT NULL
        CONSTRAINT DF_USER_failed DEFAULT 0;
GO

IF COL_LENGTH('APP_USER', 'locked_until') IS NULL
    ALTER TABLE APP_USER ADD locked_until DATETIME2 NULL;
GO

IF COL_LENGTH('APP_USER', 'tokens_valid_after') IS NULL
    ALTER TABLE APP_USER ADD tokens_valid_after DATETIME2 NULL;
GO

IF OBJECT_ID('CK_USER_failed', 'C') IS NULL
    ALTER TABLE APP_USER ADD CONSTRAINT CK_USER_failed CHECK (failed_login_count >= 0);
GO

IF COL_LENGTH('APP_USER', 'failed_login_count') IS NULL
   OR COL_LENGTH('APP_USER', 'locked_until') IS NULL
   OR COL_LENGTH('APP_USER', 'tokens_valid_after') IS NULL
    THROW 50390, N'V39: APP_USER security columns were not created.', 1;

PRINT 'V39 done.';
GO
