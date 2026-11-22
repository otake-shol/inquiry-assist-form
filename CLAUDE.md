# inquiry-assist-form

社内の問い合わせを、誰の番かを見失わずに終わりまで運ぶ、AI 補助つきの問い合わせ対応ツール。本人の状況を答えられる問いに直し、ソースコードと FAQ を根拠に答え、答えきれない件は材料と案を添えて担当者に渡す。新しい決定は仕様の持ち主が下し、先例として残る。

- 使い方と、カスタマイズの手引き（使う側が、何をどこで変えるか）: `README.md`
- 枠組み（問い合わせとは何か、課題、登場人物、状態、規則）: `docs/framework.md`
- 要件定義（要件ごとの実装状況つき）: `docs/requirements.md`
- 業務フローとスキル設計の図解: `site/index.html`（GitHub Pages で公開）

## 構成

| 場所 | 内容 |
| --- | --- |
| `backend/` | Spring Boot（Java 21、Gradle Kotlin DSL） |
| `backend/src/main/java/dev/inquiryassist/core/` | AI処理の型。画面・HTTP・Spring に依存させない |
| `backend/src/main/java/dev/inquiryassist/core/skill/` | 8つのスキルの境界、受け渡す型、調査から説明までの流れ（`AnswerPipeline`） |
| `backend/src/main/java/dev/inquiryassist/mock/` | スキルと一次情報の検索のモック実装 |
| `backend/src/main/resources/db/migration/` | 保存スキーマ（Flyway）。適用済みの版は書き換えず、新しい版を足す |
| `frontend/` | React + TypeScript + Vite（SPA）。`/`、`/inquiries`、`/inquiries/{id}` が本人向け、`/operator` が担当者向け、`/owner` が仕様の持ち主向け、`/precedents` が先例の一覧 |
| `site/` | 業務フローとスキル設計の図解ページ（GitHub Pages で公開） |

## 設計上の決まり

- 問い合わせとは何か、解く課題、登場人物、状態と終わり方、規則、測るものの定義は `docs/framework.md` が正。ほかの仕様より上位に置く。機能を足すか迷ったら、そこに書いた4つの課題（伝える、待つ、届け先を誤る、繰り返す）のどれに効くかで判断する。いまの実装はこの枠組みより手前にあり、差分と実装の順番は同じ文書の末尾にある。
- AIに任せる仕事は8つのスキルに分ける。本人向けの5つ（受付、聞き手、言い直し、調査、説明）と、担当者向けの3つ（助言、下書き、訳）。境界、受け渡す型、呼ぶ順序、失敗時の扱いは `docs/skill-architecture.md` が正。スキルを呼ぶ順序は通常のコード（`InquiryService`、`AnswerPipeline`、`InvestigationRunner`、`AdviceRunner`）が決め、スキル同士に相談させない。
- 助言が出す案（判断の案、手順の案）は、基づく事実を持つものだけを出す。基づく事実のない案は、コード（`AdviceVerifier`）で捨てる。案は担当者だけに見せ、画面では事実と区別して示す。下書きは担当者の入力欄に入るだけで、AI が直接送ることはない。
- スキルは、品質の基準、使える道具、失敗のしかたのどれかが違う単位で分ける。どれも変わらない分割はしない。
- 調査は事実と根拠を対（`Finding`）で返す。照合（`InvestigationVerifier`）で根拠がすべて落ちた事実は説明に渡さず、「根拠を確かめられなかったこと」として区別して残す。説明は照合の後に、確かめられた事実だけから書く。確かめられた事実が1つもなければ「わからない」として扱う。
- 機械的に確かめられること（根拠の照合、閲覧権限、回数の上限）はAIに任せず、通常のコードで行う。
- `core` パッケージは Spring にも HTTP にも依存させない。Slack の入口など、ほかの入口からも同じ処理を呼ぶため。
- 閲覧権限はプロンプトではなく検索の時点で適用する（`AccessScope`）。
- 種類ごとの経路は `InquiryType` に集約する。経路表を変えるときはテストも直す。
- 入口で次に何を聞くかは、API が決める（`core/intake/IntakePlanner`。Spring に依存しない）。画面は、API が返した質問を描くだけにする。
- 本番環境ではモックを有効にできない（`ProductionMockGuard` が起動時に検査する）。
- 秘密情報（Slack の Webhook の URL など）は設定ファイルに書かず、環境変数で与える。このリポジトリは公開されている。

## Quality Gate / Definition of Done

コード変更を完了と言う前に、ルートで `npm run verify` が通ること。

- `npm run verify:backend`: `./gradlew check`（コンパイルと JUnit）
- `npm run verify:frontend`: 型チェック、oxlint、Prettier、Vitest

画面か API の契約に触れたら `npm run smoke`（Playwright）も通す。純粋関数や型の不変条件を変えたら対応するテストを追加・更新する。

## ローカルでの起動

Docker を起動したうえで、ルートで次を実行する。

```bash
npm run dev:backend    # API（8080）。PostgreSQL のコンテナも自動で起動する
npm run dev:frontend   # 画面（5173）
npm run smoke          # 実ブラウザのスモーク。API と画面を自動で起動する（起動済みの API を使い回す場合は、INQUIRY_RULES_ANSWER_TIMEOUT=3s を付けて起動しておく）
```

Gradle は必ず `scripts/gradle.sh` 経由で実行する。asdf の遅延初期化と OrbStack の環境で、`JAVA_HOME` と `DOCKER_HOST` を補うため。

画面と API の間の仕様は `docs/api-contract.md` が正。変更するときは両方の実装とテストを同時に直す。

画面の考え方、形、言葉づかいは `docs/ux-principles.md` が正。画面に内部の言葉（「0次回答」「種別」「起票」など）を出さない。

## 現在の状態

このリポジトリの位置づけは骨組み（スケルトン）。問い合わせの流れのどこに、どのスキルを置くかが分かることを目的にしている。スキルの中身（プロンプトの品質、実際の資料の検索）を作り込む場所ではない。

最初の版の範囲（置き場所と状態、担当者で完結させる流れ、相談と先例、返事がないときの決まり）は、骨組みとして揃った。次の版（期限、測るもの、変更の記録）は未着手。

- 各要件の実装状況: `docs/requirements.md` の表の右端の欄
- いまの動きと、分かっている制約: `docs/status.md`
- 枠組みに対して残っている差: `docs/framework.md` の末尾

## 作業するときの決まり

- **機能を足す前に、骨組みの目的に効くかを確かめる。** 目的は、スキルの置き場所と受け渡しを示すこと。脇道の機能（運用の便利さ、例外の救済）は、足さない。一度足して削った機能の一覧が `docs/status.md` にある。
- **使う側が、骨組みを書き換えずにカスタマイズできる状態を保つ。** 使う側の変更は、設定（`backend/config/application.yaml`）、足したクラス（`dev.inquiryassist.custom` など）、上書きのファイル（`frontend/src/i18n/overrides.ts`、`frontend/src/overrides.css`）に収まる前提（`README.md` の「カスタマイズの手引き」）。差し替えられる実装を足すときは、既定の実装が引っ込む形にする（スキルと資料の検索は `provider` の設定、そのほかは `@Fallback`）。画面の文言は辞書に置き、色は変数にする。カスタマイズの入口を変えたら、手引きも直す。
- **スキルの実装は、モックだけ。** 実際のモデルをつなぐ実装、指示文、評価の仕組みは、本番に向けた土台として、このリポジトリの外で作る。ここに置くのは、差し替えの境界（スキルのインターフェース、`provider` の設定、一次情報の検索）まで。手元のモデル（Ollama）の実装と評価の仕組みは、一度持っていたが、外した。
- **本人向けの応答に、担当者だけの情報を出さない。** 本人向けの型（`InquiryView`）に項目を足すときは、担当者だけに見せるもの（確かめられなかった内容、裏での調査の結果、AI の案、相談、先例、訳）でないことを確かめる。まとめて守るテストがある。
- **文言をコンポーネントや CSS に直接書かない。** 画面の文言は `frontend/src/i18n/` の辞書に置く（日本語の辞書が型の基準）。日本語の文言を足すときは、英語の辞書にも足す。
- **問い合わせの保存は、`InquiryCommitter` を通す。** 担当者への配信と、案を作り始める判定を、1か所で行うため。
- **状態の遷移は、`core/lifecycle/InquiryStateMachine` に足す。** どの操作がどの状態でできるかを、1か所で読めるように保つ。
