package dev.inquiryassist.inquiry;

import dev.inquiryassist.core.Placement;
import dev.inquiryassist.core.intake.IntakeCatalog;
import dev.inquiryassist.core.lifecycle.Transition;
import java.time.Instant;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * 遷移を反映し、保存し、保存の確定後に担当者へ知らせる。本人の操作（{@link InquiryService}）と、調査の結果を保存する役
 * （{@link InvestigationRunner}）が、同じ手順を通るようにする。
 *
 * <p>担当者に初めて渡す遷移は、通知を保存と同じトランザクションの中で出す（通知が失敗したら保存も取り消す）。
 * 確定後に、初めて渡したときは {@link InquiryFiledEvent}、すでに渡したことのある件の変更は {@link InquiryUpdatedEvent} を発行する。
 */
@Component
class InquiryCommitter {

    private static final Logger log = LoggerFactory.getLogger(InquiryCommitter.class);

    private final InquiryRepository repository;
    private final FilingNotifier notifier;
    private final TransactionTemplate transaction;
    private final IntakeLocales locales;
    private final InquiryPresenter presenter;
    private final ApplicationEventPublisher events;
    private final org.springframework.beans.factory.ObjectProvider<AdviceRunner> adviceRunner;

    /**
     * @param repository 問い合わせ記録の保存先
     * @param notifier 担当者への通知先
     * @param transaction 保存時のトランザクション
     * @param locales 通知に載せる名前（既定の言語）を引く
     * @param presenter 担当者向けのイベントの内容を作る
     * @param events 保存の確定後にイベントを発行する
     * @param adviceRunner 保存の確定後に、AI の案を作り始めるか判断する（案を保存する役も、この保存を通るので、循環を避けて遅く引く）
     */
    InquiryCommitter(
            InquiryRepository repository,
            FilingNotifier notifier,
            TransactionTemplate transaction,
            IntakeLocales locales,
            InquiryPresenter presenter,
            ApplicationEventPublisher events,
            org.springframework.beans.factory.ObjectProvider<AdviceRunner> adviceRunner) {
        this.repository = repository;
        this.notifier = notifier;
        this.transaction = transaction;
        this.locales = locales;
        this.presenter = presenter;
        this.events = events;
        this.adviceRunner = adviceRunner;
    }

    /**
     * 遷移を反映する。担当者に初めて渡す遷移なら、通知の内容を返す（通知は保存と同じトランザクションで {@link #commit} が出す）。
     * それ以外は null。
     *
     * @param inquiry 問い合わせ記録
     * @param transition 遷移
     * @param immediately 答えを待たずに送ったか
     * @return 通知の内容。初めて渡す遷移でなければ null
     */
    FilingNotifier.FilingNotice apply(InquiryEntity inquiry, Transition transition, boolean immediately) {
        boolean first = inquiry.handedOverAt == null && transition.handsOver();
        inquiry.apply(transition, Instant.now());
        if (!first) {
            return null;
        }
        inquiry.sentImmediately = immediately;
        Placement placement = inquiry.placement();
        IntakeCatalog catalog = locales.catalog(locales.defaultLanguage());
        return new FilingNotifier.FilingNotice(
                inquiry.id,
                placement.type(),
                inquiry.urgency,
                inquiry.utterance(),
                placement.systemId() == null ? null : catalog.systemLabel(placement.systemId()),
                placement.functionId() == null
                        ? null
                        : catalog.functionLabel(placement.systemId(), placement.functionId()),
                inquiry.structured(),
                inquiry.technicalMemo,
                immediately);
    }

    /**
     * 保存する。担当者に初めて渡す場合は保存の確定前に通知し、通知が失敗したら保存も取り消す。
     * 確定後に、担当者のストリームへイベントを流す。他の操作との競合は {@link InvalidStateException} にする。
     *
     * @param inquiry 問い合わせ記録
     * @param notice 初めて渡すときの通知の内容。なければ null
     * @return 保存した記録
     * @throws InvalidStateException 他の操作と競合した場合
     */
    InquiryEntity commit(InquiryEntity inquiry, FilingNotifier.FilingNotice notice) {
        try {
            return commitOrConflict(inquiry, notice);
        } catch (OptimisticLockingFailureException e) {
            throw new InvalidStateException("他の操作と競合しました。最新の状態を取得し直してください");
        }
    }

    /**
     * {@link #commit} と同じだが、競合を {@link OptimisticLockingFailureException} のまま投げる（読み直してやり直す呼び出し側のため）。
     *
     * @param inquiry 問い合わせ記録
     * @param notice 初めて渡すときの通知の内容。なければ null
     * @return 保存した記録
     * @throws OptimisticLockingFailureException 他の操作と競合した場合
     */
    InquiryEntity commitOrConflict(InquiryEntity inquiry, FilingNotifier.FilingNotice notice) {
        inquiry.updatedAt = Instant.now();
        // 保存と同時に、AI の案を作り始める印を付ける（担当者の番で、材料があり、案がないとき）
        boolean advise = adviceRunner.getObject().claim(inquiry);
        InquiryEntity saved = transaction.execute(status -> {
            InquiryEntity s = repository.saveAndFlush(inquiry);
            if (notice != null) {
                notifier.notifyFiled(notice);
            }
            return s;
        });
        // 保存し直した記録は別のインスタンスになりうるため、見る人の言語を引き継ぐ
        saved.viewerLanguage = inquiry.viewerLanguage;
        if (notice != null) {
            // 保存の確定後に配る。配信側の失敗で、確定済みの更新を失敗にしない
            try {
                events.publishEvent(presenter.filedEvent(saved));
            } catch (RuntimeException e) {
                log.error("担当者への通知イベントの配信に失敗 id={}", saved.id, e);
            }
        } else if (saved.handedOverAt != null) {
            publishUpdated(saved);
        }
        if (advise) {
            adviceRunner.getObject().launch(saved.id);
        }
        return saved;
    }

    /**
     * 保存の確定後に、オペレータの画面へ更新を知らせる。配信側の失敗で、確定済みの更新を失敗にしない。
     *
     * @param saved 保存した記録
     */
    void publishUpdated(InquiryEntity saved) {
        try {
            events.publishEvent(presenter.updatedEvent(saved));
        } catch (RuntimeException e) {
            log.error("更新の通知イベントの配信に失敗 id={}", saved.id, e);
        }
    }


    /** 確定後に担当者へ何を流すか。 */
    enum Publish {
        /** 何も流さない（終わっている件への結果など）。 */
        NONE,
        /** すでに担当者に渡したことのある件なら、更新を流す。 */
        UPDATED_IF_HANDED_OVER
    }

    /**
     * 更新の内容。
     *
     * @param notice 初めて渡すときの通知の内容。なければ null（あれば、確定後に「渡した」イベントを流す）
     * @param publish 通知がないときに、確定後に流すもの
     */
    record Update(FilingNotifier.FilingNotice notice, Publish publish) {}

    /**
     * 記録を<strong>トランザクションの中で読み込み</strong>、その場で変更して保存する。読み込みから保存までが短く、変えた列だけが
     * 更新される（別のスレッドの直接の更新と重なっても、変えていない列を古い値で上書きしない）。競合は
     * {@link OptimisticLockingFailureException} のまま投げる（呼び出し側が、読み直してやり直す）。
     *
     * @param id 問い合わせの識別子
     * @param mutator 読み込んだ記録を変更し、確定後の通知を返す。何も変更せず null を返すと、保存しない
     * @return 保存した記録。記録がない、または mutator が null を返した場合は null
     * @throws OptimisticLockingFailureException 他の操作と競合した場合
     */
    InquiryEntity update(java.util.UUID id, java.util.function.Function<InquiryEntity, Update> mutator) {
        record Done(InquiryEntity saved, Update update, boolean advise) {}
        Done done = transaction.execute(status -> {
            InquiryEntity inquiry = repository.findById(id).orElse(null);
            if (inquiry == null) {
                return null;
            }
            Update update = mutator.apply(inquiry);
            if (update == null) {
                return null;
            }
            inquiry.updatedAt = Instant.now();
            // 保存と同時に、AI の案を作り始める印を付ける（担当者の番で、材料があり、案がないとき）
            boolean advise = adviceRunner.getObject().claim(inquiry);
            InquiryEntity saved = repository.saveAndFlush(inquiry);
            if (update.notice() != null) {
                notifier.notifyFiled(update.notice());
            }
            return new Done(saved, update, advise);
        });
        if (done == null) {
            return null;
        }
        InquiryEntity saved = done.saved();
        if (done.update().notice() != null) {
            try {
                events.publishEvent(presenter.filedEvent(saved));
            } catch (RuntimeException e) {
                log.error("担当者への通知イベントの配信に失敗 id={}", saved.id, e);
            }
        } else if (done.update().publish() == Publish.UPDATED_IF_HANDED_OVER && saved.handedOverAt != null) {
            publishUpdated(saved);
        }
        if (done.advise()) {
            adviceRunner.getObject().launch(saved.id);
        }
        return saved;
    }
}
