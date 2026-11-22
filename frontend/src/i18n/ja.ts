import type {
  Awaiting,
  DecisionKind,
  HandoverReason,
  DeclineReasonKind,
  Ending,
  HistoryActor,
  InquiryTypeId,
  MessageKind,
  Phase,
  SourceType,
  Turn,
  Urgency,
} from "../api/types.ts";

/**
 * 日本語の文言。2つの言語の文言の型は、この辞書を基準にする（英語の辞書にキーが欠けると型エラーになる）。
 * 言葉づかいの仕様は docs/ux-principles.md。本人向けの画面には、内部の言葉（「0次回答」「種別」「判定」
 * 「整形」「起票」「出典」など）を出さない。担当者の画面（`op`）と、開発者向けの部品（`mock`、`trace`、
 * `skills`）の中に限り、内部の言葉を使ってよい。
 */
export const ja = {
  /** 言語そのものの情報 */
  meta: {
    code: "ja",
    /** その言語自身の名前（言語の切り替えと、「本人の言語」の表示に使う） */
    name: "日本語",
    /** 日時と数の書き方に使うロケール */
    locale: "ja-JP",
    /** 「A（B）」の形 */
    paren: (base: string, inner: string) => `${base}（${inner}）`,
    /** 並べた名前をつなぐ区切り */
    joiner: "・",
    /** 項目の間に置く区切り（前後に空白） */
    separator: " ・ ",
  },

  /** 本人向けの画面の固定文言 */
  text: {
    appTitle: "問い合わせアシスト",
    /** 問い合わせの作成前に、画面が描く1問目の質問文（作成後は API が返す） */
    firstQuestion: "何をしたいですか",
    languageLabel: "言語",
    writeLink: "文章で書く",
    inputLabel:
      "うまくまとまっていなくて大丈夫です。起きていること、知りたいことをそのまま書いてください。",
    send: "送信する",
    unknown: "わからない",
    next: "次へ",
    skip: "書かずに進む",
    skippedAnswer: "書かずに進みました",
    alsoWriteLabel: "選択肢にない場合は、文章で答えられます",
    inferredNote: "（文章から読み取りました）",
    rewindHint: "（押すと答え直せます）",
    urgencyLegend: "急ぎですか",
    working: "少しお待ちください。",
    restatementLead: "つまり、こういうことですね。",
    restatementOk: "合っています",
    restatementNo: "少し違います",
    noteLabel: "どこが違いますか？",
    noteSubmit: "作り直してもらう",
    cancel: "やめる",
    rewrite: "自分で書き直す",
    rewriteLabel: "言い直しを書き直してください",
    rewriteSubmit: "この内容で確定する",
    noRevisionsLeft:
      "うまく汲み取れませんでした。書いていただいた内容のまま担当者に送ることもできます。",
    sendAsIs: "このまま担当者に送る",
    backToQuestions: "前の質問に戻る",
    researching: "調べています",
    researchingLabel: "調べている内容",
    resultTitle: "調べた結果",
    notFound: "手元の資料からは答えを見つけられませんでした。",
    notAnswered: "ご質問への答えは、手元の資料では確認できませんでした。",
    relatedTitle: "関連して分かったこと",
    citationsTitle: (n: number) => `根拠にした資料（${n}件）`,
    stepsTitle: "手順",
    stepCheck: "確かめ方: ",
    memoTitle: "担当者向けの詳しい内容",
    memoFindings: "分かったこと",
    memoNotFound: "調べたが見つからなかったこと",
    memoNextChecks: "次に確認すること",
    resolved: "解決しました",
    notResolved: "まだ解決していません（担当者に送る）",
    doneByMyself: "自分でできました",
    askStaff: "担当者に頼む",
    sendToStaff: "担当者に送る",
    bugFiled: "いまの動きを調べました。この内容を添えて担当者に送りました。",
    doForMeFiled: "調べた内容を添えて、担当者に送りました。",
    retryResearch: "もう一度調べる",
    retryCompose: "もう一度試す",
    sendNow: "答えを待たずに担当者に送る",
    sendNowHint: "待たずに人に見てもらいたいときはこちら。",
    restart: "新しい問い合わせを書く",
    /** 列や一覧の件数のあとに添える単位（読み上げ用） */
    countUnit: (_count: number) => "件",
    boardEmpty: "ありません",
  },

  /** 送った後の画面（ページの上部、一覧、やり取り）の文言 */
  user: {
    navLabel: "ページの移動",
    myInquiries: "送った問い合わせ",
    unreadReplies: (count: number) => `未読の返信 ${count}件`,
    loading: "読み込んでいます…",
    loadFailed: "読み込めませんでした。時間をおいて開き直してください。",
    notFound: "この問い合わせは見つかりませんでした",
    listEmpty: "送った問い合わせはまだありません。",
    unreadReplyMark: "未読の返信",
    currentStatus: "いまの状況",
    exchange: "やり取り",
    noReplyYet: "担当者からの返信は、ここに表示されます。",
    appendLabel: "追加で伝える",
    appendSubmit: "送る",
    replyLabel: "担当者の質問に答える",
    reopenNote: "送ると、担当者の番で開き直します。",
    progressQuestion: "やりたかったことは、進められそうですか。",
    stillUnresolved: "まだ解決していません",
    unresolvedNoteLabel: "どこが解決していませんか（任意）",
    unresolvedSend: "担当者に送る",
    /** 返事がないことの知らせ。`date` は終わる予定の日（月と日） */
    noReplyNotice: (date: string) =>
      `お返事がないため、${date}に「回答済み」として終了します。解決したかどうかを、下から選んでください。終了した後も、このページから続きを送れます。`,
    /** 聞き返しに答えていない件の知らせ（終わり方は「取り下げ」） */
    noReplyNoticeWithdrawn: (date: string) =>
      `お返事がないため、${date}に「取り下げ」として終了します。続ける場合は、下から担当者の質問に答えてください。終了した後も、このページから続きを送れます。`,
    withdrawLink: "この問い合わせを取り下げる",
    withdrawConfirm: "取り下げますか。取り下げた後は、続きを送れません。",
    withdrawDo: "取り下げる",
    withdrawCancel: "やめる",
    restartOther: "別の問い合わせを書く",
    traceSummary: "裏側を見る（開発者向け）",
    devToOperator: "開発用: 担当者の画面へ",
    devToAsker: "開発用: 本人の画面へ",
    devToOwner: "開発用: 仕様の持ち主の画面へ",
  },

  /** エラーの文言。画面は API のエラーの `code` から文言を決める（`message` は開発者向け） */
  errors: {
    /** AI を使えないときのエラー文（エラーの `code` ごと） */
    ai: {
      AI_REFUSED:
        "この内容は自動では調べられませんでした。担当者に直接送れます。",
      AI_UNAVAILABLE: "いま自動の調査を使えません。担当者に直接送れます。",
    } as Record<string, string>,
    /** 上記以外のエラーの汎用文言 */
    generic: "うまく処理できませんでした。時間をおいてもう一度お試しください。",
    /** AI を使えない失敗のあと、答えを待たずに送れるとき（ページの下に案内が出ている場合）の案内 */
    sendNowHint: "ページの下の「答えを待たずに担当者に送る」から送れます。",
    /** 問い合わせがまだ保存されていないときの案内 */
    beforeCreateHint:
      "内容を少し変えるか、時間をおいてもう一度送信してください。",
    /** 通信の失敗（開発者向けの `message`） */
    network: "サーバーに接続できませんでした",
    /** 進捗の接続が切れた（開発者向けの `message`） */
    streamLost: "サーバーとの接続が切れました",
  },

  /** 根拠にした資料の種類の呼び名 */
  sources: {
    CODE: "コード",
    FAQ: "FAQ",
    DOCUMENT: "ドキュメント",
    GLOSSARY: "用語対応表",
  } as Record<SourceType, string>,

  /** 本人の画面で、状態を表す言葉 */
  asker: {
    /** 待っていることの言葉（本人のページの「いまの状況」と、ボードのカード） */
    awaiting: {
      INTAKE_ANSWER: "入力の途中です",
      QUESTION_CONFIRMATION: "問いの確認をお願いします",
      INVESTIGATION: "調べています",
      RESULT_CONFIRMATION: "結果を確かめてください",
      HANDLING: "担当者が確認しています",
      ASKER_REPLY: "担当者から質問が届いています",
      CONSULTATION: "担当者が、仕様を決める人に確認しています",
    } as Record<Awaiting, string>,
    /** 結果を確かめている件のうち、担当者が結果を伝えたものの言葉 */
    operatorResult: "担当者から結果が届いています",
    /** 終わり方の、本人に見せる言葉 */
    endings: {
      RESOLVED: "解決済み",
      ANSWERED: "回答済み",
      DECLINED: "見送り",
      WITHDRAWN: "取り下げ",
    } as Record<Ending, string>,
    /** 終わりの文言 */
    endingText: {
      RESOLVED: "解決してよかったです。この内容は記録しておきます。",
      ANSWERED:
        "お返事がなかったため、いったん終了しました。解決していなければ、このページから続きを送れます。",
      DECLINED: "担当者から、今回は対応を見送るとの連絡がありました。",
      WITHDRAWN: "この問い合わせは取り下げました。",
    } as Record<Ending, string>,
    /** やり取りの発言の見出し（担当者の発言の種類）。本人の追記には見出しを付けない */
    messageKinds: {
      ASK: "質問",
      RESULT: "結果",
      DECLINE: "見送りの連絡",
      NOTE: null,
    } as Record<MessageKind, string | null>,
    /** AI が引き継いだ理由を本人に伝える1文（「担当者が確認しています」のすぐ下）。それ以外の理由では添えない */
    handoverReasonNote: {
      NO_ANSWER:
        "調べましたが、はっきりした答えが見つかりませんでした。担当者に引き継ぎました。",
      TIMEOUT:
        "調べるのに時間がかかっているため、担当者に引き継ぎました。調べた内容は、担当者に渡します。",
    } as Partial<Record<HandoverReason, string>>,
    /** 返事がないまま期間が過ぎて、時間で「取り下げ」になった件の終わりの文言 */
    endingTextTimeWithdrawn:
      "お返事がなかったため、取り下げとして終了しました。続ける場合は、このページから送れます。",
    /** 担当者に渡した直後の案内（渡し方で言い方が変わる） */
    handover: {
      blocked:
        "担当者にすぐ届けました。書いていただいた内容をそのまま添えています。",
      immediate:
        "担当者に送りました。書いていただいた内容をそのまま添えています。",
      withAnswer:
        "担当者に送りました。ここまでの内容と調べた結果を添えています。同じ説明をもう一度する必要はありません。",
      plain:
        "担当者に送りました。ここまでの内容を添えています。同じ説明をもう一度する必要はありません。",
      /** AI が引き継いだ件（引き継いだことは、理由の文で伝える） */
      attachedWithAnswer:
        "ここまでの内容と調べた結果を添えています。同じ説明をもう一度する必要はありません。",
      attachedPlain:
        "ここまでの内容を添えています。同じ説明をもう一度する必要はありません。",
    },
    /** 本人のボードの列の見出し */
    columns: {
      YOURS: "あなたの番",
      THEIRS: "相手の番",
      CLOSED: "終わり",
    },
  },

  /** 担当者の画面の文言。担当者は開発チームなので、内部の言葉（出典・技術メモ）を使ってよい */
  op: {
    title: "問い合わせ受付",
    tabTitle: "問い合わせ受付",
    unreadOnly: "未読だけ",
    listLabel: "受付一覧",
    detailLabel: "問い合わせの詳細",
    close: "閉じる",
    loading: "読み込んでいます…",
    messageCount: (count: number) => `やり取り ${count}件`,
    arrived: "新しい問い合わせが届きました",
    dismiss: "通知を閉じる",
    unread: "未読",
    unreadCount: (count: number) => `未読 ${count}件`,
    sentImmediately: "今すぐ送る",
    typeUnknown: "種類未定",
    phaseLabel: "区間",
    stateLabel: "いまの状態",
    askerLanguage: (name: string) => `本人の言語: ${name}`,
    systemUnknown: "システム未定",
    placedByAi: "（AIが文章から置きました）",
    placedByOperator: "（担当者が置き直しました）",
    slackOk: "Slack に投稿済み",
    slackFailed: "Slack への投稿に失敗しました",
    inquiryNotFound: "この問い合わせは見つかりませんでした",
    utterance: "元の発話",
    restatement: "言い直し",
    fieldTarget: "対象",
    fieldGoal: "やりたいこと",
    fieldWhatHappened: "起きたこと",
    fieldExpected: "期待した結果",
    intake: "入口の質問と答え",
    unknownAnswer: "わからない",
    skippedAnswer: "書かずに進みました",
    inferredNote: "（文章から読み取り）",
    unconfirmed: "根拠を確かめられなかったこと",
    unconfirmedNote: "調査で挙がりましたが、資料で裏づけを取れていません。",
    revisionNotes: "作り直しで伝えられた一言",
    result: "調べた結果",
    noResult: "調べた結果はありません（調査の前に送られました）",
    offTopic: "問いに答えていないと判定（関連する内容のみ）",
    notGrounded: "一次情報から答えを特定できませんでした",
    findings: "分かったこと",
    notFound: "調べたが見つからなかったこと",
    nextChecks: "次に確認すること",
    citations: "出典",
    verified: "照合済み",
    unverified: "照合できず",
    loadFailed: "読み込めませんでした。時間をおいて開き直してください。",
    phaseSwitchLabel: "区間を切り替える",
    phaseSwitchHint: "区間を切り替えられるのは、担当者の番のときだけです。",
    placementSummary: "置き直す",
    placementType: "種類",
    placementSystem: "システム",
    placementFunction: "機能",
    placementUnset: "未定",
    placementSubmit: "置き直す",
    sendLabel: "本人に送る",
    sendAsk: "聞き返す",
    sendResult: "結果を伝える",
    sendAskHint: "聞き返す: 本人の返事を待ちます",
    sendResultHint:
      "結果を伝える: 本人が解決したかを確かめます（問い合わせを終えるのは本人です）",
    declineSummary: "見送る",
    declineKind: "理由の種類",
    declineReason: "理由",
    declineSubmit: "見送りとして終える",
    historySummary: "状態の履歴",
    historyEmpty: "履歴はまだありません。",
    dropSend: "「本人に送る」の入力欄を開きました",
    dropDecline: "「見送る」の入力欄を開きました",
    dropConsult: "「相談する」の入力欄を開きました",
    exchange: "やり取り",
    noMessages: "やり取りはまだありません。",
    actionFailed:
      "うまく処理できませんでした。時間をおいてもう一度お試しください。",
    /** 別の列に移ったことを伝える文。`head` はカードの言い直しの先頭 */
    moved: (head: string, column: string) =>
      `「${head}」を${column}に移しました`,
    /** 担当者のボードの列の見出し */
    columns: {
      OPERATOR: "担当者の番",
      CONSULTING: "相談中",
      ASKER: "本人の番",
      CLOSED: "終わり",
    },
    phases: {
      INTAKE: "受付",
      KNOW: "分かる",
      DECIDE: "決まる",
      ACT: "行われる",
      CLOSED: "終わり",
    } as Record<Phase, string>,
    turns: {
      ASKER: "本人の番",
      AI: "AIの番",
      OPERATOR: "担当者の番",
      OWNER: "仕様の持ち主の番",
    } as Record<Turn, string>,
    awaiting: {
      INTAKE_ANSWER: "入口の質問に答えている",
      QUESTION_CONFIRMATION: "問いを確かめている",
      INVESTIGATION: "AIが調べている",
      RESULT_CONFIRMATION: "結果の確かめ待ち",
      HANDLING: "担当者が受けている",
      ASKER_REPLY: "返事待ち",
      CONSULTATION: "仕様の持ち主の決定待ち",
    } as Record<Awaiting, string>,
    endings: {
      RESOLVED: "解決済み",
      ANSWERED: "回答済み",
      DECLINED: "見送り",
      WITHDRAWN: "取り下げ",
    } as Record<Ending, string>,
    declineReasons: {
      NOT_REPRODUCIBLE: "再現しない",
      KNOWN_LIMITATION: "既知の制限",
      OUT_OF_SCOPE: "対象外",
      DUPLICATE: "重複",
      OTHER: "その他",
    } as Record<DeclineReasonKind, string>,
    actors: {
      ASKER: "本人",
      AI: "AI",
      OPERATOR: "担当者",
      OWNER: "仕様の持ち主",
      TIME: "期間",
    } as Record<HistoryActor, string>,
    /** 状態の履歴の `event` の呼び名 */
    events: {
      CREATED: "問い合わせができた",
      QUESTION_COMPOSED: "問いを組み立てた",
      REOPENED_INTAKE: "入口の質問へ戻った",
      QUESTION_CONFIRMED: "問いを確かめた",
      ANSWERED_BY_AI: "AI が答えた",
      HANDED_OVER: "担当者に渡した",
      ASKED_BACK: "本人に聞き返した",
      REPLIED: "聞き返しに答えた",
      RESULT_DELIVERED: "結果を伝えた",
      RESOLVED: "解決を伝えた",
      DECLINED: "見送った",
      WITHDRAWN: "取り下げた",
      REOPENED: "追記で開き直した",
      REMINDED: "返事がないことを本人に知らせた",
      ANSWERED: "返事がないまま終わった",
      CONSULTED: "仕様の持ち主に相談した",
      DECIDED: "仕様の持ち主が決めた",
      CONSULTATION_CANCELED: "相談を取り下げた",
      PHASE_CHANGED: "区間を変えた",
      PLACEMENT_CHANGED: "置き直した",
    } as Record<string, string>,
    /** 緊急度の呼び名（設定から名前を取れないときの代わり） */
    urgencies: {
      NORMAL: "通常",
      HURRY: "急ぎ",
      BLOCKED: "業務が止まっている",
    } as Record<Urgency, string>,
    /** 終わった件で、フォームの代わりに示す文（`{ending}` は終わり方） */
    waitingClosed: (ending: string) =>
      `この問い合わせは終わっています（${ending}）。本人が追記すると、担当者の番で開き直します。`,
    /** 返事がないときの予定（結果を本人が確かめている件）。日付は月と日 */
    /** 渡った理由の行（担当者の番のとき） */
    handoverReasonLabel: (reason: string) => `渡った理由: ${reason}`,
    handoverReasons: {
      SENT_NOW: "本人が、答えを待たずに送った",
      UNRESOLVED: "本人が、解決していないと答えた",
      BLOCKED: "業務が止まっている",
      ROUTE: "この種類は、担当者が判断する",
      NO_ANSWER: "AI が答えられなかった",
      TIMEOUT: "AI の調査が、時間内に終わらなかった",
      REOPENED: "本人が、終わった件に追記した",
      DECIDED: "仕様の持ち主が決めた",
    } as Record<HandoverReason, string>,
    /** 調査の状況の行（「調べた結果」の見出しのすぐ下） */
    investigationStatus: {
      NOT_RUN: "AI は調べていません。",
      RUNNING: "AI が調べています。終わると、ここに結果が出ます。",
      DONE_ASKER: "本人も、この結果を見ています。",
      DONE_OPERATOR: "この結果は、本人には見せていません。",
      FAILED: "AI の調査は失敗しました。",
    },
    investigationUnconfirmed:
      "本人が確かめていない言い直しをもとに調べています。",
    investigationStart: "AI に調べさせる",
    investigationRetry: "調べ直す",
    investigationHint: "番は変わりません。結果は、担当者だけに見えます。",
    /** AI の案（「調べた結果」のすぐ下の節） */
    adviceTitle: "AI の案",
    adviceMark: "案",
    adviceIntro: "AI が、上の事実から考えた案です。決めるのは担当者です。",
    adviceRunning: "AI が案を作っています。",
    adviceFailed: "AI の案は作れませんでした。",
    judgmentTitle: "判断の案",
    /** 相談が要りそうなときの見出し。続けて「: （理由）」を添える */
    consultationNeeded: "仕様の持ち主への相談が要りそうです",
    consultationNotNeeded: "相談は要らなそうです。",
    adviceBasedOn: "基づく事実",
    procedureTitle: "手順の案",
    stepTarget: "対象",
    stepAction: "操作",
    stepCheck: "確かめ方",
    adviceRollback: "元に戻す方法",
    adviceStaffCarryOut: "実行するのは担当者です。",
    /** 返信の下書き（「本人に送る」の入力欄のすぐ上） */
    /** 訳（本人の言語が画面の言語と違うとき） */
    translationShow: "訳を見る",
    translationHide: "訳を隠す",
    translationLabel: "訳（AI）",
    draftNote: "伝えたいこと（任意）",
    draftAsk: "聞き返しを下書きさせる",
    draftResult: "結果を下書きさせる",
    draftHint: "入力欄が空のときに使えます",
    draftNotice: (language: string) =>
      `AI の下書きです。確かめて、直してから送ってください。本人の言語（${language}）で書かれています。`,
    noReplyPlanned: (remindDate: string) =>
      `返事がなければ、${remindDate}に本人へ知らせます`,
    noReplyReminded: (remindedDate: string, closeDate: string) =>
      `${remindedDate}に本人へ知らせました。返事がなければ、${closeDate}に「回答済み」で終わります`,
    noReplyRemindedWithdrawn: (remindedDate: string, closeDate: string) =>
      `${remindedDate}に本人へ知らせました。返事がなければ、${closeDate}に「取り下げ」で終わります`,
    waitingAskerReply: "本人の返事を待っています。",
    waitingResult: "本人が結果を確かめるのを待っています。",
    waitingAi: "AI の処理を待っています。",
    waitingAsker: "本人の操作を待っています。",
    /** 仕様の持ち主への相談（「本人に送る」のすぐ下） */
    consultSummary: "相談する",
    consultHint:
      "相談している間は、仕様の持ち主の番になります。調べた結果や AI の案は、相談先にも見えます。",
    consultTargets: "相談先",
    consultOther: "ほかの人",
    consultQuestion: "相談したいこと",
    consultSubmit: "仕様の持ち主に相談する",
    /** 相談している間の表示。`names` は相談先の名前 */
    consultWaiting: (names: string) =>
      `仕様の持ち主の決定を待っています（相談先: ${names}）`,
    consultWaitingPlain: "仕様の持ち主の決定を待っています。",
    consultCancel: "相談を取り下げる",
    /** 「相談中」の列のカードに添える、相談先 */
    consultedNote: (names: string) => `相談先: ${names}`,
    /** 名前を並べる */
    nameList: (names: string[]) => names.join("、"),
    /** 仕様の持ち主の決定（「AI の案」のすぐ下の節） */
    decisionTitle: "仕様の持ち主の決定",
    decisionContent: "決定の内容",
    decisionReason: "理由",
    decisionSituation: "状況",
    decisionKinds: {
      AS_INTENDED: "意図どおり",
      DEFECT: "不具合",
      ACCEPT: "受ける",
      REJECT: "受けない",
      ADOPT: "採用する",
      DEFER: "見送る",
      OTHER: "その他",
    } as Record<DecisionKind, string>,
    /** 同じ置き場所の先例（「調べた結果」のすぐ下の節） */
    precedentsTitle: "同じ置き場所の先例",
    precedentsEmpty: "同じ置き場所の先例は、まだありません。",
    precedentsLink: "先例の一覧を見る",
    precedentUnconfirmed: "決めた人が、まだ確かめていません",
    precedentFunction: (name: string) => `機能: ${name}`,
    /** 担当者のボードと持ち主の画面の上部の、先例の一覧へのリンク */
    precedentsNav: "先例",
    /** AI の案が基づく先例 */
    adviceBasedOnPrecedents: "基づく先例",
    /** 決定を記録する（会議などで決まった場合） */
    recordDecisionSummary: "決定を記録する（会議などで決まった場合）",
    recordDecisionNote:
      "決めた人が後から確かめられるように、仕様の持ち主の画面に出ます。",
  },

  /** 仕様の持ち主の画面の文言 */
  owner: {
    title: "仕様の持ち主",
    tabTitle: "決定を待っている相談",
    listTitle: "決定を待っている相談",
    refresh: "更新",
    empty: "決定を待っている相談は、ありません。",
    consultTitle: "相談",
    askedBy: "相談した人",
    askedAt: "相談した日時",
    consultedTo: "相談先",
    question: "相談したこと",
    materialTitle: "材料",
    decideTitle: "決める",
    situation: "状況",
    situationHint:
      "どんな状況についての決定かを、後から読む人に分かるように書きます",
    kind: "決定の種類",
    choose: "選んでください",
    decision: "決定の内容",
    reason: "理由",
    decidedBy: "決めた人",
    decidedByOther: "ほかの人",
    decidedByName: "決めた人の名前",
    submit: "決定を記録する",
    note: "決定は先例として残り、次の同じ種類の問い合わせで、担当者と AI が参照します。本人への連絡は、担当者が行います。",
    recorded: "決定を記録しました。担当者の番に戻りました。",
    notWaiting: "この相談は、すでに決まったか、取り下げられています。",
    unconfirmedTitle: "確かめてほしい先例",
    confirm: "このとおりです",
    edit: "直す",
    save: "保存",
    cancel: "やめる",
  },

  /** 先例の一覧の画面の文言 */
  precedents: {
    title: "先例の一覧",
    filterLabel: "絞り込み",
    system: "システム",
    function: "機能",
    type: "種類",
    all: "すべて",
    empty: "先例は、まだありません。",
    openOriginal: "元の問い合わせを見る",
    toOperator: "担当者のボードへ",
    toOwner: "仕様の持ち主の画面へ",
    listLabel: "先例",
  },

  /** モックの案内枠（開発者向け） */
  mock: {
    barLabel: "モックのシナリオ",
    badge: "モック",
    badgePartial: "一部モック",
    tryExamples: "例文を試す",
    open: "開く",
    close: "閉じる",
    pickHelp:
      "例文を押すと「文章で書く」の入力欄に入ります（最初の画面で使えます）",
    situations: "状況の例",
    actions: "動きの例",
    tagHelp: "タグは、モックで動いているスキルにだけ効きます",
    investigationHelp:
      "何を尋ねても、求人の掲載期限についての調査結果が返ります",
    /** 種類ごとの例文。API のモックの受付が、その種類に置く語を使う */
    typeExamples: {
      HOW_TO: {
        label: "使い方を知りたい",
        text: "求人の掲載期限を延ばす手順を知りたいです。",
      },
      HOW_IT_WORKS: {
        label: "仕組みを知りたい",
        text: "求人の掲載期限は、何日まで延長できますか？",
      },
      LOOKS_WRONG: {
        label: "おかしいかもしれない",
        text: "応募者一覧の件数が実際と合わず、不具合かもしれません。",
      },
      DO_FOR_ME: {
        label: "やってほしい",
        text: "掲載期限を一括で延長してほしいです。",
      },
      CHANGE_REQUEST: {
        label: "変えてほしい",
        text: "応募者一覧の並び順を、新しい順に変えてほしいです。",
      },
    } as Record<InquiryTypeId, { label: string; text: string }>,
  },

  /** スキルの呼び名と、実装の構成の説明（開発者向け） */
  skills: {
    /** スキルの識別子と呼び名（表示順） */
    labels: [
      ["reception", "受付"],
      ["interviewer", "聞き手"],
      ["restater", "言い直し"],
      ["investigator", "調査"],
      ["explainer", "説明"],
      ["advisor", "助言"],
      ["drafter", "下書き"],
      ["translator", "訳"],
    ] as [id: string, label: string][],
    /** 内訳の文。例: 「受付・聞き手は手元のモデル、調査はモックで動いています」 */
    mixText: (real: string[], mock: string[]) =>
      `${real.join("・")}は手元のモデル、${mock.join("・")}はモックで動いています`,
    allReal:
      "全スキルが手元のモデルで動いています（一次情報の検索がモックです）",
  },

  /** スキルの実行記録の表示（開発者向け） */
  trace: {
    /** 段の呼び名（スキルの識別子ごと。照合は `verifier`） */
    stages: {
      reception: "受付",
      interviewer: "聞き手",
      restater: "言い直し",
      investigator: "調査",
      verifier: "照合（コード）",
      explainer: "説明",
      advisor: "助言",
      adviceVerifier: "案の確認（コード）",
      drafter: "下書き",
      translator: "訳",
    } as Record<string, string>,
    /** 案の確認（コード）の段の説明 */
    adviceCodeDetail: "案が基づく事実を確かめる",
    codeKind: "通常のコード",
    skillKind: "AI のスキル",
    idle: "まだ動いていません",
    calls: (count: number) => `${count}回（合計）`,
    failedCalls: (count: number) => `失敗 ${count}回`,
    noFailure: "失敗なし",
    codeDetail: "調査の結果の根拠を確かめる",
    tokens: (input: string, output: string) =>
      `入力 ${input} / 出力 ${output} トークン`,
    noRecord: "記録なし",
    millis: (value: number) => `${value}ミリ秒`,
    seconds: (value: string) => `${value}秒`,
    mock: "モック",
    local: "手元のモデル",
    implJoiner: "、",
  },
};

/** 文言の辞書の型。日本語の辞書が基準。 */
export type Messages = typeof ja;
