-- 本人の言語。AI が書くもの（質問、言い直し、事実、説明、進捗の文言）をこの言語で書く（docs/api-contract.md「言語」）。
-- 作成のときに決め、本人が行う状態を変えるリクエストのたびに合わせ直す。既存の記録は、既定の言語（ja）。

ALTER TABLE inquiries
    ADD COLUMN language TEXT NOT NULL DEFAULT 'ja';
