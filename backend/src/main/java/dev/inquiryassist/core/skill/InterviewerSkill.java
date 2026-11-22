package dev.inquiryassist.core.skill;

import dev.inquiryassist.core.AccessScope;
import dev.inquiryassist.core.AiRefusalException;
import dev.inquiryassist.core.AiResult;
import dev.inquiryassist.core.AiUnavailableException;
import dev.inquiryassist.core.FunctionOption;
import dev.inquiryassist.core.InquiryType;
import dev.inquiryassist.core.SystemOption;
import dev.inquiryassist.core.intake.IntakeQuestion;
import java.util.List;

/**
 * 聞き手。足りない情報を、非エンジニアが答えられる質問にする。用語対応表とFAQの軽い検索を使える。
 *
 * <p>仕事は2つ。（1）置き場所の質問が終わり、マスの設定に詳しい質問がないときに、質問を作る（{@link #ask}）。
 * （2）マスの設定に詳しい質問があり、本人が文章を書いているときに、設定の質問の答えが文章に既に書かれているかを
 * 読み取る（{@link #prefill}）。どちらも、失敗しても本人を止めず、聞き手が使えない場合と同じ聞き方で先へ進む。
 */
public interface InterviewerSkill {

    /**
     * 詳しい質問を作る（0〜2個）。システムや機能が決まっている場合は、それを尋ねる質問を出さない。
     *
     * @param utterance 本人が書いた文章。書いていなければ {@code null}
     * @param type 種類。決まっていなければ {@code null}
     * @param system システム。決まっていなければ {@code null}
     * @param function 機能。決まっていなければ {@code null}
     * @param scope 投稿者の閲覧範囲
     * @param language 本人の言語。質問と選択肢はこの言語で書く
     * @return 詳しい質問
     * @throws AiRefusalException モデルが回答を拒否した場合
     * @throws AiUnavailableException モデルを呼び出せなかった場合
     */
    AiResult<Interview> ask(
            String utterance,
            InquiryType type,
            SystemOption system,
            FunctionOption function,
            AccessScope scope,
            String language);

    /**
     * 設定の質問のうち、答えが本人の文章に既に書かれているものを読み取る。書かれていない質問は含めない。
     * 文章がないときは呼ばない（押して答えただけの入口では、AI を呼ばない）。
     *
     * @param utterance 本人が書いた文章
     * @param questions まだ答えていない設定の質問
     * @return 読み取った答え。何も読み取れなければ空
     * @throws AiRefusalException モデルが回答を拒否した場合
     * @throws AiUnavailableException モデルを呼び出せなかった場合
     */
    AiResult<Prefill> prefill(String utterance, List<IntakeQuestion> questions);
}
