package dev.inquiryassist.eval;

/**
 * 落ちた条件1件。
 *
 * @param condition 条件の名前
 * @param detail 落ちた理由（出力の一部を含みうる。{@code holdout} の結果には出さない）
 */
record Failure(String condition, String detail) {}
