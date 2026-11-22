package dev.inquiryassist.inquiry;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jayway.jsonpath.JsonPath;
import dev.inquiryassist.core.AccessScope;
import dev.inquiryassist.core.AiResult;
import dev.inquiryassist.core.InquiryType;
import dev.inquiryassist.core.KnowledgeSearch;
import dev.inquiryassist.core.Placement;
import dev.inquiryassist.core.SystemOption;
import dev.inquiryassist.core.Usage;
import dev.inquiryassist.core.skill.Reception;
import dev.inquiryassist.core.skill.ReceptionSkill;
import dev.inquiryassist.mock.MockInterviewerSkill;
import dev.inquiryassist.mock.MockKnowledgeSearch;
import dev.inquiryassist.mock.MockReceptionSkill;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;

/**
 * 骨組みのファイルを書き換えずに、クラスと設定を足すだけで、スキル、資料の検索、利用者を差し替えられることを確かめる。
 * テストの中だけにある実装を使う（骨組みの本体に、見本の実装は足さない）。受付と資料の検索の {@code provider} を {@code custom} にし、
 * 利用者は {@code @Primary} なしで差し替える。ほかのスキルはモックのまま。
 */
@SpringBootTest(
        properties = {
            "inquiry.skills.reception.provider=custom",
            "inquiry.knowledge.provider=custom",
            "inquiry.mock.slow-step-delay=10ms"
        })
@Import({
    SkillOverrideApiTest.CustomReception.class,
    SkillOverrideApiTest.CustomKnowledge.class,
    SkillOverrideApiTest.CustomUsers.class,
    SkillOverrideApiTest.CustomNotification.class
})
class SkillOverrideApiTest extends ApiSupport {

    /** テスト用の受付。{@code ReceptionSkill} を実装するだけで、注釈も条件もない。 */
    static class CustomReception implements ReceptionSkill {
        static final AtomicInteger CALLS = new AtomicInteger();

        @Override
        public AiResult<Reception> receive(String utterance, List<SystemOption> candidates, Placement decided) {
            CALLS.incrementAndGet();
            return new AiResult<>(
                    new Reception(InquiryType.HOW_IT_WORKS, null, null), new Usage("custom-model", "c1", 5, 2, 0, false));
        }
    }

    /** テスト用の資料の検索。見本の資料に委譲して、呼ばれた回数を数える。 */
    static class CustomKnowledge implements KnowledgeSearch {
        static final AtomicInteger READS = new AtomicInteger();
        private final MockKnowledgeSearch delegate = new MockKnowledgeSearch();

        @Override
        public List<SearchHit> search(String query, AccessScope scope, int limit) {
            return delegate.search(query, scope, limit);
        }

        @Override
        public FileExcerpt read(String repository, String path, int startLine, int endLine, AccessScope scope) {
            READS.incrementAndGet();
            return delegate.read(repository, path, startLine, endLine, scope);
        }
    }

    /** テスト用の利用者。{@code CurrentUserProvider} を実装するだけで、{@code @Primary} は付けない。 */
    static class CustomUsers implements CurrentUserProvider {
        static final String USER_ID = "custom-user";
        private static final Set<String> REPOSITORIES = Set.of("job-board", "faq", "glossary");

        @Override
        public AccessScope currentScope() {
            return scopeOf(USER_ID);
        }

        @Override
        public AccessScope scopeOf(String userId) {
            return new AccessScope(userId, REPOSITORIES);
        }
    }

    /** 通知を足す例。担当者に初めて渡した後に、確定後のイベントを受け取る（Slack の投稿と同じ受け取り方）。 */
    static class CustomNotification {
        static final java.util.List<InquiryFiledEvent> FILED = new java.util.concurrent.CopyOnWriteArrayList<>();

        @org.springframework.context.event.EventListener
        public void onFiled(InquiryFiledEvent event) {
            FILED.add(event);
        }
    }

    @Autowired
    ReceptionSkill reception;

    @Autowired
    KnowledgeSearch knowledge;

    @Autowired
    CurrentUserProvider users;

    @Autowired
    ObjectProvider<MockReceptionSkill> mockReception;

    @Autowired
    ObjectProvider<MockKnowledgeSearch> mockKnowledge;

    @Autowired
    ObjectProvider<MockInterviewerSkill> mockInterviewer;

    @Autowired
    ObjectProvider<FixedCurrentUserProvider> defaultUsers;

    @Test
    void 足した実装が使われ_その名前のモックは作られず_ほかはモックのまま() {
        assertThat(reception).isInstanceOf(CustomReception.class);
        assertThat(mockReception.getIfAvailable()).isNull();
        assertThat(knowledge).isInstanceOf(CustomKnowledge.class);
        assertThat(mockKnowledge.getIfAvailable()).isNull();
        assertThat(mockInterviewer.getIfAvailable()).isNotNull();
        // 利用者は、@Primary なしで足した実装が使われる。既定の実装は、候補として残るだけ（使われない）
        assertThat(users).isInstanceOf(CustomUsers.class);
        assertThat(defaultUsers.getIfAvailable()).isNotNull();
    }

    @Test
    void configのskillsに名前がそのまま出て_ほかがモックなのでmockはtrue() throws Exception {
        mvc.perform(get("/api/config"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.skills.reception").value("custom"))
                .andExpect(jsonPath("$.skills.interviewer").value("mock"))
                .andExpect(jsonPath("$.skills.translator").value("mock"))
                .andExpect(jsonPath("$.mock").value(true))
                .andExpect(jsonPath("$.user.id").value(CustomUsers.USER_ID));
    }

    @Test
    void 足した受付が呼ばれ_実行記録のproviderは記録した時点の名前になり_保存にも残る() throws Exception {
        int before = CustomReception.CALLS.get();

        String created = create("期限の仕様を教えて");
        String id = idOf(created);

        assertThat(CustomReception.CALLS.get()).isEqualTo(before + 1);
        assertThat((String) JsonPath.read(created, "$.type.id")).isEqualTo("HOW_IT_WORKS");
        assertThat((String) JsonPath.read(created, "$.trace[0].skill")).isEqualTo("reception");
        assertThat((String) JsonPath.read(created, "$.trace[0].provider")).isEqualTo("custom");
        assertThat((String) JsonPath.read(created, "$.trace[0].model")).isEqualTo("custom-model");
        // 保存した利用量が、記録した時点の名前を持つ
        assertThat(jdbc.queryForObject("select usages->0->>'provider' from inquiries where id = ?::uuid", String.class, id))
                .isEqualTo("custom");
        assertThat(jdbc.queryForObject("select asker_id from inquiries where id = ?::uuid", String.class, id))
                .isEqualTo(CustomUsers.USER_ID);
        // モックのスキルの記録は、mock
        String structured = finishIntake(id, null);
        assertThat((List<String>) JsonPath.read(structured, "$.trace[?(@.skill=='interviewer' || @.skill=='restater')].provider"))
                .isNotEmpty()
                .containsOnly("mock");
    }

    @Test
    void 足した資料の検索が_調査の出典の照合に使われる() throws Exception {
        int before = CustomKnowledge.READS.get();
        String id = toAnswering("使い方を教えて", null);

        stream(id);

        assertThat(CustomKnowledge.READS.get()).isGreaterThan(before);
    }

    @Test
    void 通知を足すと_担当者に初めて渡した確定後に_イベントを受け取れる() throws Exception {
        String id = filedNow("使い方を教えて", null);

        assertThat(CustomNotification.FILED).anySatisfy(event -> {
            assertThat(event.item().id().toString()).isEqualTo(id);
            assertThat(event.item().sentImmediately()).isTrue();
            assertThat(event.item().type().id()).isEqualTo("HOW_IT_WORKS");
            assertThat(event.itemFor("en").id().toString()).isEqualTo(id);
        });
    }
}
