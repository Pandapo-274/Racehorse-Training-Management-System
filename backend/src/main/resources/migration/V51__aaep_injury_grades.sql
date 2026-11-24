-- V51: Mo rong phan do chan thuong theo thang do khap khieng AAEP (Grade 1 den Grade 5)
-- Thay the constraint cu CK_INJ_severity von chi cho phep GRADE_1, GRADE_2, GRADE_3.

IF OBJECT_ID('CK_INJ_severity', 'C') IS NOT NULL
    ALTER TABLE INJURY DROP CONSTRAINT CK_INJ_severity;
GO

ALTER TABLE INJURY ADD CONSTRAINT CK_INJ_severity CHECK (
    severity IN ('GRADE_1', 'GRADE_2', 'GRADE_3', 'GRADE_4', 'GRADE_5')
);
GO
