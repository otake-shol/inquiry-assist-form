package dev.inquiryassist.core.skill;

import dev.inquiryassist.core.AiRefusalException;
import dev.inquiryassist.core.AiResult;
import dev.inquiryassist.core.AiUnavailableException;
import java.util.List;

/**
 * 訳。本人の言語で書かれた文章を、担当者の言語に訳す。道具を持たない。基準は、原文の意味を変えない、足さない、省かないこと。
 * 判断も書き直しもしない。訳は必ず原文に添えて出し、原文を置き換えない。
 */
public interface TranslatorSkill {

    /**
     * 文章を訳す。
     *
     * @param texts 訳す文章
     * @param from 原文の言語
     * @param to 訳す先の言語
     * @return 訳した文章（渡した順。数が合うかは、呼ぶ側のコードが確かめる）
     * @throws AiRefusalException モデルが回答を拒否した場合
     * @throws AiUnavailableException モデルを呼び出せなかった場合
     */
    AiResult<Translation> translate(List<String> texts, String from, String to);
}
