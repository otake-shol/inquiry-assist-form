package dev.inquiryassist.inquiry;

/** 先例を記録した人。 */
public enum RecordedBy {
    /** 仕様の持ち主が、相談に答えて記録した。 */
    OWNER,
    /** 担当者が、決めた人の代わりに記録した（会議や別の連絡手段で下された決定）。持ち主が確かめるか直すまで、確かめられていない。 */
    OPERATOR
}
