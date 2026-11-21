-- 問い合わせ記録。自己解決・起票・障害の案内・離脱のいずれの場合も1件ずつ残す。
-- Flyway と PostgreSQL の接続はフェーズ3で追加する。それまでこのファイルは実行されない。

CREATE TABLE inquiries (
    id                    UUID PRIMARY KEY,
    created_at            TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at            TIMESTAMPTZ NOT NULL DEFAULT now(),

    -- 投稿者（SSOで特定した識別子）
    asker_id              TEXT NOT NULL,

    -- 元の発話。本人が最初に書いた文章をそのまま残す
    utterance             TEXT NOT NULL,

    -- 種別。AIの判定と、本人が確認・変更した後の値
    category_by_ai        TEXT CHECK (category_by_ai IN ('SPEC_QUESTION', 'SUSPECTED_BUG', 'WORK_REQUEST', 'INCIDENT')),
    category_confirmed    TEXT CHECK (category_confirmed IN ('SPEC_QUESTION', 'SUSPECTED_BUG', 'WORK_REQUEST', 'INCIDENT')),

    -- 追加質問と回答: [{ "id", "text", "options", "answer" }]
    follow_ups            JSONB NOT NULL DEFAULT '[]',

    -- 整形後の問い。AIの出力と、本人が修正した後の値
    structured_by_ai      JSONB,
    structured_confirmed  JSONB,

    -- 0次回答
    answer_grounded       BOOLEAN,
    asker_explanation     TEXT,
    technical_memo        JSONB,
    -- 出典: [{ "sourceType", "repository", "path", "startLine", "endLine", "quote", "revision", "verified" }]
    citations             JSONB NOT NULL DEFAULT '[]',

    -- 結果
    outcome               TEXT NOT NULL DEFAULT 'IN_PROGRESS'
        CHECK (outcome IN ('IN_PROGRESS', 'SELF_RESOLVED', 'FILED', 'INCIDENT_REDIRECTED', 'ABANDONED')),
    -- 「今すぐ担当者に送る」による起票か
    sent_immediately      BOOLEAN NOT NULL DEFAULT false,

    -- 実行条件と利用量: [{ "step", "model", "promptVersion", "inputTokens", "outputTokens", "cacheReadTokens" }]
    usages                JSONB NOT NULL DEFAULT '[]',
    -- モック実装による記録か
    mock                  BOOLEAN NOT NULL DEFAULT false,

    -- 担当者の評価。対応ツールの段階で入力する
    answer_correct        BOOLEAN,
    memo_useful           BOOLEAN
);

CREATE INDEX inquiries_created_at_idx ON inquiries (created_at DESC);
CREATE INDEX inquiries_outcome_idx ON inquiries (outcome);
CREATE INDEX inquiries_category_idx ON inquiries (category_confirmed);
