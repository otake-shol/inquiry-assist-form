package dev.inquiryassist.inquiry;

import dev.inquiryassist.core.AiRefusalException;
import dev.inquiryassist.core.AiResult;
import dev.inquiryassist.core.AiUnavailableException;
import dev.inquiryassist.core.Usage;
import dev.inquiryassist.core.skill.Skill;
import dev.inquiryassist.core.skill.SkillUsage;
import dev.inquiryassist.core.skill.Translation;
import dev.inquiryassist.core.skill.TranslatorSkill;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Consumer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.stereotype.Service;

/**
 * 担当者のための訳（契約書「訳」、{@code docs/skill-architecture.md}「訳のスキル」）。本人の言語で書かれた文章を、見る人の言語に訳す。
 * 担当者が頼んだときだけ、同期で呼ぶ。状態は変えず、何も送らない。
 *
 * <p>訳は問い合わせに保存し、同じ文章を2度訳さない（訳す先の言語と、原文が同じなら、保存した訳を返す）。足りない分だけをスキルに渡し、
 * すべて保存済みなら、スキルを呼ばない（利用量も記録しない）。返ってきた文章の数が、渡した数と同じかは、ここで確かめる
 * （スキルの実装によらず、同じ確かめを通すため）。違えば、{@code AI_UNAVAILABLE} として扱う。
 *
 * <p>保存（訳と利用量）が、ほかの操作と競合しても、訳は返す。
 */
@Service
public class TranslationService {

    private static final Logger log = LoggerFactory.getLogger(TranslationService.class);
    private static final int SAVE_ATTEMPTS = 3;

    /** スキルが返した訳（原文のキーから訳への対応）と、その利用量。 */
    private record Fresh(Map<String, String> translated, Usage usage, long millis) {}

    private final InquiryRepository repository;
    private final TranslatorSkill translator;
    private final InquiryCommitter committer;
    private final SkillImplementations implementations;

    /**
     * @param repository 問い合わせ記録の保存先
     * @param translator 訳
     * @param committer 訳と利用量の保存
     * @param implementations スキルごとの実装（失敗した呼び出しの利用量の補完に使う）
     */
    TranslationService(
            InquiryRepository repository,
            TranslatorSkill translator,
            InquiryCommitter committer,
            SkillImplementations implementations) {
        this.repository = repository;
        this.translator = translator;
        this.committer = committer;
        this.implementations = implementations;
    }

    /**
     * 本人の言語で書かれた文章を、見る人の言語に訳す。
     *
     * @param id 問い合わせの識別子
     * @param to 訳す先の言語（見る人の言語）
     * @return 訳。訳す文章がなければ、{@code items} は空
     * @throws InquiryNotFoundException 存在しない、または担当者に渡したことがない場合
     * @throws InvalidStateException 本人の言語と、訳す先の言語が同じ場合
     * @throws AiRefusalException モデルが回答を拒否した場合
     * @throws AiUnavailableException モデルを呼び出せなかった場合、または返ってきた文章の数が合わない場合
     */
    public TranslationResult translate(UUID id, String to) {
        InquiryEntity inquiry = repository
                .findById(id)
                .filter(e -> e.handedOverAt != null)
                .orElseThrow(() -> new InquiryNotFoundException(id));
        String from = inquiry.language;
        if (from.equalsIgnoreCase(to)) {
            throw new InvalidStateException("本人の言語と同じ言語なので、訳せません");
        }
        // 訳す文章（あるものだけ。契約書の順）
        Map<String, String> originals = new LinkedHashMap<>();
        put(originals, "utterance", inquiry.utterance());
        if (inquiry.structured() != null) {
            put(originals, "structured.summary", inquiry.structured().summary());
        }
        put(originals, "answer.askerExplanation", inquiry.askerExplanation);
        for (InquiryEntity.StoredMessage m : inquiry.messages) {
            if (m.from() == MessageAuthor.ASKER) {
                put(originals, "message:" + m.id(), m.body());
            }
        }

        Map<String, String> stored = new HashMap<>(inquiry.translations.getOrDefault(to, Map.of()));
        List<String> missing = new ArrayList<>(new LinkedHashSet<>(originals.values().stream()
                .filter(text -> !stored.containsKey(hashOf(text)))
                .toList()));
        if (!missing.isEmpty()) {
            Fresh result = translateMissing(id, missing, from, to);
            Map<String, String> fresh = result.translated();
            stored.putAll(fresh);
            // 訳と利用量を、1回の保存でまとめて保存する
            saveQuietly(id, entity -> {
                entity.addUsage(Skill.TRANSLATOR, result.usage(), result.millis());
                Map<String, Map<String, String>> all = new LinkedHashMap<>(entity.translations);
                Map<String, String> forLanguage = new LinkedHashMap<>(all.getOrDefault(to, Map.of()));
                forLanguage.putAll(fresh);
                all.put(to, forLanguage);
                entity.translations = all;
            });
        }
        List<TranslationResult.Item> items = originals.entrySet().stream()
                .map(e -> new TranslationResult.Item(e.getKey(), e.getValue(), stored.get(hashOf(e.getValue()))))
                .toList();
        return new TranslationResult(from, to, items);
    }

    /** 足りない文章をスキルで訳す。数が合わなければ失敗として扱う。利用量は、成功も失敗も記録する。 */
    private Fresh translateMissing(UUID id, List<String> missing, String from, String to) {
        long start = System.nanoTime();
        AiResult<Translation> result;
        try {
            result = translator.translate(List.copyOf(missing), from, to);
        } catch (AiRefusalException | AiUnavailableException e) {
            Usage usage = e instanceof AiUnavailableException u ? u.usage() : ((AiRefusalException) e).usage();
            saveQuietly(id, entity -> entity.addUsage(new SkillUsage(Skill.TRANSLATOR, orUnknown(usage), true, millisSince(start))));
            throw e;
        }
        long millis = millisSince(start);
        if (result.value().texts().size() != missing.size()) {
            // どの訳がどの原文のものか分からなくなるので、使わない
            saveQuietly(id, entity -> entity.addUsage(new SkillUsage(Skill.TRANSLATOR, result.usage(), true, millis)));
            throw new AiUnavailableException("訳の数が、渡した文章の数と合いません", null, result.usage());
        }
        Map<String, String> fresh = new LinkedHashMap<>();
        for (int i = 0; i < missing.size(); i++) {
            fresh.put(hashOf(missing.get(i)), result.value().texts().get(i));
        }
        return new Fresh(fresh, result.usage(), millis);
    }

    private Usage orUnknown(Usage usage) {
        return usage != null ? usage : new Usage("unknown", "unknown", 0, 0, 0, implementations.isMock(Skill.TRANSLATOR));
    }

    private static long millisSince(long startNanos) {
        return (System.nanoTime() - startNanos) / 1_000_000L;
    }

    private static void put(Map<String, String> originals, String key, String text) {
        if (text != null && !text.isBlank()) {
            originals.put(key, text);
        }
    }

    /** 原文のキー（SHA-256 の16進数）。原文そのものをキーにすると長くなるため。 */
    static String hashOf(String original) {
        try {
            return HexFormat.of()
                    .formatHex(MessageDigest.getInstance("SHA-256").digest(original.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    /** 保存する。ほかの操作と競合したら、最大3回やり直し、それでも競合する場合は諦める（訳は返す）。 */
    private void saveQuietly(UUID id, Consumer<InquiryEntity> change) {
        for (int attempt = 1; attempt <= SAVE_ATTEMPTS; attempt++) {
            try {
                committer.update(id, entity -> {
                    change.accept(entity);
                    return new InquiryCommitter.Update(null, InquiryCommitter.Publish.NONE);
                });
                return;
            } catch (OptimisticLockingFailureException e) {
                log.info("訳の保存が競合しました attempt={} id={}", attempt, id);
            } catch (RuntimeException e) {
                log.warn("訳を保存できませんでした id={} cause={}", id, e.getClass().getSimpleName());
                return;
            }
        }
    }
}
