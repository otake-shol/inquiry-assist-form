-- 返事がないときの決まり（docs/api-contract.md「返事がないとき」）の起点と、知らせた時刻。
-- awaiting_since: 待っていることが、いまのものになった時刻。既存の行は、履歴の最後の出来事の時刻から、履歴が空なら updated_at から埋める。
-- reminded_at: 返事がないことを本人に知らせた時刻。既存の行は、まだ知らせていない。

ALTER TABLE inquiries
    ADD COLUMN awaiting_since TIMESTAMPTZ,
    ADD COLUMN reminded_at    TIMESTAMPTZ;

UPDATE inquiries
SET awaiting_since = CASE
    WHEN jsonb_array_length(history) = 0 THEN updated_at
    WHEN jsonb_typeof(history -> -1 -> 'at') = 'number' THEN to_timestamp((history -> -1 ->> 'at')::numeric)
    WHEN jsonb_typeof(history -> -1 -> 'at') = 'string' THEN (history -> -1 ->> 'at')::timestamptz
    ELSE updated_at
END;

-- 定期的な判定が、結果を確かめている件だけを引くため
CREATE INDEX inquiries_result_confirmation_idx ON inquiries (awaiting_since) WHERE awaiting = 'RESULT_CONFIRMATION';
