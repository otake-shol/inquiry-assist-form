package dev.inquiryassist.inquiry;

import dev.inquiryassist.core.Placement;
import dev.inquiryassist.core.lifecycle.Awaiting;
import dev.inquiryassist.core.lifecycle.InquiryStateMachine;
import dev.inquiryassist.core.lifecycle.Transition;
import java.time.Instant;
import java.util.UUID;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.stereotype.Service;

/**
 * 決定の記録（契約書「仕様の持ち主への相談」）。仕様の持ち主が決める場合（{@link OwnerService}）と、担当者が代わりに記録する場合
 * （{@link OperatorService} の操作）の両方が、ここを通る。先例の作成と、問い合わせの状態の変更は、同じトランザクションで行う
 * （片方だけが残らない）。
 *
 * <ul>
 *   <li>持ち主が決める: 相談している間（{@code CONSULTATION}）だけ。先例を作り（{@code recordedBy: OWNER}、確かめられている）、
 *       相談を {@code DECIDED} にして、担当者の番に戻し、担当者の側を未読に戻す（履歴の {@code DECIDED} の起こした人は持ち主）。
 *   <li>担当者が記録する: 担当者の番（{@code HANDLING}）か、相談している間。先例を作る（{@code recordedBy: OPERATOR}、確かめられていない）。
 *       担当者の番なら、状態を変えず、履歴にも入れない。相談している間なら、相談を {@code DECIDED} にして担当者の番に戻す
 *       （履歴の {@code DECIDED} の起こした人は担当者。担当者自身の操作なので、未読に戻さない）。
 * </ul>
 */
@Service
public class DecisionService {

    private static final int SITUATION_FALLBACK_LENGTH = 200;

    private final PrecedentRepository precedents;
    private final OperatorService operators;
    private final InquiryCommitter committer;

    /**
     * @param precedents 先例の保存先
     * @param operators 全内容の組み立て
     * @param committer 決定の保存（先例と同じトランザクション）と、確定後のイベント
     */
    DecisionService(PrecedentRepository precedents, OperatorService operators, InquiryCommitter committer) {
        this.precedents = precedents;
        this.operators = operators;
        this.committer = committer;
    }

    /**
     * 担当者が、仕様の持ち主の代わりに決定を記録する。担当者の番か、相談している間だけ。
     *
     * @param id 問い合わせの識別子
     * @param kind 決定の種類
     * @param decision 決定の内容
     * @param reason 理由
     * @param situation 状況。省くと既定
     * @param decidedBy 決めた人の名前
     * @param lang 見る人の言語
     * @return 記録後の全内容
     * @throws IllegalArgumentException 入力が不正な場合
     * @throws InquiryNotFoundException 存在しない、または担当者に渡したことがない場合
     * @throws dev.inquiryassist.core.lifecycle.IllegalTransitionException 担当者の番でも、相談している間でもない場合
     */
    public OperatorDetail recordByOperator(
            UUID id, DecisionKind kind, String decision, String reason, String situation, String decidedBy, String lang) {
        return decide(id, RecordedBy.OPERATOR, kind, decision, reason, situation, decidedBy, lang);
    }

    /**
     * 決定を記録する。
     *
     * @param id 問い合わせの識別子
     * @param by 記録した人（{@code OWNER} は持ち主が決める場合、{@code OPERATOR} は担当者が代わりに記録する場合）
     * @param kind 決定の種類
     * @param decision 決定の内容。前後の空白を除いて1〜4000文字
     * @param reason 理由。前後の空白を除いて1〜4000文字
     * @param situation どんな状況についての決定か。省くと、言い直しの文章。なければ、本人が書いた文章の先頭200文字。それもなければ、
     *     決定を待っている相談の文章。それもなければ、決定の内容
     * @param decidedBy 決めた人の名前。前後の空白を除いて1〜100文字
     * @param lang 見る人の言語（先例の {@code language}）
     * @return 記録後の全内容
     * @throws IllegalArgumentException 入力が不正な場合
     * @throws InquiryNotFoundException 存在しない場合。持ち主が決める場合は、相談したことがない場合も
     * @throws dev.inquiryassist.core.lifecycle.IllegalTransitionException 実行できない状態の場合
     * @throws InvalidStateException 他の操作と競合した場合
     */
    OperatorDetail decide(
            UUID id,
            RecordedBy by,
            DecisionKind kind,
            String decision,
            String reason,
            String situation,
            String decidedBy,
            String lang) {
        String decisionText = required(decision, 4000, "decision");
        String reasonText = required(reason, 4000, "reason");
        String decidedByText = required(decidedBy, 100, "decidedBy");
        String situationText = situation == null ? null : required(situation, 4000, "situation");
        InquiryEntity saved;
        try {
            saved = committer.update(id, inquiry -> {
                if (by == RecordedBy.OWNER && inquiry.consultations.isEmpty()) {
                    throw new InquiryNotFoundException(id);
                }
                if (by == RecordedBy.OPERATOR && inquiry.handedOverAt == null) {
                    // 担当者に渡したことのない問い合わせは、担当者向けの操作では見えない
                    throw new InquiryNotFoundException(id);
                }
                Instant now = Instant.now();
                Consultation open = inquiry.openConsultation();
                boolean consulting = inquiry.awaiting == Awaiting.CONSULTATION;
                Transition transition = null;
                if (by == RecordedBy.OWNER) {
                    transition = InquiryStateMachine.decided(inquiry.state());
                } else if (consulting) {
                    transition = InquiryStateMachine.decidedByOperator(inquiry.state());
                } else {
                    // 担当者の番のときだけ。状態は変えない
                    InquiryStateMachine.require(inquiry.state(), Awaiting.HANDLING, Awaiting.CONSULTATION);
                }
                if (consulting && open == null) {
                    throw new InvalidStateException("決定を待っている相談がありません");
                }
                // 先例のマスは、決めた時点の置き場所。後から問い合わせを置き直しても変えない
                Placement placement = inquiry.placement();
                PrecedentEntity precedent = new PrecedentEntity(
                        inquiry.id,
                        placement.type(),
                        placement.systemId(),
                        placement.functionId(),
                        situationText != null ? situationText : defaultSituation(inquiry, open, decisionText),
                        kind,
                        decisionText,
                        reasonText,
                        decidedByText,
                        by,
                        lang,
                        now);
                precedents.save(precedent);
                if (transition == null) {
                    return new InquiryCommitter.Update(null, InquiryCommitter.Publish.NONE);
                }
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

    /** 状況の既定。言い直しの文章。なければ、本人が書いた文章の先頭200文字。それもなければ、相談の文章（なければ、決定の内容）。 */
    private static String defaultSituation(InquiryEntity inquiry, Consultation open, String decision) {
        if (inquiry.structured() != null && !inquiry.structured().summary().isBlank()) {
            return inquiry.structured().summary();
        }
        String utterance = inquiry.utterance();
        if (utterance != null && !utterance.isBlank()) {
            return utterance.substring(0, Math.min(utterance.length(), SITUATION_FALLBACK_LENGTH));
        }
        return open != null ? open.question() : decision;
    }

    private static String required(String value, int max, String name) {
        String trimmed = value == null ? "" : value.strip();
        if (trimmed.isEmpty() || trimmed.length() > max) {
            throw new IllegalArgumentException(name + " は前後の空白を除いて1〜" + max + "文字");
        }
        return trimmed;
    }
}
