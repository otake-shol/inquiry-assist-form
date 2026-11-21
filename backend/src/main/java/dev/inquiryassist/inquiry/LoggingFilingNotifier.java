package dev.inquiryassist.inquiry;

import dev.inquiryassist.core.TechnicalMemo;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/** 起票先が未確定のため、整形後の問いと技術メモの要約をログに出すだけの実装。 */
@Component
public class LoggingFilingNotifier implements FilingNotifier {

    private static final Logger log = LoggerFactory.getLogger(LoggingFilingNotifier.class);

    /**
     * @param notice 通知の内容
     */
    @Override
    public void notifyFiled(FilingNotice notice) {
        TechnicalMemo memo = notice.memo();
        log.info(
                "起票 id={} category={} system={} sentImmediately={} structured={} memo(findings={}, notFound={}, nextChecks={})",
                notice.inquiryId(),
                notice.category(),
                notice.systemLabel(),
                notice.sentImmediately(),
                notice.structured() != null ? notice.structured() : "（整形前。元の発話: " + notice.utterance() + "）",
                memo == null ? 0 : memo.findings().size(),
                memo == null ? 0 : memo.notFound().size(),
                memo == null ? 0 : memo.nextChecks().size());
        if (notice.structured() != null) {
            log.info("  言い直し: {}", notice.structured().summary());
        }
        if (memo != null) {
            memo.findings().forEach(f -> log.info("  分かったこと: {}", f));
            memo.notFound().forEach(f -> log.info("  見つからなかったこと: {}", f));
            memo.nextChecks().forEach(f -> log.info("  次に確認すること: {}", f));
        }
    }
}
