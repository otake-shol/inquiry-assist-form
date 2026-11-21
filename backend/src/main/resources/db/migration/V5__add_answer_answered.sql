-- 確かめられた事実が本人の問いに答えているか（説明のスキルの判定）。根拠なし・未生成は null（false として扱う）。

ALTER TABLE inquiries
    ADD COLUMN answer_answered BOOLEAN;
