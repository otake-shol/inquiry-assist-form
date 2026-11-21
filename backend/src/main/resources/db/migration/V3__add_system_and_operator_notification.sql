-- 対象システムとオペレータへの通知に関する列を足す。V1・V2 は適用済みのため書き換えない。

ALTER TABLE inquiries
    -- 対象システム（本人が選ぶか、AI が推測した値）。label は設定が変わっても記録が読めるよう一緒に残す
    ADD COLUMN system_id      TEXT,
    ADD COLUMN system_label   TEXT,
    ADD COLUMN system_guessed BOOLEAN NOT NULL DEFAULT false,
    -- オペレータが既読にした時刻。起票済みで null のものが未読
    ADD COLUMN read_at        TIMESTAMPTZ,
    -- Slack に投稿できたか。未設定で投稿しなかった場合も false（slack_error は null）
    ADD COLUMN slack_notified BOOLEAN NOT NULL DEFAULT false,
    -- Slack への投稿に失敗した理由。URL は含めない
    ADD COLUMN slack_error    TEXT;

CREATE INDEX inquiries_filed_idx ON inquiries (filed_at DESC) WHERE outcome = 'FILED';
