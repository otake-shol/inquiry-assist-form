-- 入口を、選択肢を押していく一問一答に作り替える（docs/api-contract.md「入口の質問」）。
-- V1〜V6 は適用済みのため書き換えず、この版で読み替える。
--
-- 変わること:
--   * 種別（4つ）を種類（5つ）に読み替える。障害は種類ではなく緊急度 BLOCKED にする
--   * 置き場所（種類、システム、機能）は列に持たず、答え終えた入口の質問（intake）から導く。
--     「置いた人」（本人が選んだ ASKER / AI が置いた AI）は、答えの種類（OPTION / INFERRED）で表す
--   * 段階 TRIAGED を INTAKE に、結果 INCIDENT_REDIRECTED を ABANDONED に読み替える
--   * 本人が書いた文章は texts に持つ（補足は入口の答えに入る）

ALTER TABLE inquiries DROP CONSTRAINT IF EXISTS inquiries_step_check;
ALTER TABLE inquiries DROP CONSTRAINT IF EXISTS inquiries_outcome_check;

ALTER TABLE inquiries
    -- 緊急度。本人が申告する（NORMAL / HURRY / BLOCKED）
    ADD COLUMN urgency          TEXT NOT NULL DEFAULT 'NORMAL',
    -- 本人が文章で書いたもの（最初の文章、切り替えたときの文章）。書いた順: ["…", …]
    ADD COLUMN texts            JSONB NOT NULL DEFAULT '[]',
    -- 答え終えた入口の質問（聞いた順）:
    -- [{ "questionId", "kind", "text", "answer": { "kind", "optionId", "label", "text" } }]
    ADD COLUMN intake           JSONB NOT NULL DEFAULT '[]',
    -- 聞き手が作った詳しい質問。聞き手をまだ呼んでいなければ NULL、呼んで質問がなければ []
    ADD COLUMN detail_questions JSONB;

-- 旧い種別から新しい種類への読み替え。SPEC_QUESTION は仕組み、SUSPECTED_BUG は「おかしいかもしれない」、
-- WORK_REQUEST は「やってほしい」。INCIDENT は「おかしいかもしれない」に置き、緊急度 BLOCKED で表す
CREATE FUNCTION v7_type(category TEXT) RETURNS TEXT AS $$
    SELECT CASE category
        WHEN 'SPEC_QUESTION' THEN 'HOW_IT_WORKS'
        WHEN 'SUSPECTED_BUG' THEN 'LOOKS_WRONG'
        WHEN 'WORK_REQUEST' THEN 'DO_FOR_ME'
        WHEN 'INCIDENT' THEN 'LOOKS_WRONG'
    END
$$ LANGUAGE SQL IMMUTABLE;

CREATE FUNCTION v7_type_label(category TEXT) RETURNS TEXT AS $$
    SELECT CASE v7_type(category)
        WHEN 'HOW_IT_WORKS' THEN '仕組みを知りたい'
        WHEN 'LOOKS_WRONG' THEN 'おかしいかもしれない'
        WHEN 'DO_FOR_ME' THEN 'やってほしい'
    END
$$ LANGUAGE SQL IMMUTABLE;

UPDATE inquiries i
SET urgency = CASE WHEN i.category_confirmed = 'INCIDENT' THEN 'BLOCKED' ELSE 'NORMAL' END,
    texts = jsonb_build_array(i.utterance),
    intake =
        -- 種類。本人が種別を変更していれば本人が選んだもの（OPTION）、そうでなければ AI が置いたもの（INFERRED）
        (CASE WHEN i.category_confirmed IS NULL THEN '[]'::jsonb ELSE jsonb_build_array(jsonb_build_object(
            'questionId', 'type',
            'kind', 'TYPE',
            'text', '何をしたいですか',
            'answer', jsonb_build_object(
                'kind', CASE WHEN i.category_by_ai IS DISTINCT FROM i.category_confirmed THEN 'OPTION' ELSE 'INFERRED' END,
                'optionId', v7_type(i.category_confirmed),
                'label', v7_type_label(i.category_confirmed)))) END)
        -- システム。推測したものは AI が置いたもの。決まっていない場合、入口が進んでいれば「わからない」として読む
        || (CASE
                WHEN i.system_id IS NOT NULL THEN jsonb_build_array(jsonb_build_object(
                    'questionId', 'system',
                    'kind', 'SYSTEM',
                    'text', 'どのシステムですか',
                    'answer', jsonb_build_object(
                        'kind', CASE WHEN i.system_guessed THEN 'INFERRED' ELSE 'OPTION' END,
                        'optionId', i.system_id,
                        'label', COALESCE(i.system_label, i.system_id))))
                WHEN i.category_confirmed IS NOT NULL AND i.step <> 'TRIAGED' THEN jsonb_build_array(jsonb_build_object(
                    'questionId', 'system',
                    'kind', 'SYSTEM',
                    'text', 'どのシステムですか',
                    'answer', jsonb_build_object('kind', 'UNKNOWN')))
                ELSE '[]'::jsonb
            END)
        -- 送信済みの追加質問と回答は、詳しい質問への答えとして読めるように移す
        || (CASE WHEN i.answers_submitted THEN (
                SELECT COALESCE(jsonb_agg(jsonb_build_object(
                    'questionId', t.q ->> 'id',
                    'kind', 'DETAIL',
                    'text', t.q ->> 'text',
                    'answer', CASE
                        WHEN t.q ->> 'answer' IS NULL OR btrim(t.q ->> 'answer') = ''
                            THEN jsonb_build_object('kind', 'UNKNOWN')
                        ELSE jsonb_build_object('kind', 'TEXT', 'text', t.q ->> 'answer')
                    END) ORDER BY t.ord), '[]'::jsonb)
                FROM jsonb_array_elements(i.follow_ups) WITH ORDINALITY AS t(q, ord))
            ELSE '[]'::jsonb END),
    -- 入口の途中だった記録は、聞き手を呼び直さずに続けられるようにする
    detail_questions = CASE WHEN i.step = 'TRIAGED' THEN '[]'::jsonb END,
    step = CASE WHEN i.step = 'TRIAGED' THEN 'INTAKE' ELSE i.step END,
    outcome = CASE WHEN i.outcome = 'INCIDENT_REDIRECTED' THEN 'ABANDONED' ELSE i.outcome END,
    -- 整形後の問いも、種別を種類に読み替え、緊急度（本人の申告ではなかった値）は持たない
    structured_by_ai = CASE WHEN i.structured_by_ai IS NULL THEN NULL
        ELSE (i.structured_by_ai - 'category' - 'urgency')
            || jsonb_build_object('type', v7_type(i.structured_by_ai ->> 'category')) END,
    structured_confirmed = CASE WHEN i.structured_confirmed IS NULL THEN NULL
        ELSE (i.structured_confirmed - 'category' - 'urgency')
            || jsonb_build_object('type', v7_type(i.structured_confirmed ->> 'category')) END;

DROP FUNCTION v7_type_label(TEXT);
DROP FUNCTION v7_type(TEXT);

ALTER TABLE inquiries
    DROP COLUMN category_by_ai,
    DROP COLUMN category_confirmed,
    DROP COLUMN follow_ups,
    DROP COLUMN ai_questions,
    DROP COLUMN answers_submitted,
    DROP COLUMN system_id,
    DROP COLUMN system_label,
    DROP COLUMN system_guessed,
    DROP COLUMN utterance;

ALTER TABLE inquiries
    ADD CONSTRAINT inquiries_step_check
        CHECK (step IN ('INTAKE', 'STRUCTURED', 'ANSWERING', 'ANSWERED', 'CLOSED')),
    ADD CONSTRAINT inquiries_outcome_check
        CHECK (outcome IN ('IN_PROGRESS', 'SELF_RESOLVED', 'FILED', 'ABANDONED')),
    ADD CONSTRAINT inquiries_urgency_check
        CHECK (urgency IN ('NORMAL', 'HURRY', 'BLOCKED'));
