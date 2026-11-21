-- 言い直しの作り直しに関する列を足す。
-- V1 は適用済みのため書き換えず、この版で追加する。

ALTER TABLE inquiries
    -- 言い直しを作り直せる残り回数。追加質問へ戻っても戻さない
    ADD COLUMN revisions_left INT NOT NULL DEFAULT 2 CHECK (revisions_left >= 0),
    -- 本人が「少し違います」で伝えた一言: ["消えたのは一昨日から", ...]
    ADD COLUMN revision_notes JSONB NOT NULL DEFAULT '[]';
