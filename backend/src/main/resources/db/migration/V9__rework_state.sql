-- 状態を「区間 × 誰の番か」に作り替える（docs/api-contract.md「状態」）。
-- V1〜V8 は適用済みのため書き換えず、この版で読み替える。
--
-- 変わること:
--   * 段階（step）、結果（outcome）、対応状況（handling_status）を、区間（phase）、誰の番か（turn）、
--     待っていること（awaiting）、終わり方（ending）に置き換える
--   * 「担当者に送る」は問い合わせを終わらせず、番を担当者にするだけにする。初めて渡した時刻を handed_over_at に持つ
--     （以前の filed_at）
--   * メッセージに発言の種類（kind）を足す
--   * 状態の履歴、見送りの内容、担当者が置き直した置き場所を足す

ALTER TABLE inquiries RENAME COLUMN filed_at TO handed_over_at;
DROP INDEX IF EXISTS inquiries_filed_idx;
DROP INDEX IF EXISTS inquiries_outcome_idx;

ALTER TABLE inquiries
    -- 区間: INTAKE / KNOW / DECIDE / ACT / CLOSED
    ADD COLUMN phase               TEXT,
    -- 誰の番か: ASKER / AI / OPERATOR。終わった件は NULL
    ADD COLUMN turn                TEXT,
    -- 待っていること。終わった件は NULL
    ADD COLUMN awaiting            TEXT,
    -- 終わり方: RESOLVED / ANSWERED / DECLINED / WITHDRAWN。終わっていない件は NULL
    ADD COLUMN ending              TEXT,
    -- 終わる前の区間。終わった件を開き直すとき、この区間に戻す
    ADD COLUMN phase_before_closed TEXT,
    -- 見送りの内容: { "reasonKind", "reason" }
    ADD COLUMN declined            JSONB,
    -- 状態の履歴。状態が変わるたびに1件: [{ "at", "actor", "event", "phase", "turn", "awaiting", "ending", "detail" }]
    ADD COLUMN history             JSONB NOT NULL DEFAULT '[]',
    -- 担当者が置き直した置き場所: { "type", "systemId", "functionId" }。ある場合は、入口の答えから導く置き場所の代わりに使う
    ADD COLUMN placement_override  JSONB;

-- 担当者に渡したときの区間。知識の不足のまま答える種類（使い方、仕組み、種類なし）は KNOW、それ以外は DECIDE
CREATE FUNCTION v9_handover_phase(intake JSONB) RETURNS TEXT AS $$
    SELECT CASE WHEN x.t IS NULL OR x.t IN ('HOW_TO', 'HOW_IT_WORKS') THEN 'KNOW' ELSE 'DECIDE' END
    FROM (
        SELECT (
            SELECT e -> 'answer' ->> 'optionId'
            FROM jsonb_array_elements(intake) e
            WHERE e ->> 'questionId' = 'type' AND e -> 'answer' ->> 'kind' IN ('OPTION', 'INFERRED')
            LIMIT 1) AS t
    ) x
$$ LANGUAGE SQL IMMUTABLE;

UPDATE inquiries
SET phase = CASE
        WHEN step IN ('INTAKE', 'STRUCTURED') THEN 'INTAKE'
        WHEN step IN ('ANSWERING', 'ANSWERED') THEN 'KNOW'
        -- 担当者に送られ、完了していない件は、担当者の番。区間は種類で決める
        WHEN outcome = 'FILED' AND handling_status IS DISTINCT FROM 'DONE' THEN v9_handover_phase(intake)
        ELSE 'CLOSED'
    END,
    turn = CASE
        WHEN step IN ('INTAKE', 'STRUCTURED', 'ANSWERED') THEN 'ASKER'
        WHEN step = 'ANSWERING' THEN 'AI'
        WHEN outcome = 'FILED' AND handling_status IS DISTINCT FROM 'DONE' THEN 'OPERATOR'
    END,
    awaiting = CASE
        WHEN step = 'INTAKE' THEN 'INTAKE_ANSWER'
        WHEN step = 'STRUCTURED' THEN 'QUESTION_CONFIRMATION'
        WHEN step = 'ANSWERING' THEN 'INVESTIGATION'
        WHEN step = 'ANSWERED' THEN 'RESULT_CONFIRMATION'
        WHEN outcome = 'FILED' AND handling_status IS DISTINCT FROM 'DONE' THEN 'HANDLING'
    END,
    -- 結果は伝えたが本人が確かめていない件（完了にした件）は「回答済み」として読み替える
    ending = CASE
        WHEN step <> 'CLOSED' THEN NULL
        WHEN outcome = 'SELF_RESOLVED' THEN 'RESOLVED'
        WHEN outcome = 'FILED' AND handling_status = 'DONE' THEN 'ANSWERED'
        WHEN outcome = 'FILED' THEN NULL
        ELSE 'WITHDRAWN'
    END,
    phase_before_closed = CASE
        WHEN outcome = 'FILED' AND handling_status = 'DONE' THEN v9_handover_phase(intake)
    END,
    -- 発言の種類。担当者の発言は結果、本人の追記は追記として読み替える
    messages = COALESCE((
        SELECT jsonb_agg(
            m || jsonb_build_object('kind', CASE WHEN m ->> 'from' = 'OPERATOR' THEN 'RESULT' ELSE 'NOTE' END)
            ORDER BY ord)
        FROM jsonb_array_elements(messages) WITH ORDINALITY AS t(m, ord)), '[]'::jsonb);

DROP FUNCTION v9_handover_phase(JSONB);

ALTER TABLE inquiries
    ALTER COLUMN phase SET NOT NULL,
    DROP COLUMN step,
    DROP COLUMN outcome,
    DROP COLUMN handling_status;

ALTER TABLE inquiries
    ADD CONSTRAINT inquiries_phase_check CHECK (phase IN ('INTAKE', 'KNOW', 'DECIDE', 'ACT', 'CLOSED')),
    ADD CONSTRAINT inquiries_turn_check CHECK (turn IS NULL OR turn IN ('ASKER', 'AI', 'OPERATOR')),
    ADD CONSTRAINT inquiries_awaiting_check CHECK (awaiting IS NULL OR awaiting IN (
        'INTAKE_ANSWER', 'QUESTION_CONFIRMATION', 'INVESTIGATION', 'RESULT_CONFIRMATION', 'HANDLING', 'ASKER_REPLY')),
    ADD CONSTRAINT inquiries_ending_check CHECK (ending IS NULL OR ending IN ('RESOLVED', 'ANSWERED', 'DECLINED', 'WITHDRAWN'));

CREATE INDEX inquiries_handed_over_idx ON inquiries (handed_over_at DESC) WHERE handed_over_at IS NOT NULL;
