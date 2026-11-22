package dev.inquiryassist.core.intake;

/**
 * 本人の答え（リクエストの内容）。答え方は4つのうち1つだけを指定する。
 *
 * @param questionId 答える質問の識別子
 * @param optionId 選んだ選択肢
 * @param text 文章で答える場合の文章
 * @param unknown 「わからない」と答える場合は true
 * @param skipped 補足を書かずに進む場合は true
 */
public record IntakeReply(String questionId, String optionId, String text, boolean unknown, boolean skipped) {}
