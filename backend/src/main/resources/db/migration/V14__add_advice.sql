-- 担当者向けの AI の案（助言）の状況と中身（docs/api-contract.md「AI の案（advice）」）。
-- advice_status: NOT_RUN / RUNNING / DONE / FAILED。既存の行は NOT_RUN のまま（作り直さない）。
-- advice: できたとき（DONE）の中身。{ judgment, procedure }。基づく事実は文で持つ。

ALTER TABLE inquiries
    ADD COLUMN advice_status TEXT NOT NULL DEFAULT 'NOT_RUN',
    ADD COLUMN advice        JSONB;

ALTER TABLE inquiries
    ADD CONSTRAINT inquiries_advice_status_check CHECK (advice_status IN ('NOT_RUN', 'RUNNING', 'DONE', 'FAILED'));
