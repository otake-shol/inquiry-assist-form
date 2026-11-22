-- 仕様の持ち主への相談と、先例（docs/api-contract.md「仕様の持ち主への相談」）。
-- turn に OWNER、awaiting に CONSULTATION、handover_reason に DECIDED を加える（CHECK 制約を作り直す）。
-- closed_by は変えない（持ち主は問い合わせを終わらせない）。

ALTER TABLE inquiries
    DROP CONSTRAINT inquiries_turn_check,
    DROP CONSTRAINT inquiries_awaiting_check,
    DROP CONSTRAINT inquiries_handover_reason_check;

ALTER TABLE inquiries
    ADD CONSTRAINT inquiries_turn_check CHECK (turn IS NULL OR turn IN ('ASKER', 'AI', 'OPERATOR', 'OWNER')),
    ADD CONSTRAINT inquiries_awaiting_check CHECK (awaiting IS NULL OR awaiting IN (
        'INTAKE_ANSWER', 'QUESTION_CONFIRMATION', 'INVESTIGATION', 'RESULT_CONFIRMATION', 'HANDLING', 'ASKER_REPLY',
        'CONSULTATION')),
    ADD CONSTRAINT inquiries_handover_reason_check CHECK (handover_reason IS NULL OR handover_reason IN
        ('SENT_NOW', 'UNRESOLVED', 'BLOCKED', 'ROUTE', 'NO_ANSWER', 'TIMEOUT', 'REOPENED', 'DECIDED'));

-- 相談（古い順）: [{ id, askedAt, askedBy, to, question, status, closedAt, precedentId }]
ALTER TABLE inquiries
    ADD COLUMN consultations JSONB NOT NULL DEFAULT '[]';

-- 先例。仕様の持ち主が下した決定の記録。問い合わせが終わっても残る。マスは、決めた時点の置き場所
CREATE TABLE precedents (
    id            UUID        PRIMARY KEY,
    inquiry_id    UUID        NOT NULL REFERENCES inquiries (id),
    type          TEXT,
    system_id     TEXT,
    function_id   TEXT,
    situation     TEXT        NOT NULL,
    decision_kind TEXT        NOT NULL,
    decision      TEXT        NOT NULL,
    reason        TEXT        NOT NULL,
    decided_by    TEXT        NOT NULL,
    recorded_by   TEXT        NOT NULL,
    confirmed     BOOLEAN     NOT NULL,
    language      TEXT        NOT NULL,
    decided_at    TIMESTAMPTZ NOT NULL,
    updated_at    TIMESTAMPTZ NOT NULL,
    CONSTRAINT precedents_decision_kind_check CHECK (decision_kind IN
        ('AS_INTENDED', 'DEFECT', 'ACCEPT', 'REJECT', 'ADOPT', 'DEFER', 'OTHER')),
    CONSTRAINT precedents_recorded_by_check CHECK (recorded_by IN ('OWNER'))
);

-- 先例をマスで引くため
CREATE INDEX precedents_cell_idx ON precedents (system_id, function_id, type);
CREATE INDEX precedents_inquiry_idx ON precedents (inquiry_id, decided_at);
