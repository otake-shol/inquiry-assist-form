package dev.inquiryassist;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.builder.SpringApplicationBuilder;

class ProductionMockGuardTest {

    @Test
    void productionとmockが同時に有効なら起動時に例外で停止する() {
        // DB には接続できない設定にしておく。ガードが先に働かなければ別の例外になり、このテストは失敗する
        assertThatThrownBy(() -> new SpringApplicationBuilder(BackendApplication.class)
                        .web(WebApplicationType.NONE)
                        .profiles("production", "mock")
                        .run("--spring.datasource.url=jdbc:postgresql://localhost:1/none"))
                .hasStackTraceContaining("production プロファイルでは mock プロファイルを有効にできません");
    }
}
