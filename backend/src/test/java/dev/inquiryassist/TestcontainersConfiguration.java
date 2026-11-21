package dev.inquiryassist;

import dev.inquiryassist.inquiry.FilingNotifier;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.testcontainers.postgresql.PostgreSQLContainer;

/** 統合テスト用の設定。PostgreSQL を Testcontainers で起動し、起票の通知を記録する通知先に差し替える。 */
@TestConfiguration(proxyBeanMethods = false)
public class TestcontainersConfiguration {

    /** 通知を記録するだけの通知先。 */
    public static class RecordingFilingNotifier implements FilingNotifier {
        private final List<FilingNotice> notices = new CopyOnWriteArrayList<>();

        @Override
        public void notifyFiled(FilingNotice notice) {
            notices.add(notice);
        }

        /**
         * @return これまでに通知された内容
         */
        public List<FilingNotice> notices() {
            return notices;
        }
    }

    @Bean
    @ServiceConnection
    PostgreSQLContainer postgres() {
        return new PostgreSQLContainer("postgres:17");
    }

    @Bean
    @Primary
    RecordingFilingNotifier recordingFilingNotifier() {
        return new RecordingFilingNotifier();
    }
}
