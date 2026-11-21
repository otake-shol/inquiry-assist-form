package dev.inquiryassist.inquiry;

import java.util.List;
import java.util.Map;

/**
 * オペレータ向けの一覧。
 *
 * @param unreadCount 未読の件数（絞り込みにかかわらず全体の件数）
 * @param counts 対応状況ごとの件数（絞り込みにかかわらず全体の件数。3つの値をすべて含む）
 * @param items 一覧（新しい順）
 */
public record OperatorList(long unreadCount, Map<HandlingStatus, Long> counts, List<OperatorItem> items) {}
