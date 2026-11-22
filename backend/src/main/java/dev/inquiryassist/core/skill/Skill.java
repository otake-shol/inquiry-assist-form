package dev.inquiryassist.core.skill;

/** AI に任せるスキル。識別子は利用量の記録（{@code usages[].step}）とプロンプトの置き場所に使う。 */
public enum Skill {
    /** 種別を判定し、対象システムを推測する。 */
    RECEPTION("reception"),
    /** 足りない情報を、非エンジニアが答えられる質問にする。 */
    INTERVIEWER("interviewer"),
    /** 言い直しの文章と構造化データを作る。作り直しも担う。 */
    RESTATER("restater"),
    /** 一次情報を調べ、根拠つきの事実を集める。 */
    INVESTIGATOR("investigator"),
    /** 確かめられた事実だけから、本人向けの説明を書く。 */
    EXPLAINER("explainer"),
    /** 確かめられた事実から、担当者向けの判断の案と手順の案を作る。 */
    ADVISOR("advisor"),
    /** 担当者が本人に送る文章（聞き返し、結果）の下書きを作る。 */
    DRAFTER("drafter"),
    /** 本人の言語で書かれた文章を、担当者の言語に訳す。 */
    TRANSLATOR("translator");

    private final String id;

    Skill(String id) {
        this.id = id;
    }

    /**
     * @return 識別子（小文字）
     */
    public String id() {
        return id;
    }
}
