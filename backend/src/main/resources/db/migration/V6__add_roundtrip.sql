-- 送った後のやり取り（往復）。
-- handling_status: 担当者側の対応状況。担当者に送られた問い合わせ（FILED）だけが持つ。
-- messages: 担当者の返信と本人の追記を1本にした時系列（古い順）。
-- asker_unread_reply: 本人がまだ読んでいない担当者の返信があるか。

ALTER TABLE inquiries
    ADD COLUMN handling_status VARCHAR(20),
    ADD COLUMN messages JSONB NOT NULL DEFAULT '[]'::jsonb,
    ADD COLUMN asker_unread_reply BOOLEAN NOT NULL DEFAULT false;

-- 既存の記録: すでに担当者に送られたものは未対応（OPEN）から始める。それ以外は持たない（null）
UPDATE inquiries
SET handling_status = 'OPEN'
WHERE outcome = 'FILED';
