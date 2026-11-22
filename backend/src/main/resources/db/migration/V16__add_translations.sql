-- 担当者のための訳（docs/api-contract.md「訳」）。
-- { "ja": { "<原文の SHA-256（16進数）>": "訳", … }, "en": { … } }。訳す先の言語ごとに、原文から訳への対応を持つ。
-- 同じ文章を2度訳さないために保存する。既存の行は、訳なし（空のオブジェクト）。

ALTER TABLE inquiries
    ADD COLUMN translations JSONB NOT NULL DEFAULT '{}';
