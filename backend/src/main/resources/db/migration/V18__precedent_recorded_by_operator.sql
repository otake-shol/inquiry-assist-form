-- 担当者が、仕様の持ち主の代わりに決定を記録できるようにする（docs/api-contract.md「担当者が決定を記録する」）。
-- 先例の recorded_by に OPERATOR を加える（CHECK 制約を作り直す）。担当者が記録した先例は confirmed = false で始まる。
-- 案（inquiries.advice）の JSONB に basedOnPrecedents が増えるが、移行は要らない（持たない案は、空として読む）。

ALTER TABLE precedents
    DROP CONSTRAINT precedents_recorded_by_check;

ALTER TABLE precedents
    ADD CONSTRAINT precedents_recorded_by_check CHECK (recorded_by IN ('OWNER', 'OPERATOR'));
