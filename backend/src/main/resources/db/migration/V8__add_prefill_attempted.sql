-- 設定の質問の答えを、本人の文章から読み取る処理（聞き手の2つ目の仕事）の状態。
-- 読み取りを済ませた（または、読み取らずに本人に聞くと決めた）設定の質問の識別子: ["since", …]。
-- 戻って答え直す質問を、同じ文章からもう一度読み取って消さないために、質問ごとに1回だけ読み取る。

ALTER TABLE inquiries
    ADD COLUMN prefill_attempted JSONB NOT NULL DEFAULT '[]';
