package dev.inquiryassist;

import org.springframework.beans.factory.config.BeanFactoryPostProcessor;
import org.springframework.beans.factory.config.ConfigurableListableBeanFactory;
import org.springframework.context.EnvironmentAware;
import org.springframework.context.annotation.Profile;
import org.springframework.core.env.Environment;
import org.springframework.core.env.Profiles;
import org.springframework.stereotype.Component;

/**
 * 本番（{@code production} プロファイル）でモックを有効にできないようにする起動時検査。
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
     * @throws IllegalStateException production と mock が同時に有効な場合
     */
    @Override
    public void postProcessBeanFactory(ConfigurableListableBeanFactory beanFactory) {
        if (environment.acceptsProfiles(Profiles.of("mock"))) {
            throw new IllegalStateException("production プロファイルでは mock プロファイルを有効にできません");
        }
    }
}
