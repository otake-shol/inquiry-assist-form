-- 終わらせた人（docs/api-contract.md「InquiryView」の closedBy）。ASKER、OPERATOR、TIME のいずれか。終わっていない件は NULL。
-- 既存の終わっている行は、履歴の最後の要素の actor から埋める。履歴が空の終わった行は、終わり方から決める
-- （DECLINED は担当者、ANSWERED は時間、それ以外は本人）。

ALTER TABLE inquiries
    ADD COLUMN closed_by TEXT;

UPDATE inquiries
SET closed_by = CASE
    WHEN jsonb_array_length(history) > 0 AND history -> -1 ->> 'actor' IN ('ASKER', 'OPERATOR', 'TIME')
        THEN history -> -1 ->> 'actor'
    WHEN ending = 'DECLINED' THEN 'OPERATOR'
    WHEN ending = 'ANSWERED' THEN 'TIME'
    ELSE 'ASKER'
END
WHERE phase = 'CLOSED';

ALTER TABLE inquiries
    ADD CONSTRAINT inquiries_closed_by_check CHECK (closed_by IS NULL OR closed_by IN ('ASKER', 'OPERATOR', 'TIME'));

-- 返事がないときの決まりの対象（結果の確かめ、聞き返しへの返事）を、定期的な判定が引くため。V11 の索引は結果の確かめだけだった
DROP INDEX inquiries_result_confirmation_idx;
CREATE INDEX inquiries_no_reply_idx ON inquiries (awaiting_since) WHERE awaiting IN ('RESULT_CONFIRMATION', 'ASKER_REPLY');
