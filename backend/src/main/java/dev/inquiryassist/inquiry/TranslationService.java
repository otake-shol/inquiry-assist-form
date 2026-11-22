package dev.inquiryassist.inquiry;

import dev.inquiryassist.core.AiRefusalException;
import dev.inquiryassist.core.AiResult;
import dev.inquiryassist.core.AiUnavailableException;
import dev.inquiryassist.core.Usage;
import dev.inquiryassist.core.skill.Skill;
import dev.inquiryassist.core.skill.SkillUsage;
import dev.inquiryassist.core.skill.Translation;
import dev.inquiryassist.core.skill.TranslatorSkill;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.stereotype.Service;

/**
 * 担当者のための訳（契約書「訳」、{@code docs/skill-architecture.md}「訳のスキル」）。本人の言語で書かれた文章を、見る人の言語に訳す。
 * 担当者が頼んだときだけ、同期で呼ぶ。状態は変えず、何も送らない。
 *
 * <p>訳は保存しない。頼まれるたびに、その時点の文章を訳す（同じ文章が複数あれば、1度だけスキルに渡す）。返ってきた文章の数が、
 * 渡した数と同じかは、ここで確かめる（スキルの実装によらず、同じ確かめを通すため）。違えば、{@code AI_UNAVAILABLE} として扱う。
 * 利用量は、成功も失敗も記録する。記録の保存が、ほかの操作と競合しても、訳は返す。
 */
@Service
public class TranslationService {

    private static final Logger log = LoggerFactory.getLogger(TranslationService.class);
    private static final int SAVE_ATTEMPTS = 3;

    private final InquiryRepository repository;
    private final TranslatorSkill translator;
    private final InquiryCommitter committer;
    private final SkillImplementations implementations;

    /**
     * @param repository 問い合わせ記録の保存先
     * @param translator 訳
     * @param committer 利用量の保存
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
        if (originals.isEmpty()) {
            return new TranslationResult(from, to, List.of());
        }

        // 同じ文章は1度だけ訳す
        List<String> distinct = new ArrayList<>(new LinkedHashSet<>(originals.values()));
        List<String> translated = translate(id, distinct, from, to);
        Map<String, String> byOriginal = new HashMap<>();
        for (int i = 0; i < distinct.size(); i++) {
            byOriginal.put(distinct.get(i), translated.get(i));
        }
        List<TranslationResult.Item> items = originals.entrySet().stream()
                .map(e -> new TranslationResult.Item(e.getKey(), e.getValue(), byOriginal.get(e.getValue())))
                .toList();
        return new TranslationResult(from, to, items);
    }

    /** 文章をスキルで訳す。数が合わなければ失敗として扱う。利用量は、成功も失敗も記録する。 */
    private List<String> translate(UUID id, List<String> texts, String from, String to) {
        long start = System.nanoTime();
        AiResult<Translation> result;
        try {
            result = translator.translate(List.copyOf(texts), from, to);
        } catch (AiRefusalException | AiUnavailableException e) {
            Usage usage = e instanceof AiUnavailableException u ? u.usage() : ((AiRefusalException) e).usage();
            recordUsage(id, new SkillUsage(Skill.TRANSLATOR, orUnknown(usage), true, millisSince(start)));
            throw e;
        }
        long millis = millisSince(start);
        if (result.value().texts().size() != texts.size()) {
            // どの訳がどの原文のものか分からなくなるので、使わない
            recordUsage(id, new SkillUsage(Skill.TRANSLATOR, result.usage(), true, millis));
            throw new AiUnavailableException("訳の数が、渡した文章の数と合いません", null, result.usage());
        }
        recordUsage(id, new SkillUsage(Skill.TRANSLATOR, result.usage(), false, millis));
        return result.value().texts();
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

    /** 利用量を記録する。ほかの操作と競合したら、最大3回やり直し、それでも競合する場合は諦める（訳は返す）。 */
    private void recordUsage(UUID id, SkillUsage usage) {
        for (int attempt = 1; attempt <= SAVE_ATTEMPTS; attempt++) {
            try {
                committer.update(id, entity -> {
                    entity.addUsage(usage, implementations);
                    return new InquiryCommitter.Update(null, InquiryCommitter.Publish.NONE);
                });
                return;
            } catch (OptimisticLockingFailureException e) {
                log.info("訳の利用量の保存が競合しました attempt={} id={}", attempt, id);
            } catch (RuntimeException e) {
                log.warn("訳の利用量を保存できませんでした id={} cause={}", id, e.getClass().getSimpleName());
                return;
            }
        }
    }
}
