package dev.inquiryassist.inquiry;

import java.util.List;

/**
 * オペレータ向けの一覧。
 *
 * @param unreadCount 未読の件数（絞り込みにかかわらず全体の件数）
 * @param items 一覧。新しい順で、緊急度が {@code BLOCKED} の件を先頭に寄せる
 */
public record OperatorList(long unreadCount, List<OperatorItem> items) {}
