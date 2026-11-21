package dev.inquiryassist.eval;

import dev.inquiryassist.core.InquiryCategory;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.List;
import java.util.regex.Pattern;
import tools.jackson.databind.JsonNode;

/**
 * 出力を、例の {@code expect} に書かれた条件で採点する。機械的に確かめられる条件だけを使う。
 *
 * <p>すべての例に共通で確かめる項目もある: 聞き手の選択肢の数（0個、または2〜4個）、聞き手のエンジニア向けの語、
 * 説明のコード上の名前。
 */
final class Scorer {

    /** 聞き手の質問1件。 */
    record Question(String text, List<String> options) {}

    private Scorer() {}

    // ---- 受付 ----

    /**
     * 受付の出力を採点する。{@code systemId} の null は「推測しない」が正解であることを表し、{@code systemIdAnyOf} に
     * null を含めると「推測しない」も正解になる。
     *
     * @param expect 条件
     * @param category 出力の種別
     * @param systemId 出力の対象システム（推測しなければ null）
     * @return 落ちた条件
     */
    static List<Failure> scoreReception(JsonNode expect, InquiryCategory category, String systemId) {
        List<Failure> failures = new ArrayList<>();
        if (expect.has("category") && !expect.get("category").asString().equals(category.name())) {
            failures.add(new Failure("category", "期待 " + expect.get("category").asString() + "、出力 " + category));
        }
        if (expect.has("categoryAnyOf")) {
            List<String> allowed = strings(expect.get("categoryAnyOf"));
            if (!allowed.contains(category.name())) {
                failures.add(new Failure("categoryAnyOf", "期待 " + allowed + "、出力 " + category));
            }
        }
        if (expect.has("systemId") && !sameSystem(expect.get("systemId"), systemId)) {
            failures.add(new Failure("systemId", "期待 " + show(expect.get("systemId")) + "、出力 " + systemId));
        }
        if (expect.has("systemIdAnyOf")) {
            boolean match = false;
            for (JsonNode allowed : expect.get("systemIdAnyOf")) {
                match |= sameSystem(allowed, systemId);
            }
            if (!match) {
                failures.add(new Failure("systemIdAnyOf", "期待 " + expect.get("systemIdAnyOf") + "、出力 " + systemId));
            }
        }
        return failures;
    }

    private static boolean sameSystem(JsonNode expected, String actual) {
        return expected.isNull() ? actual == null : expected.asString().equals(actual);
    }

    private static String show(JsonNode node) {
        return node.isNull() ? "null" : node.asString();
    }

    // ---- 聞き手 ----

    /**
     * 聞き手の出力を採点する。選択肢の数（0個、または2〜4個）と、エンジニア向けの語は、すべての例で確かめる。
     *
     * @param expect 条件
     * @param questions 出力の質問
     * @return 落ちた条件
     */
    static List<Failure> scoreInterviewer(JsonNode expect, List<Question> questions) {
        List<Failure> failures = new ArrayList<>();
        if (expect.has("minQuestions") && questions.size() < expect.get("minQuestions").asInt()) {
            failures.add(new Failure("minQuestions", "質問 " + questions.size() + " 個（最小 " + expect.get("minQuestions").asInt() + "）"));
        }
        if (expect.has("maxQuestions") && questions.size() > expect.get("maxQuestions").asInt()) {
            failures.add(new Failure("maxQuestions", "質問 " + questions.size() + " 個（最大 " + expect.get("maxQuestions").asInt() + "）"));
        }
        // すべての例で確かめる: 選択肢は0個、または2〜4個
        for (Question q : questions) {
            int n = q.options().size();
            if (n != 0 && (n < EvalTerms.MIN_OPTIONS || n > EvalTerms.MAX_OPTIONS)) {
                failures.add(new Failure("optionCount", "選択肢 " + n + " 個: " + q.text()));
            }
        }
        // すべての例で確かめる: エンジニア向けの語（forbidEngineerTerms が false と書かれていても確かめる）
        for (Question q : questions) {
            List<String> targets = new ArrayList<>(q.options());
            targets.add(q.text());
            for (String text : targets) {
                firstMatch(EvalTerms.ENGINEER_TERMS, text)
                        .ifPresent(m -> failures.add(new Failure("forbidEngineerTerms", "「" + m + "」: " + q.text())));
            }
        }
        if (expect.path("forbidLocationQuestion").asBoolean(false)) {
            for (Question q : questions) {
                if (EvalTerms.LOCATION_QUESTION.matcher(q.text()).find()) {
                    failures.add(new Failure("forbidLocationQuestion", "場所を尋ねている: " + q.text()));
                }
            }
        }
        if (expect.has("mustNotMatch")) {
            for (Question q : questions) {
                for (String regex : strings(expect.get("mustNotMatch"))) {
                    if (Pattern.compile(regex).matcher(q.text()).find()) {
                        failures.add(new Failure("mustNotMatch", "/" + regex + "/ に当てはまる: " + q.text()));
                    }
                }
            }
        }
        return failures;
    }

    // ---- 言い直し・説明 ----

    /**
     * 言い直しの文章を採点する。
     *
     * @param expect 条件
     * @param summary 言い直しの文章
     * @return 落ちた条件
     */
    static List<Failure> scoreRestater(JsonNode expect, String summary) {
        return scoreRestater(expect, summary, null, null, null);
    }

    /**
     * 言い直しを、文章（summary）と、構造化データの項目で採点する。{@code mustNotInclude} と {@code mustNotMatch} は、
     * 文章に加えて {@code goal}、{@code whatHappened}、{@code expectedResult} にも適用する。これらの項目は担当者への
     * 引き継ぎと調査に使うため、言い直しの文章に出ていなくても、本人が言っていないことが足されていれば落とす。
     * それ以外の条件（{@code mustInclude}、{@code mustIncludeAny}、{@code style}、文の数）は文章だけを見る。
     * どの項目で当たったかは、落ちた理由に出す。
     *
     * @param expect 条件
     * @param summary 言い直しの文章
     * @param goal 求めていること（なければ null）
     * @param whatHappened 起きたこと（なければ null）
     * @param expectedResult 期待した結果（なければ null）
     * @return 落ちた条件
     */
    static List<Failure> scoreRestater(
            JsonNode expect, String summary, String goal, String whatHappened, String expectedResult) {
        List<Failure> failures = new ArrayList<>();
        scoreText(expect, summary, "summary", failures);
        Map<String, String> others = new LinkedHashMap<>();
        others.put("goal", goal);
        others.put("whatHappened", whatHappened);
        others.put("expectedResult", expectedResult);
        others.forEach((field, value) -> {
            if (value != null) {
                scoreForbidden(expect, value, "[" + field + "] ", failures);
            }
        });
        return failures;
    }

    /**
     * 説明を採点する。コード上の名前（英字の識別子など）が出ていないことは、すべての例で確かめる。
     *
     * @param expect 条件
     * @param text 説明
     * @param answersQuestion 出力の「問いに答えられたか」の判定
     * @return 落ちた条件
     */
    static List<Failure> scoreExplainer(JsonNode expect, String text, boolean answersQuestion) {
        List<Failure> failures = new ArrayList<>();
        if (expect.has("answersQuestion") && expect.get("answersQuestion").asBoolean() != answersQuestion) {
            failures.add(new Failure("answersQuestion", "期待 " + expect.get("answersQuestion").asBoolean() + "、出力 " + answersQuestion));
        }
        if (expect.path("mustAcknowledgeUnknown").asBoolean(false)
                && EvalTerms.UNKNOWN_ACKNOWLEDGEMENTS.stream().noneMatch(text::contains)) {
            failures.add(new Failure("mustAcknowledgeUnknown", "確認できなかったことを述べていない"));
        }
        scoreText(expect, text, "text", failures);
        // すべての例で確かめる: コード上の名前が出ていない
        firstMatch(EvalTerms.CODE_NAMES, text)
                .ifPresent(m -> failures.add(new Failure("noCodeNames", "コード上の名前「" + m + "」が出ている")));
        return failures;
    }

    private static void scoreText(JsonNode expect, String text, String field, List<Failure> failures) {
        if (expect.has("mustInclude")) {
            for (String word : strings(expect.get("mustInclude"))) {
                if (!text.contains(word)) {
                    failures.add(new Failure("mustInclude", "「" + word + "」を含まない"));
                }
            }
        }
        if (expect.has("mustIncludeAny")) {
            for (JsonNode group : expect.get("mustIncludeAny")) {
                List<String> words = strings(group);
                if (words.stream().noneMatch(text::contains)) {
                    failures.add(new Failure("mustIncludeAny", words + " のどれも含まない"));
                }
            }
        }
        scoreForbidden(expect, text, "[" + field + "] ", failures);
        int sentences = sentences(text).size();
        if (expect.has("minSentences") && sentences < expect.get("minSentences").asInt()) {
            failures.add(new Failure("minSentences", sentences + " 文（最小 " + expect.get("minSentences").asInt() + "）"));
        }
        if (expect.has("maxSentences") && sentences > expect.get("maxSentences").asInt()) {
            failures.add(new Failure("maxSentences", sentences + " 文（最大 " + expect.get("maxSentences").asInt() + "）"));
        }
        if (expect.has("style")) {
            scoreStyle(expect.get("style").asString(), text, failures);
        }
    }

    /** 含んではいけない語と、当てはまってはいけない正規表現。落ちた理由に項目名（{@code prefix}）を付ける。 */
    private static void scoreForbidden(JsonNode expect, String text, String prefix, List<Failure> failures) {
        if (expect.has("mustNotInclude")) {
            for (String word : strings(expect.get("mustNotInclude"))) {
                if (text.contains(word)) {
                    failures.add(new Failure("mustNotInclude", prefix + "「" + word + "」を含む"));
                }
            }
        }
        if (expect.has("mustNotMatch")) {
            for (String regex : strings(expect.get("mustNotMatch"))) {
                var matcher = Pattern.compile(regex).matcher(text);
                if (matcher.find()) {
                    failures.add(new Failure("mustNotMatch", prefix + "/" + regex + "/ に当てはまる: 「" + matcher.group() + "」"));
                }
            }
        }
    }

    /**
     * 文末の調子を確かめる。{@code plain} は、文末が「です・ます」系でない（すべての文が「である」調）。
     * {@code polite} は、すべての文末が「です・ます」系か「でした」。
     */
    private static void scoreStyle(String style, String text, List<Failure> failures) {
        for (String sentence : sentences(text)) {
            String end = stripTrailingMarks(sentence);
            boolean polite = EvalTerms.POLITE_ENDINGS.stream().anyMatch(end::endsWith);
            boolean politePast = end.endsWith(EvalTerms.POLITE_PAST_ENDING);
            if ("plain".equals(style) && polite) {
                failures.add(new Failure("style", "「です・ます」調の文がある: " + sentence));
            } else if ("polite".equals(style) && !polite && !politePast) {
                failures.add(new Failure("style", "「です・ます」調でない文がある: " + sentence));
            }
        }
    }

    /**
     * 文に分ける。「。」で区切り、空の部分は数えない（末尾の「。」がなくても1文として数える）。
     *
     * @param text 文章
     * @return 文
     */
    static List<String> sentences(String text) {
        return Arrays.stream(text.split("。")).map(String::strip).filter(s -> !s.isEmpty()).toList();
    }

    private static String stripTrailingMarks(String sentence) {
        int end = sentence.length();
        while (end > 0 && EvalTerms.TRAILING_MARKS.indexOf(sentence.charAt(end - 1)) >= 0) {
            end--;
        }
        return sentence.substring(0, end);
    }

    private static java.util.Optional<String> firstMatch(List<Pattern> patterns, String text) {
        for (Pattern pattern : patterns) {
            var matcher = pattern.matcher(text);
            if (matcher.find()) {
                return java.util.Optional.of(matcher.group());
            }
        }
        return java.util.Optional.empty();
    }

    private static List<String> strings(JsonNode array) {
        List<String> result = new ArrayList<>();
        array.forEach(n -> result.add(n.asString()));
        return result;
    }
}
