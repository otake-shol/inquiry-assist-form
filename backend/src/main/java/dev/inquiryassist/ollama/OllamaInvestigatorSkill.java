package dev.inquiryassist.ollama;

import dev.inquiryassist.core.AccessScope;
import dev.inquiryassist.core.AiResult;
import dev.inquiryassist.core.AiUnavailableException;
import dev.inquiryassist.core.Citation;
import dev.inquiryassist.core.KnowledgeSearch;
import dev.inquiryassist.core.StructuredInquiry;
import dev.inquiryassist.core.Usage;
import dev.inquiryassist.core.skill.EvidenceLocator;
import dev.inquiryassist.core.skill.Finding;
import dev.inquiryassist.core.skill.Investigation;
import dev.inquiryassist.core.skill.InvestigatorSkill;
import dev.inquiryassist.core.skill.ProgressListener;
import dev.inquiryassist.core.skill.Skill;
import dev.inquiryassist.inquiry.InquiryProperties;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * 調査を、手元のモデル（Ollama）で動かす実装。
 *
 * <p>モデルに検索（{@code search}）と読み取り（{@code read}）の道具を渡し、モデルが道具を呼ばずに結果の JSON を
 * 出力するまで繰り返す。道具はどちらも読み取り専用で、{@link KnowledgeSearch} を通るため、閲覧範囲はそこで適用される。
 *
 * <p>回数、1回に読む量、返す事実の数の上限は、モデルに任せず、ここで決める。行番号はモデルに書かせず、
 * 引用から {@link EvidenceLocator} が求める。出典が実在するかは、ここでは判定しない（呼び出し側が照合する）。
 */
@Component
@ConditionalOnProperty(name = "inquiry.skills.investigator.provider", havingValue = "ollama")
public class OllamaInvestigatorSkill implements InvestigatorSkill {

    /** モデルを呼ぶ回数の上限（道具を渡して呼ぶ回数）。超えたら、道具を渡さずに結果だけを求める。 */
    static final int MAX_ROUNDS = 8;

    /** 1回の応答で実行する道具の数の上限。 */
    static final int MAX_CALLS_PER_ROUND = 4;

    /** 検索1回で返す該当箇所の上限。 */
    static final int SEARCH_LIMIT = 12;

    /** 読み取り1回で返す行数の上限。 */
    static final int MAX_READ_LINES = 120;

    /** 道具の結果に入れる1行の長さの上限。 */
    static final int MAX_LINE_CHARS = 300;

    /** 返す事実の数の上限。 */
    static final int MAX_FINDINGS = 5;

    /** 回数の上限に達したときに足す指示。 */
    static final String LIMIT_INSTRUCTION = "道具を使える回数の上限に達しました。ここまでに分かったことだけで、指定の形の JSON を出力してください。";

    /** 資料を1度も調べずに結果を出したときに足す指示。 */
    static final String SEARCH_FIRST_INSTRUCTION = "資料を調べずに答えることはできません。まず search で資料を調べてください。";

    private static final String PROGRESS_SEARCH = "資料を検索しています";

    private final OllamaClient client;
    private final JsonMapper json;
    private final InquiryProperties.SkillSettings settings;
    private final KnowledgeSearch knowledge;
    private final String system;

    /**
     * @param client Ollama のクライアント
     * @param json JSON の読み書き
     * @param properties スキルの設定
     * @param knowledge 一次情報の検索と読み取り
     */
    public OllamaInvestigatorSkill(
            OllamaClient client, JsonMapper json, InquiryProperties properties, KnowledgeSearch knowledge) {
        this.client = client;
        this.json = json;
        this.settings = properties.skill(Skill.INVESTIGATOR);
        this.knowledge = knowledge;
        this.system = Prompts.systemOf(Skill.INVESTIGATOR, settings.promptVersion());
    }

    /** 1回の調査の間だけ持つ状態。 */
    private final class Session {
        final AccessScope scope;
        final ProgressListener progress;
        final EvidenceLocator locator = new EvidenceLocator();
        final Set<String> searched = new HashSet<>();
        final List<Map<String, Object>> messages = new ArrayList<>();
        final List<String> reasons = new ArrayList<>();
        final List<String> details = new ArrayList<>();
        final List<String> rawOutputs = new ArrayList<>();
        long input;
        long output;
        String lastProgress;
        /** 検索か読み取りが、1度でも実行されたか。 */
        boolean looked;
        /** 調べずに結果を出したことを、1度指摘したか。 */
        boolean toldToSearch;

        Session(AccessScope scope, ProgressListener progress) {
            this.scope = scope;
            this.progress = progress;
        }

        Usage usage() {
            return new Usage(settings.model(), settings.promptVersion(), input, output, 0, false);
        }

        /** 同じ文言を続けて通知しない（検索を3回続けても、画面には1行だけ出す）。 */
        void notifyProgress(String message) {
            if (!message.equals(lastProgress)) {
                lastProgress = message;
                progress.onProgress(message);
            }
        }
    }

    @Override
    public AiResult<Investigation> investigate(
            StructuredInquiry inquiry, AccessScope scope, ProgressListener progress) {
        if (!inquiry.category().receivesZeroAnswer()) {
            throw new IllegalArgumentException("0次回答を出さない種別: " + inquiry.category());
        }
        Session session = new Session(scope, progress);
        session.messages.add(message("system", system));
        session.messages.add(message("user", userMessage(inquiry)));

        for (int round = 1; round <= MAX_ROUNDS; round++) {
            OllamaClient.ToolReply reply = call(session, TOOLS);
            if (reply.toolCalls().isEmpty()) {
                if (!session.looked && !session.toldToSearch) {
                    // 資料を見ずに出した結果は受け取らない。1度だけ、調べるよう求める
                    session.toldToSearch = true;
                    session.messages.add(message("assistant", reply.content()));
                    session.messages.add(message("user", SEARCH_FIRST_INSTRUCTION));
                    continue;
                }
                Investigation result = tryParse(session, reply.content());
                if (result != null) {
                    return new AiResult<>(result, session.usage());
                }
                requestAgain(session, reply);
                continue;
            }
            session.messages.add(reply.assistantMessage());
            for (int i = 0; i < reply.toolCalls().size(); i++) {
                OllamaClient.ToolCall toolCall = reply.toolCalls().get(i);
                Map<String, Object> result = i < MAX_CALLS_PER_ROUND
                        ? run(session, toolCall)
                        : Map.of("error", "1回に呼べる道具は " + MAX_CALLS_PER_ROUND + " つまでです。必要なら次に呼び直してください");
                Map<String, Object> toolMessage = message("tool", json.writeValueAsString(result));
                toolMessage.put("tool_name", toolCall.name());
                session.messages.add(toolMessage);
            }
        }

        // 回数の上限。道具を渡さずに、ここまでの結果だけを求める
        session.messages.add(message("user", LIMIT_INSTRUCTION));
        while (true) {
            OllamaClient.ToolReply reply = call(session, List.of());
            Investigation result = tryParse(session, reply.content());
            if (result != null) {
                return new AiResult<>(result, session.usage());
            }
            requestAgain(session, reply);
        }
    }

    private OllamaClient.ToolReply call(Session session, List<Map<String, Object>> tools) {
        OllamaClient.ToolReply reply;
        try {
            reply = client.chatWithTools(
                    settings.model(), session.messages, tools, settings.temperature(), settings.think(), settings.timeout());
        } catch (AiUnavailableException e) {
            // ここまでの呼び出しの費用を、失敗にも引き継ぐ
            throw new AiUnavailableException(e.getMessage(), e.getCause(), session.usage());
        }
        session.input += reply.inputTokens();
        session.output += reply.outputTokens();
        return reply;
    }

    /** 読めなかった出力を会話に足し、指定の形での出力を求める。読めないのが2回目なら失敗させる。 */
    private void requestAgain(Session session, OllamaClient.ToolReply reply) {
        session.rawOutputs.add(reply.content());
        if (session.reasons.size() >= 2) {
            throw new UnreadableOutputException(session.reasons, session.details, session.rawOutputs, session.usage());
        }
        session.messages.add(message("assistant", reply.content()));
        session.messages.add(message("user", JsonSkillCall.RETRY_INSTRUCTION));
    }

    /** 読めて形も合っていれば結果を返す。そうでなければ理由を記録して null を返す。 */
    private Investigation tryParse(Session session, String content) {
        String reason;
        String detail = null;
        if (content == null || content.isBlank()) {
            reason = "出力が空";
        } else {
            JsonNode node = JsonSkillCall.readJson(json, content);
            if (node == null) {
                reason = "JSON として読めない";
            } else if (!node.isObject()) {
                reason = "JSON のオブジェクトでない";
            } else if (!node.path("findings").isArray()) {
                reason = "形が指定と違う";
                detail = "findings が配列でない";
            } else {
                return toInvestigation(session, node);
            }
        }
        session.reasons.add(reason);
        session.details.add(detail == null ? reason : reason + "（" + detail + "）");
        return null;
    }

    /**
     * モデルの出力を調査の結果にする。出力は信用せず、事実の文が空のもの、根拠を1件も作れないものを捨て、
     * 事実は先頭の {@link #MAX_FINDINGS} 件にする。行番号は、調査の間に見た行から求める。
     *
     * <p>求められても資料を1度も調べなかった場合は、事実をすべて捨てる。見た資料がない以上、
     * どの事実にも根拠がありえない。
     */
    private Investigation toInvestigation(Session session, JsonNode node) {
        List<Finding> findings = new ArrayList<>();
        for (JsonNode item : node.path("findings")) {
            if (!session.looked || findings.size() >= MAX_FINDINGS) {
                break;
            }
            JsonNode statement = item.path("statement");
            if (!statement.isString() || statement.asString().isBlank()) {
                continue;
            }
            List<Citation> evidence = new ArrayList<>();
            for (JsonNode e : item.path("evidence")) {
                session.locator
                        .locate(text(e, "repository"), text(e, "path"), text(e, "quote"))
                        .ifPresent(evidence::add);
            }
            if (!evidence.isEmpty()) {
                findings.add(new Finding(statement.asString().strip(), evidence));
            }
        }
        return new Investigation(findings, strings(node.path("notFound")), strings(node.path("nextChecks")));
    }

    // ---- 道具 ----

    private Map<String, Object> run(Session session, OllamaClient.ToolCall call) {
        return switch (call.name()) {
            case "search" -> search(session, call.arguments());
            case "read" -> read(session, call.arguments());
            default -> Map.of("error", "その名前の道具はありません。使えるのは search と read です");
        };
    }

    private Map<String, Object> search(Session session, JsonNode arguments) {
        String query = text(arguments, "query");
        if (query == null || query.isBlank()) {
            return Map.of("error", "query に検索する語を入れてください");
        }
        session.notifyProgress(PROGRESS_SEARCH);
        session.looked = true;
        boolean repeated = !session.searched.add(query.strip().toLowerCase(java.util.Locale.ROOT));
        List<KnowledgeSearch.SearchHit> hits = knowledge.search(query, session.scope, SEARCH_LIMIT);
        List<Map<String, Object>> rows = new ArrayList<>();
        for (KnowledgeSearch.SearchHit hit : hits) {
            session.locator.remember(hit.sourceType(), hit.repository(), hit.path(), hit.line(), hit.snippet(), null);
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("sourceType", hit.sourceType().name());
            row.put("repository", hit.repository());
            row.put("path", hit.path());
            row.put("line", hit.line());
            row.put("text", clip(hit.snippet()));
            rows.add(row);
        }
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("hits", rows);
        if (repeated) {
            result.put("note", "この語では検索済みです。別の語で検索するか、調べ終えてください");
        } else if (rows.isEmpty()) {
            result.put("note", "該当する行はありませんでした。別の語で検索してください");
        } else if (rows.size() >= SEARCH_LIMIT) {
            result.put("note", "該当が多いため、先頭の " + SEARCH_LIMIT + " 件だけを返しています。語を絞ってください");
        }
        return result;
    }

    private Map<String, Object> read(Session session, JsonNode arguments) {
        String repository = text(arguments, "repository");
        String path = text(arguments, "path");
        if (repository == null || repository.isBlank() || path == null || path.isBlank()) {
            return Map.of("error", "repository と path を、search の結果に出てきた値で指定してください");
        }
        int start = Math.max(1, integer(arguments, "startLine", 1));
        int end = integer(arguments, "endLine", start + MAX_READ_LINES - 1);
        if (end < start) {
            end = start;
        }
        end = Math.min(end, start + MAX_READ_LINES - 1);

        KnowledgeSearch.FileExcerpt excerpt;
        try {
            excerpt = knowledge.read(repository, path, start, end, session.scope);
        } catch (SecurityException e) {
            return Map.of("error", "この資料は閲覧できません");
        } catch (RuntimeException e) {
            return Map.of("error", "ファイルが見つからないか、行の範囲が正しくありません。search の結果に出てきた repository と path を使ってください");
        }
        session.looked = true;
        Citation.SourceType type = session.locator.typeOf(repository);
        session.notifyProgress(
                switch (type) {
                    case CODE -> "コードを読んでいます";
                    case FAQ -> "FAQを読んでいます";
                    case GLOSSARY -> "用語対応表を確認しています";
                    case DOCUMENT -> "資料を読んでいます";
                });
        List<Map<String, Object>> lines = new ArrayList<>();
        int number = excerpt.startLine();
        for (String line : excerpt.text().split("\n", -1)) {
            session.locator.remember(null, repository, path, number, line, excerpt.revision());
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("line", number);
            row.put("text", clip(line));
            lines.add(row);
            number++;
        }
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("repository", repository);
        result.put("path", path);
        result.put("lines", lines);
        return result;
    }

    private static final List<Map<String, Object>> TOOLS = List.of(
            tool(
                    "search",
                    "資料（ソースコード、FAQ、用語対応表）を語で検索する。空白で区切った語のどれかを含む行を返す。",
                    Map.of("query", Map.of("type", "string", "description", "検索する語。1個から3個を空白で区切る")),
                    List.of("query")),
            tool(
                    "read",
                    "search で見つけたファイルを、行の範囲を指定して読む。1回に読めるのは " + MAX_READ_LINES + " 行まで。",
                    Map.of(
                            "repository", Map.of("type", "string", "description", "search の結果の repository"),
                            "path", Map.of("type", "string", "description", "search の結果の path"),
                            "startLine", Map.of("type", "integer", "description", "読み始める行（1始まり）"),
                            "endLine", Map.of("type", "integer", "description", "読み終える行")),
                    List.of("repository", "path", "startLine", "endLine")));

    private static Map<String, Object> tool(
            String name, String description, Map<String, Object> properties, List<String> required) {
        return Map.of(
                "type",
                "function",
                "function",
                Map.of(
                        "name",
                        name,
                        "description",
                        description,
                        "parameters",
                        Map.of("type", "object", "properties", properties, "required", required)));
    }

    // ---- 補助 ----

    private static String userMessage(StructuredInquiry inquiry) {
        StringBuilder user = new StringBuilder("## 本人の問い\n")
                .append(inquiry.summary())
                .append("\n\n## 種別\n")
                .append(inquiry.category().name())
                .append("\n\n## 対象のシステム\n")
                .append(inquiry.targetSystem() == null ? "なし" : inquiry.targetSystem());
        if (inquiry.whatHappened() != null && !inquiry.whatHappened().isBlank()) {
            user.append("\n\n## 起きていること\n").append(inquiry.whatHappened());
        }
        if (inquiry.expectedResult() != null && !inquiry.expectedResult().isBlank()) {
            user.append("\n\n## 期待していた結果\n").append(inquiry.expectedResult());
        }
        return user.toString();
    }

    private static Map<String, Object> message(String role, String content) {
        Map<String, Object> message = new LinkedHashMap<>();
        message.put("role", role);
        message.put("content", content);
        return message;
    }

    private static String clip(String line) {
        return line.length() > MAX_LINE_CHARS ? line.substring(0, MAX_LINE_CHARS) + "…" : line;
    }

    private static String text(JsonNode node, String field) {
        JsonNode value = node.path(field);
        return value.isString() ? value.asString() : null;
    }

    /** 整数の引数を読む。モデルは数を文字列で返すことがあるため、文字列も受ける。 */
    private static int integer(JsonNode node, String field, int fallback) {
        JsonNode value = node.path(field);
        if (value.isNumber()) {
            return value.asInt();
        }
        if (value.isString()) {
            try {
                return Integer.parseInt(value.asString().strip());
            } catch (NumberFormatException ignored) {
                return fallback;
            }
        }
        return fallback;
    }

    private static List<String> strings(JsonNode array) {
        List<String> result = new ArrayList<>();
        for (JsonNode item : array) {
            if (item.isString() && !item.asString().isBlank()) {
                result.add(item.asString().strip());
            }
        }
        return result;
    }
}
