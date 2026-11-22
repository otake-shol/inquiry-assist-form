package dev.inquiryassist.core.intake;

/**
 * 答え終えた質問1問。聞いた順に並べて保存する。
 *
 * @param questionId 質問の識別子
 * @param kind 質問の種類
 * @param text 質問文（聞いた時点の文言）
 * @param answer 答え
 */
public record IntakeEntry(String questionId, QuestionKind kind, String text, IntakeAnswer answer) {}
