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
    EXPLAINER("explainer");

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
