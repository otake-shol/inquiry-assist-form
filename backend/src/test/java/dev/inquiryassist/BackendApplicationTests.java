package dev.inquiryassist;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.core.env.Environment;
import org.springframework.core.env.Profiles;
import dev.inquiryassist.core.InquiryAssistant;
import dev.inquiryassist.mock.MockInquiryAssistant;

@SpringBootTest
@Import(TestcontainersConfiguration.class)
class BackendApplicationTests {

    @Autowired
    Environment environment;

    @Autowired
    InquiryAssistant assistant;

    @Test
    void プロファイルを指定しなければモックが有効になる() {
        assertThat(environment.acceptsProfiles(Profiles.of("mock"))).isTrue();
        assertThat(assistant).isInstanceOf(MockInquiryAssistant.class);
    }
}
