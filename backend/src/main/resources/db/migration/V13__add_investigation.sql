-- 調査の状況と、渡した理由（docs/api-contract.md「調査の状況と、裏での調査」「担当者に渡す」）。
-- investigation_status: NOT_RUN / RUNNING / DONE / FAILED。
-- investigation_audience: 保存している調査の結果を誰に見せるか（ASKER / OPERATOR）。結果がなければ NULL。
-- handover_reason: 直近に担当者へ渡した理由。渡したことがなければ NULL。

ALTER TABLE inquiries
    ADD COLUMN investigation_status               TEXT    NOT NULL DEFAULT 'NOT_RUN',
    ADD COLUMN investigation_audience             TEXT,
    ADD COLUMN investigation_started_at           TIMESTAMPTZ,
    ADD COLUMN investigation_finished_at          TIMESTAMPTZ,
    ADD COLUMN investigation_unconfirmed_question BOOLEAN NOT NULL DEFAULT FALSE,
    ADD COLUMN handover_reason                    TEXT;

-- 調査の結果が保存されている行（技術メモがある、または根拠の有無が入っている）は、本人が待っている間に出た答え
UPDATE inquiries
SET investigation_status   = 'DONE',
    investigation_audience = 'ASKER'
WHERE technical_memo IS NOT NULL
   OR answer_grounded IS NOT NULL;

-- 担当者に渡したことのある行の、渡した理由。今すぐ送った件は緊急度で BLOCKED と SENT_NOW を分け、
-- それ以外は種類の経路（LOOKS_WRONG、DO_FOR_ME、CHANGE_REQUEST）か、ほかの理由（UNRESOLVED）。
-- 種類は、入口の答え（intake）の type の質問から読む（V9 と同じ読み方）。担当者が置き直した種類（placement_override）があれば、それを使う
UPDATE inquiries
SET handover_reason = CASE
    WHEN sent_immediately AND urgency = 'BLOCKED' THEN 'BLOCKED'
    WHEN sent_immediately THEN 'SENT_NOW'
    WHEN COALESCE(
            placement_override ->> 'type',
            (SELECT e -> 'answer' ->> 'optionId' FROM jsonb_array_elements(intake) AS e
             WHERE e ->> 'questionId' = 'type' AND e -> 'answer' ->> 'kind' IN ('OPTION', 'INFERRED') LIMIT 1)
         ) IN ('LOOKS_WRONG', 'DO_FOR_ME', 'CHANGE_REQUEST') THEN 'ROUTE'
    ELSE 'UNRESOLVED'
END
WHERE handed_over_at IS NOT NULL;

ALTER TABLE inquiries
    ADD CONSTRAINT inquiries_investigation_status_check
        CHECK (investigation_status IN ('NOT_RUN', 'RUNNING', 'DONE', 'FAILED')),
    ADD CONSTRAINT inquiries_investigation_audience_check
        CHECK (investigation_audience IS NULL OR investigation_audience IN ('ASKER', 'OPERATOR')),
    ADD CONSTRAINT inquiries_handover_reason_check
        CHECK (handover_reason IS NULL OR handover_reason IN
            ('SENT_NOW', 'UNRESOLVED', 'BLOCKED', 'ROUTE', 'NO_ANSWER', 'TIMEOUT', 'REOPENED'));
