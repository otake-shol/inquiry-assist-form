package dev.inquiryassist;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.builder.SpringApplicationBuilder;

/** 本番（production）では、モックを含む構成の起動を止める。 */
class ProductionMockGuardTest {

    private static final String UNREACHABLE_DB = "--spring.datasource.url=jdbc:postgresql://localhost:1/none";

    @Test
    void productionで設定なし_全部モックなら起動時に例外で停止する() {
        // DB には接続できない設定にしておく。ガードが先に働かなければ別の例外になり、このテストは失敗する
        assertThatThrownBy(() -> new SpringApplicationBuilder(BackendApplication.class)
                        .web(WebApplicationType.NONE)
                        .profiles("production")
                        .run(UNREACHABLE_DB))
                .hasStackTraceContaining("production プロファイルでは、モックを含む構成で起動できません")
                .hasStackTraceContaining("reception")
                .hasStackTraceContaining("knowledge");
    }
}
