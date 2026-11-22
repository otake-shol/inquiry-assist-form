package dev.inquiryassist.inquiry;

import dev.inquiryassist.core.i18n.LanguageResolver;
import dev.inquiryassist.core.intake.IntakeCatalog;
import dev.inquiryassist.core.intake.IntakePlanner;
import dev.inquiryassist.core.intake.IntakeTexts;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 言語ごとの、種類・システム・機能・マスの設定の名前（{@link IntakeCatalog}）と、入口の質問を決める処理
 * （{@link IntakePlanner}）。見る人の言語ごとに、名前と、入口の決まった質問の文言が変わるため、言語ごとに持つ。
 * 設定にない言語は、既定の言語として扱う。
 */
public final class IntakeLocales {

    private final LanguageResolver resolver;
    private final Map<String, IntakeCatalog> catalogs = new LinkedHashMap<>();
    private final Map<String, IntakePlanner> planners = new LinkedHashMap<>();

    /**
     * @param properties 言語と名前の設定
     */
    public IntakeLocales(InquiryProperties properties) {
        this.resolver = new LanguageResolver(properties.languages());
        for (String language : resolver.available()) {
            IntakeCatalog catalog = properties.catalog(language);
            catalogs.put(language, catalog);
            planners.put(language, new IntakePlanner(catalog, IntakeTexts.of(language)));
        }
    }

    /**
     * @return 見る人の言語を決める処理
     */
    public LanguageResolver resolver() {
        return resolver;
    }

    /**
     * @return 既定の言語
     */
    public String defaultLanguage() {
        return resolver.defaultLanguage();
    }

    /**
     * @param language 言語。設定にない言語は既定の言語
     * @return その言語の名前の一覧
     */
    public IntakeCatalog catalog(String language) {
        return catalogs.getOrDefault(language, catalogs.get(defaultLanguage()));
    }

    /**
     * @param language 言語。設定にない言語は既定の言語
     * @return その言語の質問を決める処理
     */
    public IntakePlanner planner(String language) {
        return planners.getOrDefault(language, planners.get(defaultLanguage()));
    }

    /**
     * @param language 言語。設定にない言語は既定の言語
     * @return その言語の、入口の決まった質問などの文言
     */
    public IntakeTexts texts(String language) {
        return IntakeTexts.of(catalogs.containsKey(language) ? language : defaultLanguage());
    }
}
