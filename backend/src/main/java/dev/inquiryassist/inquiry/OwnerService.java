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
    private final InquiryCommitter committer;

    /**
     * @param repository 問い合わせ記録の保存先
     * @param precedents 先例の保存先
     * @param presenter 一覧の項目を作る
     * @param operators 全内容の組み立て（担当者向けと同じ形を返す）
     * @param committer 決定の保存（先例と同じトランザクション）と、確定後のイベント
     */
    OwnerService(
            InquiryRepository repository,
            PrecedentRepository precedents,
            InquiryPresenter presenter,
            OperatorService operators,
            InquiryCommitter committer) {
        this.repository = repository;
        this.precedents = precedents;
        this.presenter = presenter;
        this.operators = operators;
        this.committer = committer;
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
        String decisionText = required(decision, 4000, "decision");
        String reasonText = required(reason, 4000, "reason");
        String decidedByText = required(decidedBy, 100, "decidedBy");
        String situationText = situation == null ? null : required(situation, 4000, "situation");
        InquiryEntity saved;
        try {
            saved = committer.update(id, inquiry -> {
                if (inquiry.consultations.isEmpty()) {
                    throw new InquiryNotFoundException(id);
                }
                Transition transition = InquiryStateMachine.decided(inquiry.state());
                Consultation open = inquiry.openConsultation();
                if (open == null) {
                    throw new InvalidStateException("決定を待っている相談がありません");
                }
                Instant now = Instant.now();
                // 先例のマスは、決めた時点の置き場所。後から問い合わせを置き直しても変えない
                Placement placement = inquiry.placement();
                PrecedentEntity precedent = new PrecedentEntity(
                        inquiry.id,
                        placement.type(),
                        placement.systemId(),
                        placement.functionId(),
                        situationText != null ? situationText : defaultSituation(inquiry, open),
                        kind,
                        decisionText,
                        reasonText,
                        decidedByText,
                        lang,
                        now);
                precedents.save(precedent);
                inquiry.consultations = inquiry.consultations.stream()
                        .map(c -> c.id().equals(open.id()) ? c.decided(now, precedent.id) : c)
                        .toList();
                inquiry.apply(transition, now);
                return new InquiryCommitter.Update(null, InquiryCommitter.Publish.UPDATED_IF_HANDED_OVER);
            });
        } catch (OptimisticLockingFailureException e) {
            throw new InvalidStateException("他の操作と競合しました。最新の状態を取得し直してください");
        }
        if (saved == null) {
            throw new InquiryNotFoundException(id);
        }
        return operators.toDetail(saved, lang);
    }

    /** 状況の既定。言い直しの文章。なければ、本人が書いた文章の先頭200文字。それもなければ、相談の文章。 */
    private static String defaultSituation(InquiryEntity inquiry, Consultation open) {
        if (inquiry.structured() != null && !inquiry.structured().summary().isBlank()) {
            return inquiry.structured().summary();
        }
        String utterance = inquiry.utterance();
        if (utterance != null && !utterance.isBlank()) {
            return utterance.substring(0, Math.min(utterance.length(), SITUATION_FALLBACK_LENGTH));
        }
        return open.question();
    }

    private static String required(String value, int max, String name) {
        String trimmed = value == null ? "" : value.strip();
        if (trimmed.isEmpty() || trimmed.length() > max) {
            throw new IllegalArgumentException(name + " は前後の空白を除いて1〜" + max + "文字");
        }
        return trimmed;
    }

    private static OperatorItem.Ref ref(InquiryView.PlacedView placed) {
        return placed == null ? null : new OperatorItem.Ref(placed.id(), placed.label());
    }
}
