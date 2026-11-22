package dev.inquiryassist;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;

import dev.inquiryassist.core.KnowledgeSearch;
import dev.inquiryassist.core.skill.AdvisorSkill;
import dev.inquiryassist.core.skill.DrafterSkill;
import dev.inquiryassist.core.skill.ExplainerSkill;
import dev.inquiryassist.core.skill.InterviewerSkill;
import dev.inquiryassist.core.skill.InvestigatorSkill;
import dev.inquiryassist.core.skill.ReceptionSkill;
import dev.inquiryassist.core.skill.RestaterSkill;
import dev.inquiryassist.core.skill.TranslatorSkill;
import java.util.LinkedHashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.support.GenericApplicationContext;

/**
 * 骨組みの全体（部品の走査を含む）を起動して、スキルと資料の検索の実装が見つからないとき、2つあるときに、Spring の素の例外ではなく、
 * 何をすればよいかを書いたメッセージで止まることを、8つのスキルと資料の検索のそれぞれで確かめる。DB には接続できない設定にしておく
 * （検査は、どの Bean も作る前に働くので、DB の接続の失敗より先に止まる）。
 */
class SkillOverrideStartupTest {

    private static final String UNREACHABLE_DB = "--spring.datasource.url=jdbc:postgresql://localhost:1/none";

    private static Map<String, Class<?>> targets() {
        Map<String, Class<?>> targets = new LinkedHashMap<>();
        targets.put("inquiry.skills.reception.provider", ReceptionSkill.class);
        targets.put("inquiry.skills.interviewer.provider", InterviewerSkill.class);
        targets.put("inquiry.skills.restater.provider", RestaterSkill.class);
        targets.put("inquiry.skills.investigator.provider", InvestigatorSkill.class);
        targets.put("inquiry.skills.explainer.provider", ExplainerSkill.class);
        targets.put("inquiry.skills.advisor.provider", AdvisorSkill.class);
        targets.put("inquiry.skills.drafter.provider", DrafterSkill.class);
        targets.put("inquiry.skills.translator.provider", TranslatorSkill.class);
        targets.put("inquiry.knowledge.provider", KnowledgeSearch.class);
        return targets;
    }

    private static <T> void register(GenericApplicationContext context, Class<T> type) {
        context.registerBean("extra" + type.getSimpleName(), type, () -> mock(type));
    }

    @Test
    void 実装を足さずにproviderだけ変えると_どの対象でも_足すかmockに戻すよう伝えて止まる() {
        targets().forEach((key, type) -> assertThatThrownBy(() -> new SpringApplicationBuilder(BackendApplication.class)
                        .web(WebApplicationType.NONE)
                        .run(UNREACHABLE_DB, "--" + key + "=custom"))
                .as(key)
                .hasStackTraceContaining(key + " に custom が指定されていますが、" + type.getSimpleName() + " の実装が見つかりません")
                .hasStackTraceContaining(key + " を mock に戻してください")
                .satisfies(e -> assertThat(e.toString() + e.getCause()).doesNotContain("NoSuchBeanDefinition")));
    }

    @Test
    void providerがmockのまま自分の実装も足すと_どの対象でも_providerを変えるよう伝えて止まる() {
        targets().forEach((key, type) -> assertThatThrownBy(() -> new SpringApplicationBuilder(BackendApplication.class)
                        .web(WebApplicationType.NONE)
                        .initializers(context -> register((GenericApplicationContext) context, type))
                        .run(UNREACHABLE_DB))
                .as(key)
                .hasStackTraceContaining(type.getSimpleName() + " の実装が2つ以上あります")
                .hasStackTraceContaining(key + " が mock のままです")
                .satisfies(e -> assertThat(e.toString() + e.getCause()).doesNotContain("NoUniqueBeanDefinition")));
    }
}
