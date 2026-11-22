package dev.inquiryassist.core.lifecycle;

/** 区間。いま埋めようとしている不足の種類（{@code framework.md} の区間に、受付と終わりを加えたもの）。 */
public enum Phase {
    /** 受付。入口の質問に答え、問いを確かめる。 */
    INTAKE,
    /** 分かる。知識の不足。 */
    KNOW,
    /** 決まる。意図どおりか、受けるか、採るかの判断。 */
    DECIDE,
    /** 行われる。作業。 */
    ACT,
    /** 終わり。 */
    CLOSED
}
