package dev.inquiryassist.inquiry;

import dev.inquiryassist.core.Citation;
import dev.inquiryassist.core.InquiryType;
import dev.inquiryassist.core.Placement;
import dev.inquiryassist.core.StructuredInquiry;
import dev.inquiryassist.core.TechnicalMemo;
import dev.inquiryassist.core.intake.IntakeCatalog;
import dev.inquiryassist.core.intake.IntakeEntries;
import dev.inquiryassist.core.intake.IntakeEntry;
import dev.inquiryassist.core.intake.IntakeNext;
import dev.inquiryassist.core.intake.IntakePlanner;
import dev.inquiryassist.core.intake.IntakeQuestion;
import dev.inquiryassist.core.intake.QuestionKind;
import dev.inquiryassist.inquiry.InquiryEntity.StoredCitation;
import java.util.ArrayList;
import java.util.List;
import org.springframework.stereotype.Component;

/**
 * 問い合わせ記録から、本人向けの {@link InquiryView}、担当者向けの一覧と詳細の項目を作る。
 *
 * <p>置き場所の名前は、記録に保存した値ではなく、その時点の設定から引く（設定の名前の変更に追従し、
 * 言語ごとの名前に切り替える段階でも、ここだけを直せばよい）。設定にないものは識別子を名前にする。
 */
@Component
class InquiryPresenter {

    private static final int SUMMARY_FALLBACK_LENGTH = 80;
    private static final String EMPTY_SUMMARY = "（内容なし）";

    private final IntakeCatalog catalog;
    private final IntakePlanner planner;
    private final InquiryProperties properties;
    private final SkillImplementations implementations;

    /**
     * @param catalog 種類、システム、機能の名前
     * @param planner 次の質問を決める処理
     * @param properties 実行記録の有効・無効の設定
     * @param implementations スキルごとの実装
     */
    InquiryPresenter(
            IntakeCatalog catalog,
            IntakePlanner planner,
            InquiryProperties properties,
            SkillImplementations implementations) {
        this.catalog = catalog;
        this.planner = planner;
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
        IntakeNext next = e.step == InquiryStep.INTAKE ? planner.next(e.snapshot()) : null;
        InquiryView.AnswerView answer = e.answerGrounded == null
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
                e.step,
                e.outcome,
                e.urgency,
                placed(e, QuestionKind.TYPE),
                placed(e, QuestionKind.SYSTEM),
                placed(e, QuestionKind.FUNCTION),
                e.utterance(),
                intakeView(e, next),
                e.revisionsLeft,
                structuredView(e),
                answer,
                e.canSendNow(next),
                e.sentImmediately,
                e.handlingStatus,
                MessageBodies.viewsOf(e),
                TraceViews.of(e, properties, implementations),
                e.mock);
    }

    /**
     * 担当者向けの一覧の1件を作る。
     *
     * @param e 問い合わせ記録
     * @return 一覧の1件
     */
    OperatorItem toItem(InquiryEntity e) {
        return new OperatorItem(
                e.id,
                e.filedAt,
                ref(placed(e, QuestionKind.TYPE)),
                ref(placed(e, QuestionKind.SYSTEM)),
                ref(placed(e, QuestionKind.FUNCTION)),
                e.urgency,
                summaryOf(e),
                e.sentImmediately,
                e.readAt != null,
                e.handlingStatus,
                e.messages.size());
    }

    /**
     * 一覧の要約。言い直しの文章、なければ本人が書いた文章の先頭80文字、それもなければ置き場所の名前
     * （種類、システム、機能を「・」でつないだもの）。本人向けの一覧と共有する。
     *
     * @param e 問い合わせ記録
     * @return 要約
     */
    String summaryOf(InquiryEntity e) {
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
            InquiryView.PlacedView placed = placed(e, kind);
            if (placed != null) {
                names.add(placed.label());
            }
        }
        return names.isEmpty() ? EMPTY_SUMMARY : String.join("・", names);
    }

    /**
     * 置き場所の1項目を、名前と決めた人つきで返す。
     *
     * @param e 問い合わせ記録
     * @param kind {@code TYPE}、{@code SYSTEM}、{@code FUNCTION} のいずれか
     * @return 決まっていなければ null
     */
    InquiryView.PlacedView placed(InquiryEntity e, QuestionKind kind) {
        IntakeEntry entry = IntakeEntries.find(e.intake, kind);
        if (entry == null || !entry.answer().decides()) {
            return null;
        }
        String id = entry.answer().optionId();
        Placement placement = e.placement();
        String label = switch (kind) {
            case TYPE -> catalog.typeLabel(InquiryType.valueOf(id));
            case SYSTEM -> catalog.systemLabel(id);
            case FUNCTION -> catalog.functionLabel(placement.systemId(), id);
            default -> throw new IllegalArgumentException("置き場所の質問ではない: " + kind);
        };
        return new InquiryView.PlacedView(id, label, IntakeEntries.placedBy(e.intake, kind));
    }

    /**
     * 答え終えた質問を、表現にする。
     *
     * @param e 問い合わせ記録
     * @return 聞いた順の答え
     */
    List<InquiryView.EntryView> entries(InquiryEntity e) {
        return e.intake.stream()
                .map(entry -> new InquiryView.EntryView(
                        entry.questionId(),
                        entry.kind(),
                        entry.text(),
                        new InquiryView.EntryAnswerView(
                                entry.answer().kind(),
                                entry.answer().optionId(),
                                entry.answer().label(),
                                entry.answer().text())))
                .toList();
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

    private InquiryView.IntakeView intakeView(InquiryEntity e, IntakeNext next) {
        if (e.step != InquiryStep.INTAKE) {
            return new InquiryView.IntakeView(entries(e), null, true);
        }
        InquiryView.QuestionView question = null;
        if (next instanceof IntakeNext.Ask ask) {
            IntakeQuestion q = ask.question();
            question = new InquiryView.QuestionView(q.id(), q.kind(), q.text(), q.options(), q.allowsText(), q.canSkip());
        }
        return new InquiryView.IntakeView(entries(e), question, next instanceof IntakeNext.Complete);
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
