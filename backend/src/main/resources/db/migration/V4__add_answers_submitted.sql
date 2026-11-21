-- 本人が追加質問への回答を送信したか。回答の null だけでは「わからない」と「答える前に送った」を区別できない。
-- 追加質問へ戻る（5b）と false に戻す。

ALTER TABLE inquiries
    ADD COLUMN answers_submitted BOOLEAN NOT NULL DEFAULT false;

-- 既存の記録: 整形済み（回答を送信した）か、障害の案内で終わった、または回答が入っているものは送信済みとみなす
UPDATE inquiries
SET answers_submitted = true
WHERE structured_by_ai IS NOT NULL
   OR outcome = 'INCIDENT_REDIRECTED'
   OR jsonb_path_exists(follow_ups, '$[*] ? (@.answer != null)');
