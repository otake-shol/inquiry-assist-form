package dev.inquiryassist.core.lifecycle;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.inquiryassist.core.InquiryType;
import dev.inquiryassist.core.Placement;
import dev.inquiryassist.core.lifecycle.InquiryStateMachine.Context;
import java.util.List;
import org.junit.jupiter.api.Test;

/** 状態の遷移（契約書「状態」）。どの操作が、どの状態のときにでき、その後どうなるかを、Spring なしで確かめる。 */
class InquiryStateMachineTest {

    private static final InquiryState INTAKE = InquiryState.START;
    private static final InquiryState CONFIRMING =
            new InquiryState(Phase.INTAKE, Turn.ASKER, Awaiting.QUESTION_CONFIRMATION, null);
    private static final InquiryState INVESTIGATING =
            new InquiryState(Phase.KNOW, Turn.AI, Awaiting.INVESTIGATION, null);
    private static final InquiryState RESULT = new InquiryState(Phase.KNOW, Turn.ASKER, Awaiting.RESULT_CONFIRMATION, null);

    private static InquiryState handling(Phase phase) {
        return new InquiryState(phase, Turn.OPERATOR, Awaiting.HANDLING, null);
    }

    private static InquiryState closed(Ending ending) {
        return new InquiryState(Phase.CLOSED, null, null, ending);
    }

    private static Context ctx(InquiryType type) {
        return new Context(type, false, null, false, 2);
    }

    private static Context handedOver(InquiryType type) {
        return new Context(type, true, null, false, 2);
    }

    // ---- 入口から AI の答えまで ----

    @Test
    void 入口の各時点の状態は契約書の表のとおり() {
        assertThat(InquiryStateMachine.created().to()).isEqualTo(new InquiryState(Phase.INTAKE, Turn.ASKER, Awaiting.INTAKE_ANSWER, null));
        assertThat(InquiryStateMachine.created().event()).isEqualTo(HistoryEvent.CREATED);
        Transition composed = InquiryStateMachine.composed(INTAKE);
        assertThat(composed.to()).isEqualTo(CONFIRMING);
        assertThat(composed.event()).isEqualTo(HistoryEvent.QUESTION_COMPOSED);
        assertThat(composed.actor()).isEqualTo(Actor.AI);
        assertThat(InquiryStateMachine.reopenedIntake(CONFIRMING).to()).isEqualTo(INTAKE);
    }

    @Test
    void 問いを確かめると調査に進み_変えてほしいだけは担当者に渡る() {
        for (InquiryType type : List.of(InquiryType.HOW_TO, InquiryType.HOW_IT_WORKS, InquiryType.LOOKS_WRONG, InquiryType.DO_FOR_ME)) {
            Transition t = InquiryStateMachine.confirmed(CONFIRMING, ctx(type));
            assertThat(t.to()).isEqualTo(INVESTIGATING);
            assertThat(t.event()).isEqualTo(HistoryEvent.QUESTION_CONFIRMED);
            assertThat(t.handsOver()).isFalse();
        }
        assertThat(InquiryStateMachine.confirmed(CONFIRMING, ctx(null)).to()).isEqualTo(INVESTIGATING);
        Transition change = InquiryStateMachine.confirmed(CONFIRMING, ctx(InquiryType.CHANGE_REQUEST));
        assertThat(change.to()).isEqualTo(handling(Phase.DECIDE));
        assertThat(change.handsOver()).isTrue();
    }

    @Test
    void AIの答えは_使い方と仕組みと種類なしは本人が確かめ_おかしいかもしれないは担当者に渡る() {
        for (InquiryType type : new InquiryType[] {InquiryType.HOW_TO, InquiryType.HOW_IT_WORKS, null}) {
            Transition t = InquiryStateMachine.answeredByAi(INVESTIGATING, ctx(type), true, true);
            assertThat(t.to()).isEqualTo(RESULT);
            assertThat(t.event()).isEqualTo(HistoryEvent.ANSWERED_BY_AI);
            // 根拠がなくても的外れでも、これらの種類は本人が確かめる
            assertThat(InquiryStateMachine.answeredByAi(INVESTIGATING, ctx(type), false, false).to()).isEqualTo(RESULT);
        }
        Transition wrong = InquiryStateMachine.answeredByAi(INVESTIGATING, ctx(InquiryType.LOOKS_WRONG), true, true);
        assertThat(wrong.to()).isEqualTo(handling(Phase.DECIDE));
        assertThat(wrong.event()).isEqualTo(HistoryEvent.HANDED_OVER);
        assertThat(wrong.actor()).isEqualTo(Actor.AI);
    }

    @Test
    void やってほしいは根拠があり問いに答えていれば本人が確かめ_そうでなければ担当者に渡る() {
        assertThat(InquiryStateMachine.answeredByAi(INVESTIGATING, ctx(InquiryType.DO_FOR_ME), true, true).to()).isEqualTo(RESULT);
        assertThat(InquiryStateMachine.answeredByAi(INVESTIGATING, ctx(InquiryType.DO_FOR_ME), true, false).to())
                .isEqualTo(handling(Phase.DECIDE));
        assertThat(InquiryStateMachine.answeredByAi(INVESTIGATING, ctx(InquiryType.DO_FOR_ME), false, false).to())
                .isEqualTo(handling(Phase.DECIDE));
    }

    @Test
    void 担当者に渡したときの区間は_使い方と仕組みと種類なしがKNOW_それ以外がDECIDE() {
        assertThat(InquiryStateMachine.handOverPhase(InquiryType.HOW_TO)).isEqualTo(Phase.KNOW);
        assertThat(InquiryStateMachine.handOverPhase(InquiryType.HOW_IT_WORKS)).isEqualTo(Phase.KNOW);
        assertThat(InquiryStateMachine.handOverPhase(null)).isEqualTo(Phase.KNOW);
        for (InquiryType type : List.of(InquiryType.LOOKS_WRONG, InquiryType.DO_FOR_ME, InquiryType.CHANGE_REQUEST)) {
            assertThat(InquiryStateMachine.handOverPhase(type)).isEqualTo(Phase.DECIDE);
        }
    }

    @Test
    void 担当者に渡しても終わらない() {
        Transition t = InquiryStateMachine.sentNow(INVESTIGATING, ctx(InquiryType.HOW_TO));

        assertThat(t.to().closed()).isFalse();
        assertThat(t.to().ending()).isNull();
        assertThat(t.to().turn()).isEqualTo(Turn.OPERATOR);
        assertThat(t.handsOver()).isTrue();
    }

    @Test
    void 業務が止まっているときは入口の途中でも言い直しの確認中でも担当者に渡る() {
        assertThat(InquiryStateMachine.handedOverBecauseBlocked(INTAKE, ctx(InquiryType.HOW_TO)).to())
                .isEqualTo(handling(Phase.KNOW));
        assertThat(InquiryStateMachine.handedOverBecauseBlocked(CONFIRMING, ctx(InquiryType.LOOKS_WRONG)).to())
                .isEqualTo(handling(Phase.DECIDE));
        assertThatThrownBy(() -> InquiryStateMachine.handedOverBecauseBlocked(INVESTIGATING, ctx(null)))
                .isInstanceOf(IllegalTransitionException.class);
    }

    // ---- 答えを待たずに送る ----

    @Test
    void 答えを待たずに送れるのは_調査中_AIの答えの確認中_入口を終えて組み立てに失敗_作り直しを使い切った場合だけ() {
        assertThat(InquiryStateMachine.canSendNow(INVESTIGATING, ctx(null))).isTrue();
        assertThat(InquiryStateMachine.canSendNow(RESULT, ctx(null))).isTrue();
        assertThat(InquiryStateMachine.canSendNow(INTAKE, new Context(null, false, null, true, 2))).isTrue();
        assertThat(InquiryStateMachine.canSendNow(INTAKE, new Context(null, false, null, false, 2))).isFalse();
        assertThat(InquiryStateMachine.canSendNow(CONFIRMING, new Context(null, false, null, false, 0))).isTrue();
        assertThat(InquiryStateMachine.canSendNow(CONFIRMING, new Context(null, false, null, false, 1))).isFalse();
    }

    @Test
    void 担当者の結果を確かめている間と担当者の番と終わった件は送れない() {
        assertThat(InquiryStateMachine.canSendNow(RESULT, handedOver(null))).isFalse();
        assertThat(InquiryStateMachine.canSendNow(handling(Phase.KNOW), handedOver(null))).isFalse();
        assertThat(InquiryStateMachine.canSendNow(
                        new InquiryState(Phase.DECIDE, Turn.ASKER, Awaiting.ASKER_REPLY, null), handedOver(null)))
                .isFalse();
        assertThat(InquiryStateMachine.canSendNow(closed(Ending.RESOLVED), ctx(null))).isFalse();
        assertThatThrownBy(() -> InquiryStateMachine.sentNow(handling(Phase.KNOW), handedOver(null)))
                .isInstanceOf(IllegalTransitionException.class);
    }

    // ---- 結果を確かめる ----

    @Test
    void 解決したと答えると終わり_解決していないと答えると担当者に渡る() {
        Transition resolved = InquiryStateMachine.resolved(RESULT, ctx(InquiryType.HOW_TO), true);
        assertThat(resolved.to()).isEqualTo(closed(Ending.RESOLVED));
        assertThat(resolved.event()).isEqualTo(HistoryEvent.RESOLVED);
        assertThat(resolved.handsOver()).isFalse();

        Transition notResolved = InquiryStateMachine.resolved(RESULT, ctx(InquiryType.HOW_TO), false);
        assertThat(notResolved.to()).isEqualTo(handling(Phase.KNOW));
        assertThat(notResolved.handsOver()).isTrue();
        // DO_FOR_ME で AI が手順を示した後に「解決していない」は、担当者に頼むこと
        assertThat(InquiryStateMachine.resolved(RESULT, ctx(InquiryType.DO_FOR_ME), false).to())
                .isEqualTo(handling(Phase.DECIDE));
    }

    @Test
    void 担当者の結果の後に解決していないと答えても_区間は変わらない() {
        InquiryState operatorResult = new InquiryState(Phase.ACT, Turn.ASKER, Awaiting.RESULT_CONFIRMATION, null);

        assertThat(InquiryStateMachine.resolved(operatorResult, handedOver(InquiryType.HOW_TO), false).to())
                .isEqualTo(handling(Phase.ACT));
        assertThat(InquiryStateMachine.resolved(operatorResult, handedOver(InquiryType.HOW_TO), true).to())
                .isEqualTo(closed(Ending.RESOLVED));
    }

    @Test
    void 結果を確かめている状態でなければ確かめられない() {
        for (InquiryState s : List.of(INTAKE, CONFIRMING, INVESTIGATING, handling(Phase.KNOW), closed(Ending.RESOLVED))) {
            assertThatThrownBy(() -> InquiryStateMachine.resolved(s, ctx(null), true))
                    .isInstanceOf(IllegalTransitionException.class);
        }
    }

    // ---- 担当者の操作 ----

    @Test
    void 聞き返すと本人の返事を待ち_結果を伝えると本人が確かめる_区間は変わらない() {
        Transition ask = InquiryStateMachine.operatorReplied(handling(Phase.ACT), ReplyKind.ASK);
        assertThat(ask.to()).isEqualTo(new InquiryState(Phase.ACT, Turn.ASKER, Awaiting.ASKER_REPLY, null));
        assertThat(ask.event()).isEqualTo(HistoryEvent.ASKED_BACK);
        assertThat(ask.actor()).isEqualTo(Actor.OPERATOR);
        assertThat(ask.handsOver()).isFalse();

        Transition result = InquiryStateMachine.operatorReplied(handling(Phase.DECIDE), ReplyKind.RESULT);
        assertThat(result.to()).isEqualTo(new InquiryState(Phase.DECIDE, Turn.ASKER, Awaiting.RESULT_CONFIRMATION, null));
        assertThat(result.event()).isEqualTo(HistoryEvent.RESULT_DELIVERED);
    }

    @Test
    void 担当者の番でなければ返信も見送りも区間の変更もできない() {
        for (InquiryState s : List.of(
                INTAKE,
                RESULT,
                new InquiryState(Phase.KNOW, Turn.ASKER, Awaiting.ASKER_REPLY, null),
                closed(Ending.DECLINED))) {
            assertThatThrownBy(() -> InquiryStateMachine.operatorReplied(s, ReplyKind.ASK))
                    .isInstanceOf(IllegalTransitionException.class);
            assertThatThrownBy(() -> InquiryStateMachine.declined(s)).isInstanceOf(IllegalTransitionException.class);
            assertThatThrownBy(() -> InquiryStateMachine.phaseChanged(s, Phase.ACT))
                    .isInstanceOf(IllegalTransitionException.class);
        }
    }

    @Test
    void 見送ると終わる() {
        Transition t = InquiryStateMachine.declined(handling(Phase.DECIDE));

        assertThat(t.to()).isEqualTo(closed(Ending.DECLINED));
        assertThat(t.event()).isEqualTo(HistoryEvent.DECLINED);
        assertThat(t.actor()).isEqualTo(Actor.OPERATOR);
    }

    @Test
    void 区間を変えても番は変わらず_範囲外の区間は入力の誤り_同じ区間は何も変わらない() {
        Transition t = InquiryStateMachine.phaseChanged(handling(Phase.KNOW), Phase.ACT);
        assertThat(t.to()).isEqualTo(handling(Phase.ACT));
        assertThat(t.event()).isEqualTo(HistoryEvent.PHASE_CHANGED);
        assertThat(t.handsOver()).isFalse();

        assertThat(InquiryStateMachine.phaseChanged(handling(Phase.KNOW), Phase.KNOW).event()).isNull();
        assertThatThrownBy(() -> InquiryStateMachine.phaseChanged(handling(Phase.KNOW), Phase.INTAKE))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> InquiryStateMachine.phaseChanged(handling(Phase.KNOW), Phase.CLOSED))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void 置き直しで種類が変わると区間が新しい種類の決まりに合い_種類が同じなら区間は変わらず_番は変わらない() {
        Placement before = new Placement(InquiryType.HOW_TO, "a", null);
        Placement toDoForMe = new Placement(InquiryType.DO_FOR_ME, "a", "a-1");
        Placement sameType = new Placement(InquiryType.HOW_TO, "b", null);

        Transition changed = InquiryStateMachine.placementChanged(
                handling(Phase.KNOW), handedOver(InquiryType.HOW_TO), before, toDoForMe);
        assertThat(changed.to()).isEqualTo(handling(Phase.DECIDE));
        assertThat(changed.event()).isEqualTo(HistoryEvent.PLACEMENT_CHANGED);
        assertThat(changed.actor()).isEqualTo(Actor.OPERATOR);
        assertThat(changed.detail()).containsEntry("before", before).containsEntry("after", toDoForMe);

        // 担当者の番でなくても、終わっていなければ置き直せる。番は変わらない
        InquiryState waitingForAsker = new InquiryState(Phase.ACT, Turn.ASKER, Awaiting.ASKER_REPLY, null);
        Transition same = InquiryStateMachine.placementChanged(waitingForAsker, handedOver(InquiryType.HOW_TO), before, sameType);
        assertThat(same.to()).isEqualTo(waitingForAsker);

        // 種類なしにすると、KNOW に合わせる
        assertThat(InquiryStateMachine.placementChanged(
                                handling(Phase.DECIDE), handedOver(InquiryType.DO_FOR_ME), toDoForMe, Placement.NONE)
                        .to()
                        .phase())
                .isEqualTo(Phase.KNOW);
    }

    @Test
    void 終わった件は置き直せない() {
        assertThatThrownBy(() -> InquiryStateMachine.placementChanged(
                        closed(Ending.DECLINED), handedOver(null), Placement.NONE, Placement.NONE))
                .isInstanceOf(IllegalTransitionException.class);
    }

    // ---- 本人の追記 ----

    @Test
    void 担当者の番の間の追記は状態を変えない() {
        Transition t = InquiryStateMachine.noted(handling(Phase.DECIDE), handedOver(null));

        assertThat(t.to()).isEqualTo(handling(Phase.DECIDE));
        assertThat(t.event()).isNull();
    }

    @Test
    void 聞き返しへの返事で担当者の番に戻る() {
        InquiryState asked = new InquiryState(Phase.ACT, Turn.ASKER, Awaiting.ASKER_REPLY, null);

        Transition t = InquiryStateMachine.noted(asked, handedOver(null));

        assertThat(t.to()).isEqualTo(handling(Phase.ACT));
        assertThat(t.event()).isEqualTo(HistoryEvent.REPLIED);
        assertThat(t.actor()).isEqualTo(Actor.ASKER);
        assertThat(t.handsOver()).isTrue();
    }

    @Test
    void 担当者の結果への追記は解決していないと答えたのと同じで_区間は変わらない() {
        InquiryState result = new InquiryState(Phase.DECIDE, Turn.ASKER, Awaiting.RESULT_CONFIRMATION, null);

        Transition t = InquiryStateMachine.noted(result, handedOver(InquiryType.HOW_TO));

        assertThat(t.to()).isEqualTo(handling(Phase.DECIDE));
        assertThat(t.event()).isEqualTo(HistoryEvent.HANDED_OVER);
        assertThat(t.handsOver()).isTrue();
    }

    @Test
    void 終わった件への追記は開き直し_終わる前の区間に戻して担当者の番にする() {
        for (Ending ending : List.of(Ending.RESOLVED, Ending.ANSWERED, Ending.DECLINED)) {
            Transition t = InquiryStateMachine.noted(closed(ending), new Context(null, true, Phase.ACT, false, 2));
            assertThat(t.to()).isEqualTo(handling(Phase.ACT));
            assertThat(t.event()).isEqualTo(HistoryEvent.REOPENED);
            assertThat(t.handsOver()).isTrue();
        }
        // 終わる前の区間が分からなければ、種類で決める
        assertThat(InquiryStateMachine.noted(closed(Ending.ANSWERED), handedOver(InquiryType.LOOKS_WRONG)).to())
                .isEqualTo(handling(Phase.DECIDE));
    }

    @Test
    void 渡したことのない件と取り下げた件には追記できず_ほかの状態でも追記できない() {
        assertThatThrownBy(() -> InquiryStateMachine.noted(RESULT, ctx(null)))
                .isInstanceOf(IllegalTransitionException.class);
        assertThatThrownBy(() -> InquiryStateMachine.noted(closed(Ending.RESOLVED), ctx(null)))
                .isInstanceOf(IllegalTransitionException.class);
        assertThatThrownBy(() -> InquiryStateMachine.noted(closed(Ending.WITHDRAWN), handedOver(null)))
                .isInstanceOf(IllegalTransitionException.class);
        assertThatThrownBy(() -> InquiryStateMachine.noted(INVESTIGATING, handedOver(null)))
                .isInstanceOf(IllegalTransitionException.class);
    }

    // ---- 取り下げ ----

    @Test
    void 終わっていなければどの時点でも取り下げられ_終わった件は取り下げられない() {
        for (InquiryState s : List.of(INTAKE, CONFIRMING, INVESTIGATING, RESULT, handling(Phase.KNOW),
                new InquiryState(Phase.DECIDE, Turn.ASKER, Awaiting.ASKER_REPLY, null))) {
            Transition t = InquiryStateMachine.withdrawn(s);
            assertThat(t.to()).isEqualTo(closed(Ending.WITHDRAWN));
            assertThat(t.event()).isEqualTo(HistoryEvent.WITHDRAWN);
            assertThat(t.actor()).isEqualTo(Actor.ASKER);
        }
        for (Ending ending : Ending.values()) {
            assertThatThrownBy(() -> InquiryStateMachine.withdrawn(closed(ending)))
                    .isInstanceOf(IllegalTransitionException.class);
        }
    }

    // ---- 待っていることの確認 ----

    @Test
    void 待っていることが違えば入口の操作はできない() {
        assertThatThrownBy(() -> InquiryStateMachine.composed(CONFIRMING)).isInstanceOf(IllegalTransitionException.class);
        assertThatThrownBy(() -> InquiryStateMachine.reopenedIntake(INTAKE)).isInstanceOf(IllegalTransitionException.class);
        assertThatThrownBy(() -> InquiryStateMachine.confirmed(INTAKE, ctx(null))).isInstanceOf(IllegalTransitionException.class);
        assertThatThrownBy(() -> InquiryStateMachine.answeredByAi(RESULT, ctx(null), true, true))
                .isInstanceOf(IllegalTransitionException.class);
        InquiryStateMachine.require(INTAKE, Awaiting.INTAKE_ANSWER);
        assertThatThrownBy(() -> InquiryStateMachine.require(closed(Ending.RESOLVED), Awaiting.INTAKE_ANSWER))
                .isInstanceOf(IllegalTransitionException.class);
    }

    // ---- 返事がないとき（時間） ----

    @Test
    void 知らせる遷移は状態を変えず_履歴の出来事はREMINDEDで起こしたのは時間() {
        Transition t = InquiryStateMachine.reminded(RESULT);

        assertThat(t.to()).isEqualTo(RESULT);
        assertThat(t.event()).isEqualTo(HistoryEvent.REMINDED);
        assertThat(t.actor()).isEqualTo(Actor.TIME);
        assertThat(t.handsOver()).isFalse();
    }

    @Test
    void 期間が過ぎて終える遷移は回答済みで終わり_起こしたのは時間() {
        Transition t = InquiryStateMachine.answeredByTime(RESULT);

        assertThat(t.to()).isEqualTo(closed(Ending.ANSWERED));
        assertThat(t.event()).isEqualTo(HistoryEvent.ANSWERED);
        assertThat(t.actor()).isEqualTo(Actor.TIME);
        assertThat(t.handsOver()).isFalse();
    }

    @Test
    void 担当者の結果を確かめている件も同じ() {
        InquiryState operatorResult = new InquiryState(Phase.DECIDE, Turn.ASKER, Awaiting.RESULT_CONFIRMATION, null);
        assertThat(InquiryStateMachine.reminded(operatorResult).to()).isEqualTo(operatorResult);
        assertThat(InquiryStateMachine.answeredByTime(operatorResult).to()).isEqualTo(closed(Ending.ANSWERED));
    }

    private static final InquiryState ASKED_BACK = new InquiryState(Phase.DECIDE, Turn.ASKER, Awaiting.ASKER_REPLY, null);

    @Test
    void 聞き返しへの返事を待つ件も知らせる遷移は状態を変えない() {
        Transition t = InquiryStateMachine.reminded(ASKED_BACK);

        assertThat(t.to()).isEqualTo(ASKED_BACK);
        assertThat(t.event()).isEqualTo(HistoryEvent.REMINDED);
        assertThat(t.actor()).isEqualTo(Actor.TIME);
    }

    @Test
    void 聞き返しへの返事がないまま期間が過ぎると取り下げで終わり_起こしたのは時間() {
        Transition t = InquiryStateMachine.withdrawnByTime(ASKED_BACK);

        assertThat(t.to()).isEqualTo(closed(Ending.WITHDRAWN));
        assertThat(t.event()).isEqualTo(HistoryEvent.WITHDRAWN);
        assertThat(t.actor()).isEqualTo(Actor.TIME);
        assertThat(t.handsOver()).isFalse();
    }

    @Test
    void 待っていることで終わり方が決まる() {
        assertThat(InquiryStateMachine.timeEnding(Awaiting.RESULT_CONFIRMATION)).isEqualTo(Ending.ANSWERED);
        assertThat(InquiryStateMachine.timeEnding(Awaiting.ASKER_REPLY)).isEqualTo(Ending.WITHDRAWN);
        for (Awaiting other : List.of(Awaiting.INTAKE_ANSWER, Awaiting.QUESTION_CONFIRMATION, Awaiting.INVESTIGATION, Awaiting.HANDLING)) {
            assertThat(InquiryStateMachine.timeEnding(other)).isNull();
        }
        assertThat(InquiryStateMachine.timeEnding(null)).isNull();
        assertThat(InquiryStateMachine.closedByTime(RESULT).to()).isEqualTo(closed(Ending.ANSWERED));
        assertThat(InquiryStateMachine.closedByTime(ASKED_BACK).to()).isEqualTo(closed(Ending.WITHDRAWN));
    }

    @Test
    void 時間の遷移は本人の返事を待つ状態以外では例外() {
        List<InquiryState> others = List.of(
                INTAKE,
                CONFIRMING,
                INVESTIGATING,
                handling(Phase.KNOW),
                closed(Ending.ANSWERED),
                closed(Ending.RESOLVED));
        for (InquiryState state : others) {
            assertThatThrownBy(() -> InquiryStateMachine.reminded(state)).isInstanceOf(IllegalTransitionException.class);
            assertThatThrownBy(() -> InquiryStateMachine.answeredByTime(state))
                    .isInstanceOf(IllegalTransitionException.class);
            assertThatThrownBy(() -> InquiryStateMachine.withdrawnByTime(state))
                    .isInstanceOf(IllegalTransitionException.class);
            assertThatThrownBy(() -> InquiryStateMachine.closedByTime(state))
                    .isInstanceOf(IllegalTransitionException.class);
        }
        // 結果の確かめは取り下げで終わらず、聞き返しへの返事は回答済みで終わらない
        assertThatThrownBy(() -> InquiryStateMachine.answeredByTime(ASKED_BACK)).isInstanceOf(IllegalTransitionException.class);
        assertThatThrownBy(() -> InquiryStateMachine.withdrawnByTime(RESULT)).isInstanceOf(IllegalTransitionException.class);
    }

    @Test
    void 時間で取り下げになった件への追記は開き直し_終わる前の区間に戻して担当者の番にする() {
        Context byTime = new Context(InquiryType.HOW_TO, true, Phase.DECIDE, false, 2, Actor.TIME);
        Transition t = InquiryStateMachine.noted(closed(Ending.WITHDRAWN), byTime);

        assertThat(t.to()).isEqualTo(handling(Phase.DECIDE));
        assertThat(t.event()).isEqualTo(HistoryEvent.REOPENED);
        assertThat(t.actor()).isEqualTo(Actor.ASKER);
        assertThat(t.handsOver()).isTrue();
    }

    @Test
    void 本人が自分で取り下げた件への追記は開き直せない() {
        Context byAsker = new Context(InquiryType.HOW_TO, true, Phase.DECIDE, false, 2, Actor.ASKER);
        assertThatThrownBy(() -> InquiryStateMachine.noted(closed(Ending.WITHDRAWN), byAsker))
                .isInstanceOf(IllegalTransitionException.class);
        // 終わらせた人が分からない（null）場合も、これまでどおり開き直せない
        assertThatThrownBy(() -> InquiryStateMachine.noted(closed(Ending.WITHDRAWN), handedOver(null)))
                .isInstanceOf(IllegalTransitionException.class);
    }

    @Test
    void 担当者に渡したことのない回答済みの件への追記は開き直して担当者に初めて渡す() {
        // 終わる前の区間（KNOW）ではなく、種類で決まる区間にする
        Context neverHanded = new Context(InquiryType.LOOKS_WRONG, false, Phase.KNOW, false, 2);
        Transition t = InquiryStateMachine.noted(closed(Ending.ANSWERED), neverHanded);

        assertThat(t.to()).isEqualTo(handling(Phase.DECIDE));
        assertThat(t.event()).isEqualTo(HistoryEvent.REOPENED);
        assertThat(t.actor()).isEqualTo(Actor.ASKER);
        assertThat(t.handsOver()).isTrue();
        assertThat(InquiryStateMachine.noted(closed(Ending.ANSWERED), new Context(InquiryType.HOW_TO, false, Phase.KNOW, false, 2))
                        .to())
                .isEqualTo(handling(Phase.KNOW));
        assertThat(InquiryStateMachine.noted(closed(Ending.ANSWERED), new Context(null, false, Phase.KNOW, false, 2)).to())
                .isEqualTo(handling(Phase.KNOW));
    }

    @Test
    void 担当者に渡したことがなければ_回答済み以外の終わった件には追記できない() {
        for (Ending ending : List.of(Ending.RESOLVED, Ending.DECLINED, Ending.WITHDRAWN)) {
            assertThatThrownBy(() -> InquiryStateMachine.noted(closed(ending), ctx(InquiryType.HOW_TO)))
                    .isInstanceOf(IllegalTransitionException.class);
        }
    }
}
