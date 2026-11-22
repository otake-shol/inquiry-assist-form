-- 説明が出す、本人が自分で行える手順（docs/api-contract.md「InquiryView」の answer.steps）。
-- [{ "action": "…", "check": "…" または null }] を、行う順に持つ。既存の行は、手順なし（空の一覧）。

ALTER TABLE inquiries
    ADD COLUMN answer_steps JSONB NOT NULL DEFAULT '[]';
