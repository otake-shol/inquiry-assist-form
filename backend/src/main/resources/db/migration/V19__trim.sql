-- 骨組みに要らない機能を削ったことに合わせて、使わなくなった列を落とす（docs/api-contract.md）。
-- precedents.recorded_by と confirmed: 担当者が代わりに記録する道と、持ち主が確かめる・直す道を削ったため。
-- 先例ができるのは、持ち主の決定だけ。既存の行（先例と問い合わせ）は残る。
-- inquiries.translations: 訳は保存しない（頼まれるたびに、その時点の文章を訳す）。
-- 適用済みの版（V16〜V18）は書き換えない。

ALTER TABLE precedents
    DROP COLUMN recorded_by,
    DROP COLUMN confirmed;

ALTER TABLE inquiries
    DROP COLUMN translations;
