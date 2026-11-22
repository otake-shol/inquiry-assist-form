package dev.inquiryassist.core.lifecycle;

import dev.inquiryassist.core.InquiryType;
import dev.inquiryassist.core.Placement;
import java.util.Map;

/**
 * 問い合わせの状態の遷移。どの操作が、どの状態のときにでき、その後どの状態になるかを決める（契約書「状態」）。
 *
 * <p>状態を持たず、Spring にも HTTP にも依存しない。操作ごとに、いまの状態と文脈から、遷移の結果（{@link Transition}）を返す。
 * いまの状態で実行できない操作は {@link IllegalTransitionException} を投げる。保存、通知、スキルの呼び出しは呼び出し側が行う。
 *
 * <p>「担当者に渡す」は問い合わせを終わらせず、番を担当者にするだけ。終わらせるのは、本人の解決の伝え、担当者の見送り、
 * 本人の取り下げの3つだけ。
 */
public final class InquiryStateMachine {

    /**
     * 遷移の判断に使う、状態以外の情報。
     *
     * @param type 種類（置き直しを含む、いまの種類）。決まっていなければ {@code null}
     * @param handedOver 担当者に渡したことがあるか
     * @param phaseBeforeClose 終わる前の区間。終わっていなければ {@code null}
     * @param intakeComplete 入口の質問が終わっているか
     * @param revisionsLeft 言い直しを作り直せる残り回数
     */
    public record Context(
            InquiryType type, boolean handedOver, Phase phaseBeforeClose, boolean intakeComplete, int revisionsLeft) {}

    private InquiryStateMachine() {}

    /**
     * 担当者に渡したときの区間。知識の不足のまま担当者が答える種類は {@code KNOW}、意図どおりか、受けるか、
     * 採るかを決める種類は {@code DECIDE}。種類が決まっていなければ {@code KNOW}（{@code HOW_IT_WORKS} と同じ経路）。
     *
     * @param type 種類。決まっていなければ {@code null}
     * @return 区間
     */
    public static Phase handOverPhase(InquiryType type) {
        InquiryType route = InquiryType.routeOf(type);
        return route == InquiryType.HOW_TO || route == InquiryType.HOW_IT_WORKS ? Phase.KNOW : Phase.DECIDE;
    }

    /**
     * 待っていることが、指定したもののどれかであることを確かめる。
     *
     * @param state いまの状態
     * @param allowed 実行できる待っていること
     * @throws IllegalTransitionException どれでもない場合
     */
    public static void require(InquiryState state, Awaiting... allowed) {
        for (Awaiting a : allowed) {
            if (state.awaiting() == a) {
                return;
            }
        }
        throw new IllegalTransitionException("この操作はいまの状態では実行できません（現在: " + describe(state) + "）");
    }

    private static String describe(InquiryState s) {
        return s.closed() ? "CLOSED/" + s.ending() : s.phase() + "/" + s.awaiting();
    }

    /**
     * 答えを待たずに担当者へ送れるかを返す。問いを確かめた後に限る（入力が足りないまま担当者に渡すと、聞き返しが増えるだけになるため）。
     * ただし、AI の失敗で先へ進めない場合と、言い直しを作り直せる回数を使い切った場合は、こちらの側の事情なので本人を止めない。
     *
     * @param state いまの状態
     * @param ctx 文脈
     * @return 送れる場合は true
     */
    public static boolean canSendNow(InquiryState state, Context ctx) {
        if (state.awaiting() == null) {
            return false;
        }
        return switch (state.awaiting()) {
            case INVESTIGATION -> true;
            // 担当者が伝えた結果を確かめている間は、すでに渡している
            case RESULT_CONFIRMATION -> !ctx.handedOver();
            case INTAKE_ANSWER -> ctx.intakeComplete();
            case QUESTION_CONFIRMATION -> ctx.revisionsLeft() == 0;
            case HANDLING, ASKER_REPLY -> false;
        };
    }

    /**
     * @return 問い合わせができたときの遷移
     */
    public static Transition created() {
        return new Transition(InquiryState.START, HistoryEvent.CREATED, Actor.ASKER, false, null);
    }

    /**
     * 入口の質問を終え、問いを組み立てた。
     *
     * @param state いまの状態（{@code INTAKE_ANSWER}）
     * @return 遷移
     */
    public static Transition composed(InquiryState state) {
        require(state, Awaiting.INTAKE_ANSWER);
        return move(state, new InquiryState(Phase.INTAKE, Turn.ASKER, Awaiting.QUESTION_CONFIRMATION, null),
                HistoryEvent.QUESTION_COMPOSED, Actor.AI);
    }

    /**
     * 問いの確認から、入口の質問へ戻る。
     *
     * @param state いまの状態（{@code QUESTION_CONFIRMATION}）
     * @return 遷移
     */
    public static Transition reopenedIntake(InquiryState state) {
        require(state, Awaiting.QUESTION_CONFIRMATION);
        return move(state, InquiryState.START, HistoryEvent.REOPENED_INTAKE, Actor.ASKER);
    }

    /**
     * 本人が問いを確かめた。調べない種類は担当者に渡し、それ以外は調査に進む。
     *
     * @param state いまの状態（{@code QUESTION_CONFIRMATION}）
     * @param ctx 文脈
     * @return 遷移
     */
    public static Transition confirmed(InquiryState state, Context ctx) {
        require(state, Awaiting.QUESTION_CONFIRMATION);
        if (ctx.type() != null && ctx.type().filesOnConfirm()) {
            return handOver(state, ctx, HistoryEvent.HANDED_OVER, Actor.ASKER);
        }
        return move(state, new InquiryState(Phase.KNOW, Turn.AI, Awaiting.INVESTIGATION, null),
                HistoryEvent.QUESTION_CONFIRMED, Actor.ASKER);
    }

    /**
     * AI の答えが出た。種類ごとの経路に従い、担当者に渡すか、本人が確かめる。
     *
     * @param state いまの状態（{@code INVESTIGATION}）
     * @param ctx 文脈
     * @param grounded 照合できた根拠があるか
     * @param answered 確かめられた事実が問いに答えているか
     * @return 遷移
     */
    public static Transition answeredByAi(InquiryState state, Context ctx, boolean grounded, boolean answered) {
        require(state, Awaiting.INVESTIGATION);
        if (InquiryType.routeOf(ctx.type()).filesAfterAnswer(grounded, answered)) {
            return handOver(state, ctx, HistoryEvent.HANDED_OVER, Actor.AI);
        }
        return move(state, new InquiryState(Phase.KNOW, Turn.ASKER, Awaiting.RESULT_CONFIRMATION, null),
                HistoryEvent.ANSWERED_BY_AI, Actor.AI);
    }

    /**
     * 業務が止まっているため、入口を終えた時点（または言い直しの確認中）に、担当者に渡す。
     *
     * @param state いまの状態（{@code INTAKE_ANSWER} か {@code QUESTION_CONFIRMATION}）
     * @param ctx 文脈
     * @return 遷移
     */
    public static Transition handedOverBecauseBlocked(InquiryState state, Context ctx) {
        require(state, Awaiting.INTAKE_ANSWER, Awaiting.QUESTION_CONFIRMATION);
        return handOver(state, ctx, HistoryEvent.HANDED_OVER, Actor.ASKER);
    }

    /**
     * 本人が、答えを待たずに担当者へ送る。
     *
     * @param state いまの状態
     * @param ctx 文脈
     * @return 遷移
     * @throws IllegalTransitionException 送れない状態の場合
     */
    public static Transition sentNow(InquiryState state, Context ctx) {
        if (!canSendNow(state, ctx)) {
            throw new IllegalTransitionException("いまは担当者に送れません（現在: " + describe(state) + "）");
        }
        return handOver(state, ctx, HistoryEvent.HANDED_OVER, Actor.ASKER);
    }

    /**
     * 本人が結果を確かめた。解決したなら終わり、解決していなければ担当者に渡す（区間は、AI の答えの後なら種類で決め、
     * 担当者の結果の後なら変えない）。
     *
     * @param state いまの状態（{@code RESULT_CONFIRMATION}）
     * @param ctx 文脈
     * @param resolved 解決したか
     * @return 遷移
     */
    public static Transition resolved(InquiryState state, Context ctx, boolean resolved) {
        require(state, Awaiting.RESULT_CONFIRMATION);
        if (resolved) {
            return move(state, new InquiryState(Phase.CLOSED, null, null, Ending.RESOLVED), HistoryEvent.RESOLVED, Actor.ASKER);
        }
        return handOver(state, ctx, HistoryEvent.HANDED_OVER, Actor.ASKER);
    }

    /**
     * 担当者が、本人に聞き返す、または結果を伝える。区間は変えない。
     *
     * @param state いまの状態（{@code HANDLING}）
     * @param kind 発言の種類
     * @return 遷移
     */
    public static Transition operatorReplied(InquiryState state, ReplyKind kind) {
        require(state, Awaiting.HANDLING);
        return switch (kind) {
            case ASK -> move(state, new InquiryState(state.phase(), Turn.ASKER, Awaiting.ASKER_REPLY, null),
                    HistoryEvent.ASKED_BACK, Actor.OPERATOR);
            case RESULT -> move(state, new InquiryState(state.phase(), Turn.ASKER, Awaiting.RESULT_CONFIRMATION, null),
                    HistoryEvent.RESULT_DELIVERED, Actor.OPERATOR);
        };
    }

    /**
     * 担当者が見送る。
     *
     * @param state いまの状態（{@code HANDLING}）
     * @return 遷移
     */
    public static Transition declined(InquiryState state) {
        require(state, Awaiting.HANDLING);
        return move(state, new InquiryState(Phase.CLOSED, null, null, Ending.DECLINED), HistoryEvent.DECLINED, Actor.OPERATOR);
    }

    /**
     * 担当者が区間を変える。番は変えない。同じ区間なら、何も変わらない（出来事は {@code null}）。
     *
     * @param state いまの状態（{@code HANDLING}）
     * @param phase 変更後の区間（{@code KNOW}、{@code DECIDE}、{@code ACT} のどれか）
     * @return 遷移
     * @throws IllegalArgumentException 区間が範囲外の場合
     */
    public static Transition phaseChanged(InquiryState state, Phase phase) {
        if (phase != Phase.KNOW && phase != Phase.DECIDE && phase != Phase.ACT) {
            throw new IllegalArgumentException("区間は KNOW、DECIDE、ACT のどれか");
        }
        require(state, Awaiting.HANDLING);
        if (state.phase() == phase) {
            return new Transition(state, null, Actor.OPERATOR, false, null);
        }
        return move(state, new InquiryState(phase, state.turn(), state.awaiting(), null),
                HistoryEvent.PHASE_CHANGED, Actor.OPERATOR);
    }

    /**
     * 担当者が置き直す。番は変えない。種類が変わった場合は、区間を新しい種類の「担当者に渡す」の決まりに合わせる。
     *
     * @param state いまの状態（終わっていない）
     * @param ctx 文脈（いまの種類を含む）
     * @param before 変える前の置き場所
     * @param after 変えた後の置き場所
     * @return 遷移
     */
    public static Transition placementChanged(InquiryState state, Context ctx, Placement before, Placement after) {
        if (state.closed()) {
            throw new IllegalTransitionException("終わった問い合わせは置き直せません");
        }
        boolean typeChanged = ctx.type() != after.type();
        Phase phase = typeChanged ? handOverPhase(after.type()) : state.phase();
        return new Transition(
                new InquiryState(phase, state.turn(), state.awaiting(), null),
                HistoryEvent.PLACEMENT_CHANGED,
                Actor.OPERATOR,
                false,
                Map.of("before", before, "after", after));
    }

    /**
     * 本人が追記する。担当者に渡したことのある、取り下げていない問い合わせだけ。追記したときの状態で、その後が決まる。
     *
     * @param state いまの状態
     * @param ctx 文脈
     * @return 遷移。担当者の番の間の追記は、状態が変わらない（出来事は {@code null}）
     * @throws IllegalTransitionException 担当者に渡したことがない、または取り下げた問い合わせの場合
     */
    public static Transition noted(InquiryState state, Context ctx) {
        if (!ctx.handedOver()) {
            throw new IllegalTransitionException("担当者に渡した問い合わせにだけ追記できます");
        }
        if (state.closed()) {
            if (state.ending() == Ending.WITHDRAWN) {
                throw new IllegalTransitionException("取り下げた問い合わせには追記できません");
            }
            Phase phase = ctx.phaseBeforeClose() != null ? ctx.phaseBeforeClose() : handOverPhase(ctx.type());
            return move(state, new InquiryState(phase, Turn.OPERATOR, Awaiting.HANDLING, null),
                    HistoryEvent.REOPENED, Actor.ASKER);
        }
        return switch (state.awaiting()) {
            case HANDLING -> new Transition(state, null, Actor.ASKER, false, null);
            case ASKER_REPLY -> move(state, new InquiryState(state.phase(), Turn.OPERATOR, Awaiting.HANDLING, null),
                    HistoryEvent.REPLIED, Actor.ASKER);
            // 担当者の結果に追記するのは、「解決していない」と答えたのと同じ
            case RESULT_CONFIRMATION -> handOver(state, ctx, HistoryEvent.HANDED_OVER, Actor.ASKER);
            default -> throw new IllegalTransitionException("この操作はいまの状態では実行できません（現在: " + describe(state) + "）");
        };
    }

    /**
     * 本人が取り下げる。終わっていない問い合わせで、どの時点でも有効。
     *
     * @param state いまの状態
     * @return 遷移
     */
    public static Transition withdrawn(InquiryState state) {
        if (state.closed()) {
            throw new IllegalTransitionException("終わった問い合わせは取り下げられません");
        }
        return move(state, new InquiryState(Phase.CLOSED, null, null, Ending.WITHDRAWN), HistoryEvent.WITHDRAWN, Actor.ASKER);
    }

    /** 番を担当者にする。担当者に渡したことがなければ種類で区間を決め、あれば、いまの区間のままにする。 */
    private static Transition handOver(InquiryState state, Context ctx, HistoryEvent event, Actor actor) {
        Phase phase = ctx.handedOver() && !state.closed() && state.phase() != Phase.INTAKE
                ? state.phase()
                : handOverPhase(ctx.type());
        return move(state, new InquiryState(phase, Turn.OPERATOR, Awaiting.HANDLING, null), event, actor);
    }

    private static Transition move(InquiryState from, InquiryState to, HistoryEvent event, Actor actor) {
        return new Transition(to, event, actor, to.turn() == Turn.OPERATOR && from.turn() != Turn.OPERATOR, null);
    }
}
