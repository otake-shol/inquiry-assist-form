package dev.inquiryassist.inquiry;

import dev.inquiryassist.core.Citation;
import dev.inquiryassist.core.InquiryType;
import dev.inquiryassist.core.Placement;
import dev.inquiryassist.core.StructuredInquiry;
import dev.inquiryassist.core.TechnicalMemo;
import dev.inquiryassist.core.intake.Choice;
import dev.inquiryassist.core.intake.IntakeCatalog;
import dev.inquiryassist.core.intake.IntakeEntries;
import dev.inquiryassist.core.intake.IntakeEntry;
import dev.inquiryassist.core.intake.IntakeNext;
import dev.inquiryassist.core.intake.IntakeTexts;
import dev.inquiryassist.core.intake.IntakeQuestion;
import dev.inquiryassist.core.intake.PlacedBy;
import dev.inquiryassist.core.intake.QuestionKind;
import dev.inquiryassist.core.lifecycle.Awaiting;
import dev.inquiryassist.core.lifecycle.Ending;
import dev.inquiryassist.core.lifecycle.InquiryStateMachine;
import dev.inquiryassist.core.lifecycle.NoReplyRule;
import dev.inquiryassist.inquiry.InquiryEntity.StoredCitation;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Component;

/**
 * 問い合わせ記録から、本人向けの {@link InquiryView}、担当者向けの一覧と詳細の項目を作る。
 *
 * <p>置き場所の名前は、記録に保存した値ではなく、その時点の設定から、見る人の言語の名前で引く（設定の名前の変更に追従し、
 * 言語ごとの名前に切り替えるため）。設定にないものは識別子を名前にする。入口の質問と答えも、設定から来たものと、
 * 入口の決まった質問は、見る人の言語で返す。AI が作った質問と、本人が書いた文章は、書かれたときの言語のまま。
 */
@Component
class InquiryPresenter {

    private static final int SUMMARY_FALLBACK_LENGTH = 80;

    private final IntakeLocales locales;
    private final InquiryProperties properties;
    private final SkillImplementations implementations;

    /**
     * @param locales 言語ごとの、種類、システム、機能の名前と、次の質問を決める処理
     * @param properties 実行記録の有効・無効の設定
     * @param implementations スキルごとの実装
     */
    InquiryPresenter(
            IntakeLocales locales,
            InquiryProperties properties,
            SkillImplementations implementations) {
        this.locales = locales;
        this.properties = properties;
        this.implementations = implementations;
    }

    /**
     * 本人向けの表現を作る。
     *
     * @param e 問い合わせ記録
     * @return 本人向けの表現。{@code memo.unconfirmed} は常に空
     */
    InquiryView toView(InquiryEntity e) {
        String lang = viewerOf(e);
        IntakeNext next = e.awaiting == Awaiting.INTAKE_ANSWER ? locales.planner(lang).next(e.snapshot()) : null;
        // 裏での調査の結果は、担当者だけに見せる
        InquiryView.AnswerView answer = e.answerGrounded == null || e.investigationAudience == InvestigationAudience.OPERATOR
                ? null
                : new InquiryView.AnswerView(
                        e.answerGrounded,
                        e.answered(),
                        e.askerExplanation,
                        forAsker(e.technicalMemo),
                        e.citations.stream()
                                .filter(StoredCitation::verified)
                                .map(c -> new Citation(
                                        c.sourceType(),
                                        c.repository(),
                                        c.path(),
                                        c.startLine(),
                                        c.endLine(),
                                        c.quote(),
                                        c.revision()))
                                .toList());
        return new InquiryView(
                e.id,
                e.createdAt,
                e.language,
                e.phase,
                e.turn,
                e.awaiting,
                e.ending,
                e.closedBy,
                e.declined,
                e.urgency,
                placed(e, QuestionKind.TYPE, lang),
                placed(e, QuestionKind.SYSTEM, lang),
                placed(e, QuestionKind.FUNCTION, lang),
                e.utterance(),
                intakeView(e, next, lang),
                e.revisionsLeft,
                structuredView(e),
                answer,
                InquiryStateMachine.canSendNow(e.state(), e.context(next)),
                e.sentImmediately,
                e.handedOverAt,
                e.handoverReason,
                e.askerUnreadReply,
                noReplyOf(e),
                MessageBodies.viewsOf(e),
                TraceViews.of(e, properties, implementations, false),
                e.mock);
    }

    /**
     * 担当者向けの一覧の1件を作る。
     *
     * @param e 問い合わせ記録
     * @param lang 見る人の言語
     * @return 一覧の1件
     */
    OperatorItem toItem(InquiryEntity e, String lang) {
        return new OperatorItem(
                e.id,
                e.handedOverAt,
                e.language,
                ref(placed(e, QuestionKind.TYPE, lang)),
                ref(placed(e, QuestionKind.SYSTEM, lang)),
                ref(placed(e, QuestionKind.FUNCTION, lang)),
                e.urgency,
                summaryOf(e, lang),
                e.sentImmediately,
                e.readAt != null,
                e.phase,
                e.turn,
                e.awaiting,
                e.ending,
                e.messages.size());
    }

    /**
     * 一覧の要約。言い直しの文章、なければ本人が書いた文章の先頭80文字、それもなければ置き場所の名前
     * （種類、システム、機能を、見る人の言語の区切りでつないだもの）。本人向けの一覧と共有する。
     *
     * @param e 問い合わせ記録
     * @param lang 見る人の言語
     * @return 要約
     */
    String summaryOf(InquiryEntity e, String lang) {
        StructuredInquiry structured = e.structured();
        if (structured != null) {
            return structured.summary();
        }
        String utterance = e.utterance();
        if (utterance != null) {
            return utterance.substring(0, Math.min(utterance.length(), SUMMARY_FALLBACK_LENGTH));
        }
        List<String> names = new ArrayList<>();
        for (QuestionKind kind : List.of(QuestionKind.TYPE, QuestionKind.SYSTEM, QuestionKind.FUNCTION)) {
            InquiryView.PlacedView placed = placed(e, kind, lang);
            if (placed != null) {
                names.add(placed.label());
            }
        }
        IntakeTexts texts = locales.texts(lang);
        return names.isEmpty() ? texts.noContent() : String.join(texts.nameSeparator(), names);
    }

    /**
     * 置き場所の1項目を、名前と決めた人つきで返す。
     *
     * @param e 問い合わせ記録
     * @param kind {@code TYPE}、{@code SYSTEM}、{@code FUNCTION} のいずれか
     * @param lang 見る人の言語
     * @return 決まっていなければ null
     */
    InquiryView.PlacedView placed(InquiryEntity e, QuestionKind kind, String lang) {
        Placement placement = e.placement();
        if (e.placementOverride != null) {
            // 担当者が置き直した置き場所。入口で本人が選んだ答えは、入口の答え（intake）にそのまま残る
            String id = switch (kind) {
                case TYPE -> placement.type() == null ? null : placement.type().name();
                case SYSTEM -> placement.systemId();
                case FUNCTION -> placement.functionId();
                default -> throw new IllegalArgumentException("置き場所の質問ではない: " + kind);
            };
            return id == null ? null : new InquiryView.PlacedView(id, labelOf(kind, id, placement, lang), PlacedBy.OPERATOR);
        }
        IntakeEntry entry = IntakeEntries.find(e.intake, kind);
        if (entry == null || !entry.answer().decides()) {
            return null;
        }
        String id = entry.answer().optionId();
        return new InquiryView.PlacedView(id, labelOf(kind, id, placement, lang), IntakeEntries.placedBy(e.intake, kind));
    }

    private String labelOf(QuestionKind kind, String id, Placement placement, String lang) {
        IntakeCatalog catalog = locales.catalog(lang);
        return switch (kind) {
            case TYPE -> catalog.typeLabel(InquiryType.valueOf(id));
            case SYSTEM -> catalog.systemLabel(id);
            case FUNCTION -> catalog.functionLabel(placement.systemId(), id);
            default -> throw new IllegalArgumentException("置き場所の質問ではない: " + kind);
        };
    }

    /**
     * 答え終えた質問を、表現にする。設定から来た質問（置き場所、設定の詳しい質問、補足）の文言と、選択肢で答えた名前は、
     * 見る人の言語に引き直す。AI が作った質問（聞き手）は、作ったときの言語のまま。
     *
     * @param e 問い合わせ記録
     * @param lang 見る人の言語
     * @return 聞いた順の答え
     */
    List<InquiryView.EntryView> entries(InquiryEntity e, String lang) {
        IntakeCatalog catalog = locales.catalog(lang);
        IntakeTexts texts = locales.texts(lang);
        Placement chosen = IntakeEntries.placement(e.intake);
        IntakeCatalog.Cell cell = locales.planner(lang).cellFor(chosen);
        return e.intake.stream()
                .map(entry -> {
                    String text = entry.text();
                    String label = entry.answer().label();
                    switch (entry.kind()) {
                        case TYPE -> {
                            text = texts.type();
                            label = labelOrStored(entry, id -> catalog.typeLabel(InquiryType.valueOf(id)));
                        }
                        case SYSTEM -> {
                            text = texts.system();
                            label = labelOrStored(entry, catalog::systemLabel);
                        }
                        case FUNCTION -> {
                            text = texts.function();
                            label = labelOrStored(entry, id -> catalog.functionLabel(chosen.systemId(), id));
                        }
                        case NOTE -> text = IntakeTexts.isNoteRequired(entry.text()) ? texts.noteRequired() : texts.note();
                        case DETAIL -> {
                            IntakeQuestion configured = cell == null
                                    ? null
                                    : cell.questions().stream()
                                            .filter(q -> q.id().equals(entry.questionId()))
                                            .findFirst()
                                            .orElse(null);
                            if (configured != null) {
                                text = configured.text();
                                String optionId = entry.answer().optionId();
                                label = configured.options().stream()
                                        .filter(o -> o.id().equals(optionId))
                                        .map(Choice::label)
                                        .findFirst()
                                        .orElse(label);
                            }
                        }
                    }
                    return new InquiryView.EntryView(
                            entry.questionId(),
                            entry.kind(),
                            text,
                            new InquiryView.EntryAnswerView(
                                    entry.answer().kind(), entry.answer().optionId(), label, entry.answer().text()));
                })
                .toList();
    }

    /** 選んだ（または読み取った）値の名前を、見る人の言語で引く。値のない答え（わからない、など）は、保存した値のまま。 */
    private static String labelOrStored(IntakeEntry entry, java.util.function.Function<String, String> lookup) {
        return entry.answer().decides() ? lookup.apply(entry.answer().optionId()) : entry.answer().label();
    }

    /**
     * 整形後の問いを、表現にする。
     *
     * @param e 問い合わせ記録
     * @return 整形後の問い。未作成なら null
     */
    InquiryView.StructuredView structuredView(InquiryEntity e) {
        StructuredInquiry s = e.structured();
        return s == null
                ? null
                : new InquiryView.StructuredView(
                        s.summary(), s.targetSystem(), s.goal(), s.whatHappened(), s.expectedResult());
    }

    private InquiryView.IntakeView intakeView(InquiryEntity e, IntakeNext next, String lang) {
        if (e.awaiting != Awaiting.INTAKE_ANSWER) {
            return new InquiryView.IntakeView(entries(e, lang), null, true);
        }
        InquiryView.QuestionView question = null;
        if (next instanceof IntakeNext.Ask ask) {
            IntakeQuestion q = ask.question();
            question = new InquiryView.QuestionView(q.id(), q.kind(), q.text(), q.options(), q.allowsText(), q.canSkip());
        }
        return new InquiryView.IntakeView(entries(e, lang), question, next instanceof IntakeNext.Complete);
    }

    /**
     * 調査の状況を作る（担当者向け）。結果を誰に見せるかは、終わっているときだけ。
     *
     * @param e 問い合わせ記録
     * @return 調査の状況
     */
    OperatorDetail.InvestigationView investigationOf(InquiryEntity e) {
        return new OperatorDetail.InvestigationView(
                e.investigationStatus,
                e.investigationStatus == InvestigationStatus.DONE ? e.investigationAudience : null,
                e.investigationStartedAt,
                e.investigationFinishedAt,
                e.investigationUnconfirmedQuestion);
    }

    /**
     * AI の案を作る（担当者向け）。中身は、できた（{@code DONE}）ときだけ。
     *
     * @param e 問い合わせ記録
     * @return AI の案
     */
    OperatorDetail.AdviceView adviceOf(InquiryEntity e) {
        AdviceContent content = e.adviceStatus == AdviceStatus.DONE ? e.advice : null;
        return new OperatorDetail.AdviceView(
                e.adviceStatus,
                content == null ? null : content.judgment(),
                content == null ? null : content.procedure());
    }

    /**
     * 返事がないときの決まりの予定を作る。本人の返事（結果の確かめ、聞き返しへの返事）を待っている間だけ。
     *
     * @param e 問い合わせ記録
     * @return 予定。決まりの対象の状態でなければ null
     */
    InquiryView.NoReplyView noReplyOf(InquiryEntity e) {
        Ending endsAs = InquiryStateMachine.timeEnding(e.awaiting);
        if (endsAs == null || e.awaitingSince == null) {
            return null;
        }
        NoReplyRule.Schedule schedule =
                NoReplyRule.scheduleOf(e.awaitingSince, e.remindedAt, properties.rules().periods());
        return new InquiryView.NoReplyView(endsAs, schedule.remindAt(), e.remindedAt, schedule.closesAt());
    }

    /**
     * 担当者に初めて渡したときのイベントを作る。Slack 用の既定の言語の一覧の1件と、接続ごとの言語の一覧の1件を持つ。
     *
     * @param e 問い合わせ記録
     * @return イベント
     */
    InquiryFiledEvent filedEvent(InquiryEntity e) {
        Map<String, OperatorItem> items = itemsByLanguage(e);
        return new InquiryFiledEvent(items.get(locales.defaultLanguage()), items);
    }

    /**
     * 担当者に渡した後の更新のイベントを作る。
     *
     * @param e 問い合わせ記録
     * @return イベント
     */
    InquiryUpdatedEvent updatedEvent(InquiryEntity e) {
        Map<String, OperatorItem> items = itemsByLanguage(e);
        return new InquiryUpdatedEvent(items.get(locales.defaultLanguage()), items);
    }

    private Map<String, OperatorItem> itemsByLanguage(InquiryEntity e) {
        Map<String, OperatorItem> items = new LinkedHashMap<>();
        for (String language : locales.resolver().available()) {
            items.put(language, toItem(e, language));
        }
        return items;
    }

    /** いま処理しているリクエストの言語。なければ（リクエストの外から呼ばれた場合）既定の言語。 */
    private String viewerOf(InquiryEntity e) {
        return e.viewerLanguage != null ? e.viewerLanguage : locales.defaultLanguage();
    }

    private static OperatorItem.Ref ref(InquiryView.PlacedView placed) {
        return placed == null ? null : new OperatorItem.Ref(placed.id(), placed.label());
    }

    /**
     * 本人向けの技術メモから、根拠を確かめられなかったこと（unconfirmed）を落とす。これは担当者にだけ見せる内容で、
     * 画面で隠すのではなく、本人向けの応答に API が含めない。保存は変えず、オペレータ向けの詳細には残る。
     * 本人向けの応答（InquiryView と進捗ストリームの done）はすべてこの view から作るため、ここ1か所で落とす。
     */
    private static TechnicalMemo forAsker(TechnicalMemo memo) {
        return memo == null ? null : new TechnicalMemo(memo.findings(), List.of(), memo.notFound(), memo.nextChecks());
    }
}
