# 業務用語とコード上の名前の対応表

| 業務用語 | コード上の名前 | 補足 |
| --- | --- | --- |
| 掲載期限 | JobPosting.expiresAt | この時刻を過ぎると一覧に出ない |
| 掲載中 | PostingStatus.PUBLISHED | 応募者に表示される状態 |
| 下書き | PostingStatus.DRAFT | 応募者には表示されない |
| 掲載終了 | PostingStatus.CLOSED | 担当者が手動で終了した状態 |
| 請求の締め日 | BillingCycle.closingDay | 全取引先で共通。毎月末日 |
| 掲載開始日時 | JobPosting.publishedAt | 応募者向けの一覧の並び順に使う |
