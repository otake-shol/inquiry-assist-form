package dev.inquiryassist;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.core.env.Environment;
import org.springframework.core.env.Profiles;
import dev.inquiryassist.core.skill.AnswerPipeline;
import dev.inquiryassist.core.skill.ReceptionSkill;
import dev.inquiryassist.mock.MockReceptionSkill;

@SpringBootTest
@Import(TestcontainersConfiguration.class)
class BackendApplicationTests {

    @Autowired
    Environment environment;

    @Autowired
    ReceptionSkill reception;

    @Autowired
    AnswerPipeline pipeline;

    @Test
    void プロファイルを指定しなければモックが有効になる() {
        assertThat(environment.acceptsProfiles(Profiles.of("mock"))).isTrue();
        assertThat(reception).isInstanceOf(MockReceptionSkill.class);
        assertThat(pipeline).isNotNull();
    }
}
