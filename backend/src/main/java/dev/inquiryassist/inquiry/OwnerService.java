package dev.inquiryassist.inquiry;

import dev.inquiryassist.core.Placement;
import dev.inquiryassist.core.intake.QuestionKind;
import dev.inquiryassist.core.lifecycle.Awaiting;
import dev.inquiryassist.core.lifecycle.InquiryStateMachine;
import dev.inquiryassist.core.lifecycle.Transition;
import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.stereotype.Service;

/**
 * 仕様の持ち主向けの操作（契約書「仕様の持ち主への相談」）。決定を待っている相談の一覧、相談したことのある問い合わせの全内容、決定。
 * 決定は、先例の作成と、問い合わせの状態の変更を、同じトランザクションで行う（片方だけが残らない）。
 */
@Service
public class OwnerService {

    private static final int SITUATION_FALLBACK_LENGTH = 200;

    private final InquiryRepository repository;
    private final PrecedentRepository precedents;
    private final InquiryPresenter presenter;
    private final OperatorService operators;
    private final DecisionService decisions;

    /**
     * @param repository 問い合わせ記録の保存先
     * @param precedents 先例の保存先
     * @param presenter 一覧の項目を作る
     * @param operators 全内容の組み立て（担当者向けと同じ形を返す）
     * @param decisions 決定の記録（先例の作成と状態の変更を、同じトランザクションで行う）
     */
    OwnerService(
            InquiryRepository repository,
            PrecedentRepository precedents,
            InquiryPresenter presenter,
            OperatorService operators,
            DecisionService decisions) {
        this.repository = repository;
        this.precedents = precedents;
        this.presenter = presenter;
        this.operators = operators;
        this.decisions = decisions;
    }

    /**
     * 決定を待っている相談を、古い順に返す。
     *
     * @param lang 見る人の言語
     * @return 一覧
     */
    public OwnerList list(String lang) {
        List<OwnerList.Item> items = repository.findByAwaiting(Awaiting.CONSULTATION).stream()
                .filter(e -> e.openConsultation() != null)
                .map(e -> {
                    Consultation c = e.openConsultation();
                    return new OwnerList.Item(
                            e.id,
                            c.id(),
                            c.askedAt(),
                            c.askedBy(),
                            c.to(),
                            c.question(),
                            presenter.summaryOf(e, lang),
                            ref(presenter.placed(e, QuestionKind.TYPE, lang)),
                            ref(presenter.placed(e, QuestionKind.SYSTEM, lang)),
                            ref(presenter.placed(e, QuestionKind.FUNCTION, lang)),
                            e.urgency,
                            e.language);
                })
                .sorted(Comparator.comparing(OwnerList.Item::askedAt))
                .toList();
        return new OwnerList(items);
    }

    /**
     * 相談したことのある問い合わせの全内容を返す（担当者向けと同じ形）。担当者の側の既読は変えない。
     *
     * @param id 問い合わせの識別子
     * @param lang 見る人の言語
     * @return 全内容
     * @throws InquiryNotFoundException 存在しない、または相談したことがない場合
     */
    public OperatorDetail get(UUID id, String lang) {
        InquiryEntity inquiry = repository
                .findById(id)
                .filter(e -> !e.consultations.isEmpty())
                .orElseThrow(() -> new InquiryNotFoundException(id));
        return operators.toDetail(inquiry, lang);
    }

    /**
     * 決める。先例を1つ作り、相談を {@code DECIDED} にし、担当者の番に戻す（渡した理由は {@code DECIDED}）。担当者の側を未読に戻し、
     * 担当者のストリームに更新を流す。
     *
     * @param id 問い合わせの識別子
     * @param kind 決定の種類
     * @param decision 決定の内容。前後の空白を除いて1〜4000文字
     * @param reason 理由。前後の空白を除いて1〜4000文字
     * @param situation どんな状況についての決定か。省くと、言い直しの文章。なければ、本人が書いた文章の先頭200文字。それもなければ、相談の文章
     * @param decidedBy 決めた人の名前。前後の空白を除いて1〜100文字
     * @param lang 見る人の言語（先例の {@code language}）
     * @return 決定後の全内容
     * @throws IllegalArgumentException 入力が不正な場合
     * @throws InquiryNotFoundException 存在しない、または相談したことがない場合
     * @throws dev.inquiryassist.core.lifecycle.IllegalTransitionException 持ち主の決定を待っている状態でない場合
     * @throws InvalidStateException 他の操作と競合した場合
     */
    public OperatorDetail decide(
            UUID id, DecisionKind kind, String decision, String reason, String situation, String decidedBy, String lang) {
        return decisions.decide(id, RecordedBy.OWNER, kind, decision, reason, situation, decidedBy, lang);
    }

    private static OperatorItem.Ref ref(InquiryView.PlacedView placed) {
        return placed == null ? null : new OperatorItem.Ref(placed.id(), placed.label());
    }
}
