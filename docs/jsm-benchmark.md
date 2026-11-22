---
type: reference
title: Jira Service Management の機能のベンチマーク
description: Jira Service Management（クラウド版）の機能を、Atlassian の公式ドキュメントで確かめた記録。要件定義の突き合わせ（requirements.md の6章）の根拠。2026-11-22 時点。
---

# Jira Service Management の機能のベンチマーク

2026-11-22 に調べた。製品の機能や名称は変わるため、判断に使う前に日付を確かめること。採否の判断は `requirements.md` の6章にあり、この文書は事実の記録だけを持つ。

「確かめたい対応」は、調査の時点での枠組みの要素。現在の定義は `framework.md` が正。

方法: Atlassian 公式ドキュメント（support.atlassian.com）の本文を取得して確認した。検索結果の要約だけで確認した箇所は「要約のみ」と付けた。記憶だけで書いた箇所はない。

用語の変更あり。公式ドキュメントでは Issue が「work item（作業項目）」、Project が「space（スペース）」、Issue type が「work type（作業タイプ）」に変わっている。

## 1. 機能領域の一覧

| 領域 | JSM での名前 | 何をするか |
|---|---|---|
| 受付 | ヘルプセンター／ポータル、チャネル | ポータルはスペースごとに自動作成される。チャネルはメール、ヘルプセンター、埋め込みウィジェット、API、Slack（Chat）など |
| 問い合わせの型 | リクエストタイプ、作業タイプ | リクエストタイプは名称・ポータル表示・フォームを持つ。作業タイプはワークフローとフィールドを持つ |
| 入力 | フォーム | 条件付き表示や入力検証ができる。ポータルで追加情報を集める用途と、作業項目へ追加するチェックリスト用途がある |
| 作業の場 | キュー | 条件で絞った作業項目の一覧。SLA 順に並べられる。リスト、ボード、カレンダー表示がある |
| 流れ | ワークフロー | ステータスと遷移。承認ステップを入れられる |
| 時間管理 | SLA | 開始・一時停止・終了の条件、目標時間、営業時間カレンダーで構成する |
| 自動化 | Automation | ルールビルダー。旧版の「プリセットフロー」もある |
| 知識 | ナレッジベース | Confluence が基本。Google Drive、SharePoint、他サイトの Confluence、サードパーティ製品からも追加できる |
| 評価 | CSAT、レポート、ダッシュボード | 解決時に評価メールを送り、満足度レポートに集計する |
| 重複・関連 | 類似リクエストパネル、リンク | 後述 |
| ITSM の型 | サービスリクエスト／インシデント／問題／変更管理 | 作業カテゴリごとに専用のキューとレポートがある |
| 運用 | サービス、アラート、オンコール | サービス一覧、アラートの集約、当番表とエスカレーションポリシー |
| 資産 | アセット | Premium と Enterprise のみ。構成アイテム（CI）を作業項目に結び付ける |
| 要員 | Workforce management | 担当者への振り分け、稼働の見通し |
| AI | バーチャルサービスエージェント、Rovo、Request Resolver | 第4節 |

出典は「Get to know the main Jira Service Management features」。

## 2. 対応表の各行

**① 問い合わせの種類（5つ）→ リクエストタイプ／作業タイプ。ほぼ合っている。**
- リクエストタイプは顧客向けの窓口。作業タイプはワークフローとフィールドの土台。
- 1つの作業タイプを複数のリクエストタイプで共有できる。1つのリクエストタイプが結び付く作業タイプは1つだけ。
- 5種を「顧客が選ぶ入口」にするならリクエストタイプが近い。ワークフローを分けたいなら作業タイプを分ける。
- ITSM では作業カテゴリ（サービスリクエスト、インシデント、問題、変更、事後レビュー）がある。「おかしいかもしれない」はインシデント、「変えてほしい」は変更に近い。
- 既定のリクエストタイプは「Get IT Help」と「Request a new account」。既定の作業タイプは IT Help、Purchase、Change、Fault、Access。ただしこれは旧テンプレートの記述。

**② 置き場所（システム × 機能）→ 部分的に合っている。**
- 「サービス」機能は、組織が使う製品・アプリとその関係を保持する。変更の承認やインシデント時の通知先に使う。
- システム × 機能の2軸をそのまま持つ標準項目は確認できなかった。コンポーネントとカスタムフィールドで表現する形になる。
- 「Services」と「Components」の対応は公式本文では未確認。

**③ 状態 =「どの区間か × 誰の番か」→ 合っている。**
- 「Waiting for support」と「Waiting for customer」が、まさに「誰の番か」を表す。
- チーム管理スペースの既定は次の9つ。Open、Reopened、Pending、Work in progress、Waiting for customer、Waiting for support、Escalated、Done、Canceled。
- JSM 向けの既定ステータス一覧には、他に Declined、Waiting for approval、Awaiting CAB approval、Planning、Awaiting implementation、Implementing、Peer review / change manager approval、Completed、Under investigation も載っている。
- 旧来の company-managed 既定ワークフローは次の4つ。Waiting for Triage、Waiting for Support、Waiting for Customer、Resolved。顧客向け表示名は、Waiting for Customer が「Requester Action Needed」に変わる。
- 現行の ITSM テンプレートの正確なステータス一覧は確認できなかった。

**④ 終わり方 → Resolution（解決状況）。合っている。ステータスとは別の項目。**
- 既定の解決状況は、全製品共通で Done、Won't do、Duplicate。Jira 共通で Cannot reproduce。JSM 固有で Known error、Hardware failure、Software failure。
- 別項目だと確認できる根拠が3つある。
  - 公式が「解決状況は通常ステータス変更時に設定する」と書いている。
  - CSAT の説明に「解決状況が未設定でも Done カテゴリに入れば評価メールが飛ぶ」とある。
  - SLA の条件に「Resolution: cleared」がある。
- 4つの終わり方との対応は次のとおり。
  - 解決済み → Done
  - 対応しない → Won't do
  - 取り下げ → ステータス Canceled（Won't do で代用も可）
  - 返事がないまま終了 → 標準値はない。独自の値を足す。

**⑤ 相談・エスカレーション → 承認は合っている。エスカレーション専用機能は無い。**
- 承認は、ワークフローの任意のステータスに承認ステップを足す仕組み。
  - 承認者は、承認ボタンまたはメールから承認・却下する。Slack や Teams からも操作できる。
  - 承認者に JSM のライセンスは不要。ただしスペースの顧客である必要がある。
  - 却下が1人でもあると全体が却下になる（チーム管理の記述）。
- エスカレーションは公式の「Best practices for managing escalations」で、次の手段の組み合わせとして説明されている。
  - 「Escalated」ステータス
  - エスカレーション用キューとカスタムフィールド
  - 上位担当への @メンション
  - Jira 側に作る連携作業項目
  - インシデントではオンコールのエスカレーションポリシー
- 「担当者から仕様を決める人への相談」は、承認よりも Escalated ステータスとキューで表すほうが近い。承認は可否の決裁に向く。

**⑥ 返事がなければ自動で終える／追記で開き直す → ルール自体は実在する。日数は版で違う。**
- 旧「プリセットフロー」（2021年8月30日より前に作られたスペース向け、本文確認）は次のとおり。
  - コメントでステータスが更新される。顧客のコメントなら Waiting for support、担当のコメントなら Waiting for customer になる。
  - 顧客がコメントすると、解決済みの作業項目が再オープンされる。
  - 解決から10日で自動クローズされ、顧客に通知する。
  - Waiting for customer が5日続くと、顧客にリマインドする。
- ITSM テンプレート（本文確認）では、SLA「Time to close after resolution」と Automation の組み合わせで、解決の3営業日後に Resolved から Closed へ自動遷移する。
- 現行の Automation テンプレートは次のとおり。
  - 「顧客が閉じたリクエストにコメントしたら再オープン」は実在する（KB、本文確認）。条件は、JSM のスペースであること、ワークフローに「Reopened」（To Do カテゴリ）があること。
  - 「Waiting for customer が5日更新なしなら Resolved にする」の雛形もある（テンプレートライブラリ、本文確認）。
- 注意点は3つある（KB、本文確認）。
  - 再オープンに日数制限を付けるには自前で条件を足す。
  - ポータルからの遷移はワークフローの検証をすり抜ける。
  - コメントが「更新」扱いになり、リマインドで待ち日数がリセットされる。

**⑦ 期限（担当の番だけ数える）→ SLA と一時停止条件。合っている。**
- 開始、一時停止（任意）、終了の3条件を設定する。複数条件はいずれか1つで成立する。
- 例として挙がるのは Waiting for customer 中の一時停止。ただし既定で付いているかは確認できなかった。
- 営業時間カレンダーは複数作れる（既定 9:00〜17:00、昼休み・祝日・タイムゾーン対応）。
- 目標は JQL で作業項目を絞り、優先度ごとに分けられる。優先度が変わると残り時間が再計算される。
- 1つの SLA に目標を最大90個設定できる。
- 再オープン時、Time to resolution は既定でリセットされる。Time to first response はリセットされない（KB）。
- 既定で作られる SLA の完全な一覧は確認できなかった。ドキュメント上で確認できるのは Time to first response、Time to resolution、ITSM の Time to close after resolution。

**⑧ 緊急度 → 合っている。**
- 優先度は Highest、High、Medium、Low、Lowest の5段階。顧客が出した依頼は自動で Medium になる。
- Urgency（緊急度）と Impact（影響度）の2項目から優先度を決める。
- 優先度を決める表は標準では組み込まれていない。公式の例を元に Automation で作る。

**⑨ マスごとの担当 → キューは合っている。自動割り当ては一部のみ確認。**
- リクエストタイプごとに自動でキューへ振り分けられる。
- 自動割り当ては、チーム管理スペースの「Assign a request to someone」ルールが検索要約で確認できただけ。company-managed の標準機能は確認できなかった。
- Automation か Workforce management で実現する。

**⑩ 知識・FAQ → 合っている。**
- 「Knowledge base, powered by Confluence」と書かれている。
- 顧客は、ポータルで検索してそのまま読める。
- 内部用の知識は Request Resolver が使う。

**⑪ 満足度 → CSAT。合っている。**
- 新規スペースでは既定でオン。
- 解決（Done カテゴリへの遷移）時に評価メールを送り、評価とコメントを集める。結果は満足度レポートで見る。
- Slack の Chat を設定済みなら Slack からも回答できる。

**⑫ 経過を見る人 → 参加者と組織は合っている。**
- リクエストの参加者は、共有された人。閲覧、コメント、通知ができる。通知は既定で報告者と同じで、本人が止められる。
- 担当は参加者欄で追加する。顧客もポータルやメールから追加でき、範囲は顧客共有の権限で決まる。
- 組織は、スペースをまたいで共有する顧客グループ。
- 「見るだけ」の専用ロールは顧客側には無い。参加者に通知をオフにしてもらうのが近い。
- インシデントにはステークホルダー（更新を受け取る人）がある。Premium と Enterprise のみ。Free と Standard ではエージェントしか追加できない。

**⑬ AI → 第4節に記載。**

## 3. 枠組みに無さそうで JSM にある機能

- **類似リクエストパネル**: タイトルの類似度（自然言語処理）で最近の依頼を出す。重複の検出に使える。オープン／解決済みで絞れる。AI を有効にするとインシデントでは「関連リソース」表示になる。
- **作業項目のリンク**: 他の依頼や Jira の作業項目へのリンク。連携作業項目で開発チームへ依頼できる。親側が動くと、リンク先にコメントが入る旧プリセットもある。
- **定型返信（Canned responses）**: 機能の存在は目次で確認。手順は未確認。
- **フォーム**: 条件付き表示・入力検証。
- **メール・Slack・ウィジェット受付**: Slack ではメッセージから作業項目を作れる。コメントは双方向に同期する。
- **ポータルのお知らせ**: 全体向けに表示できる。
- **キューの階層と SLA 順の並び**。
- **インシデント／問題／変更管理**: 重大インシデントの昇格、問題へのまとめ、変更のリスク評価と CAB 承認。
- **アラート、オンコール、Statuspage 連携**。
- **アセット（構成管理）**: Premium と Enterprise。
- **Workforce management**: 要員の予測と振り分け。
- **多言語対応**: ポータルとメール文面。
- **顧客種別**: 社外のポータル専用アカウントと、社内の Atlassian アカウントがある。
- **ナレッジの空白検出**: 繰り返しのチケットから不足記事を提案する。Rovo のスキル。

## 4. AI 機能のまとめ

**名称**: ドキュメント名は「Atlassian Intelligence features」から「Rovo features in Jira Service Management」に変わっている。URL には旧名が残る。変更時期の公式発表は確認できなかった。

**プランと提供範囲**:
- Rovo は Standard、Premium、Enterprise で既定有効。
- バーチャルサービスエージェント（VSA）は Service Collection の Premium または Enterprise。組織管理者が AI を有効化する必要がある。
- 顧客感情分析は Premium または Enterprise。
- Rovo agent のヘルプセンター表示は Service Collection が前提。Self Service Agent は Rovo クレジットを消費する。
- Atlassian Government 環境では VSA と AI 機能が使えない。

**バーチャルサービスエージェント**（本文確認）:
- 回答方式は2つ。両方使える。
  - **AI answers**: リンク済みナレッジベースを生成 AI が検索・要約して答える。参照記事のリンクと関連フォームのリンクを付ける。
  - **インテント**: 機械学習で意図を検出し、設計済みの会話フローに沿って進める。情報を集め、リクエストタイプへ振り分ける。
- 人への引き渡しは次の流れ。
  - フローが必要とする場合は作業項目を自動作成する。
  - 顧客が「まだ解決していない」と答えると、言い換えを促すか、人向けの作業項目の作成を提案する。
  - 自動作成に失敗した場合は、既定リクエストタイプのフォームへ案内する。
- 対応チャネルはポータルと Microsoft Teams。Slack、メールにも対応する。

**Rovo agent（ポータル向け）**:
- Studio で作る。ナレッジはヘルプセンター経由で JSM に紐付ける。スキルは最大5つ。
- 「Raise a Request」スキルで、リクエストタイプを選びフォーム項目を埋めて、最大50スペースへ依頼を作れる。
- ポータルには1つだけ表示できる。

**Request Resolver（旧 Rovo Service）**:
- 内部ナレッジに書かれた解決手順から計画を作り、実行する。
- 実行モードは2つ。
  - Supervised は、人が計画を確認してから開始する。
  - Autonomous は、計画の作成から実行まで自動で進む。
- 自信が低い操作（削除、クローズ、外部メール送信など）では止まり、内部メモで担当者を呼ぶ。
- 計画1件につき 100 Rovo クレジット。

**その他の AI**（本文確認）:
- 作業項目の要約
- 顧客感情（Positive／Neutral／Negative）
- 振り分け時のリクエストタイプ提案
- フォーム項目の提案
- リクエストタイプの提案
- 自然言語での作業項目検索
- コメント下書きの生成と文体変換
- ナレッジ記事の下書き生成
- Automation フローの生成
- 作業項目上の提案（サービスリクエストとインシデントのみ）
- インシデント向け機能（アラートのグルーピング、事後レビュー作成、Slack 上の要約・タイムライン）

## 5. 確認できなかったこと

- 現行 ITSM テンプレートの既定ワークフローと既定の解決状況の正確な一覧。確認できたのは、旧「Default configurations」ページ、チーム管理スペースの既定、全製品共通のステータス・解決状況の一覧。
- 既定 SLA の完全な一覧と、一時停止条件が既定で入っているか。
- コンポーネント、サービス、カスタムフィールドの対応。
- company-managed での自動割り当ての標準機能。
- 定型返信の手順。
- 現行 Automation の「リクエストの再オープン」テンプレートに既定の日数があるか。日数の既定は旧プリセットの10日・5日と、ITSM の3営業日だけ。
- Free／Standard／Premium／Enterprise ごとの機能差。VSA、感情分析、アセット、ステークホルダー以外は未確認。
- Data Center との差。調べていない。KB のうち「Cloud only」と書かれたものは Cloud の記述。
- 要約のみで確認した箇所: 自動割り当て、承認の詳細（チーム管理の却下条件）。

## 6. 参照した URL

- https://support.atlassian.com/jira-service-management-cloud/docs/get-to-know-the-main-jira-service-management-features/
- https://support.atlassian.com/jira-cloud-administration/docs/default-configurations-for-jira-service-management/
- https://support.atlassian.com/jira-cloud-administration/docs/what-are-issue-statuses-priorities-and-resolutions/
- https://support.atlassian.com/jira-service-management-cloud/docs/manage-how-work-flows-in-your-team-managed-service-project/
- https://support.atlassian.com/jira-service-management-cloud/docs/whats-the-difference-between-request-types-and-issue-types/
- https://support.atlassian.com/jira-service-management-cloud/docs/what-are-request-types-in-itsm/
- https://support.atlassian.com/jira-service-management-cloud/docs/what-are-slas/
- https://support.atlassian.com/jira-service-management-cloud/docs/what-are-slas-and-how-do-i-view-them-in-my-service-project/
- https://support.atlassian.com/jira-service-management-cloud/docs/create-an-sla/
- https://support.atlassian.com/jira-service-management-cloud/docs/set-up-sla-conditions/
- https://support.atlassian.com/jira-service-management-cloud/docs/set-up-sla-goals/
- https://support.atlassian.com/jira-service-management-cloud/docs/set-up-sla-calendars/
- https://support.atlassian.com/jira-service-management-cloud/docs/auto-close-resolved-service-requests/
- https://support.atlassian.com/jira-service-management-cloud/docs/available-preset-automation-rules/
- https://support.atlassian.com/jira/kb/limit-customer-issue-reopening-to-x-days-after-resolution/
- https://support.atlassian.com/jira/kb/measure-time-to-first-response-sla-for-reopened-issues-in-a-jsm-project/
- https://support.atlassian.com/jira/kb/how-to-create-a-customer-ticket-follow-up-process-in-jira-service-management-using-automation/
- https://www.atlassian.com/software/jira/automation-template-library/auto-close-jira-issues
- https://support.atlassian.com/jira-service-management-cloud/docs/what-are-approvals/
- https://support.atlassian.com/jira-service-management-cloud/docs/best-practices-for-managing-escalations/
- https://support.atlassian.com/jira-service-management-cloud/docs/what-are-queues/
- https://support.atlassian.com/jira-service-management-cloud/docs/what-are-priority-levels-in-jira-service-management/
- https://support.atlassian.com/jira-service-management-cloud/docs/how-do-i-create-a-matrix-using-impact-and-urgency-values/
- https://support.atlassian.com/jira-service-management-cloud/docs/what-are-request-participants/
- https://support.atlassian.com/jira-service-management-cloud/docs/what-are-customers-and-organizations-in-your-service-project/
- https://support.atlassian.com/jira-service-management-cloud/docs/what-are-app-roles-in-jira-service-management/
- https://support.atlassian.com/jira-service-management-cloud/docs/what-are-customer-satisfaction-surveys-csats/
- https://support.atlassian.com/jira-service-management-cloud/docs/allow-customers-to-self-serve-with-knowledge-base/
- https://support.atlassian.com/jira-service-management-cloud/docs/what-are-similar-requests/
- https://support.atlassian.com/jira-service-management-cloud/docs/create-issues-in-slack/
- https://support.atlassian.com/jira-service-management-cloud/docs/about-the-virtual-agent/
- https://support.atlassian.com/jira-service-management-cloud/docs/set-up-your-virtual-agent-in-jira-service-management/
- https://support.atlassian.com/jira-service-management-cloud/docs/create-a-rovo-agent-for-your-help-center/
- https://support.atlassian.com/jira-service-management-cloud/docs/add-a-rovo-agent-to-the-help-center/
- https://support.atlassian.com/jira-service-management-cloud/docs/about-rovo-service/
- https://support.atlassian.com/jira-service-management-cloud/docs/atlassian-intelligence-features-in-jira-service-management/
- https://support.atlassian.com/jira-service-management-cloud/docs/about-customer-sentiment-analysis/
- https://support.atlassian.com/organization-administration/docs/atlassian-intelligence-features-in-jira-service-management/
