package dev.inquiryassist;

import dev.inquiryassist.inquiry.SkillImplementations;
import org.springframework.beans.factory.config.BeanFactoryPostProcessor;
import org.springframework.beans.factory.config.ConfigurableListableBeanFactory;
import org.springframework.context.EnvironmentAware;
import org.springframework.context.annotation.Profile;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

/**
 * 本番（{@code production} プロファイル）でモックを含む構成を起動できないようにする起動時検査。
 *
 * <p>他の Bean（DB 接続など）より前に評価するため {@link BeanFactoryPostProcessor} にしている。
 */
@Component
@Profile("production")
public class ProductionMockGuard implements BeanFactoryPostProcessor, EnvironmentAware {

    private Environment environment;

    /**
     * @param environment 有効なプロファイルの判定に使う
     */
    @Override
    public void setEnvironment(Environment environment) {
        this.environment = environment;
    }

    /**
     * @param beanFactory Bean ファクトリ
     * @throws IllegalStateException スキルか一次情報の検索に1つでもモックがある場合
     */
    @Override
    public void postProcessBeanFactory(ConfigurableListableBeanFactory beanFactory) {
        SkillImplementations implementations = SkillImplementations.from(environment);
        if (implementations.includesMock()) {
            throw new IllegalStateException("production プロファイルでは、モックを含む構成で起動できません（モックで動いているもの: "
                    + String.join("、", implementations.mockNames()) + "）");
        }
    }
}
