package dev.inquiryassist.web;

import dev.inquiryassist.inquiry.IntakeLocales;
import jakarta.servlet.http.HttpServletRequest;
import java.util.List;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.MethodParameter;
import org.springframework.web.bind.support.WebDataBinderFactory;
import org.springframework.web.context.request.NativeWebRequest;
import org.springframework.web.method.support.HandlerMethodArgumentResolver;
import org.springframework.web.method.support.ModelAndViewContainer;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/** {@link ViewerLang} の引数を、リクエストごとの見る人の言語に解決する。決め方は {@code LanguageResolver}。 */
@Configuration(proxyBeanMethods = false)
class ViewerLangConfiguration implements WebMvcConfigurer {

    private final IntakeLocales locales;

    /**
     * @param locales 使える言語と、言語を決める処理
     */
    ViewerLangConfiguration(IntakeLocales locales) {
        this.locales = locales;
    }

    @Override
    public void addArgumentResolvers(List<HandlerMethodArgumentResolver> resolvers) {
        resolvers.add(new HandlerMethodArgumentResolver() {
            @Override
            public boolean supportsParameter(MethodParameter parameter) {
                return parameter.hasParameterAnnotation(ViewerLang.class) && parameter.getParameterType() == String.class;
            }

            @Override
            public Object resolveArgument(
                    MethodParameter parameter,
                    ModelAndViewContainer mavContainer,
                    NativeWebRequest webRequest,
                    WebDataBinderFactory binderFactory) {
                HttpServletRequest request = webRequest.getNativeRequest(HttpServletRequest.class);
                return locales.resolver().resolve(request.getParameter("lang"), request.getHeader("Accept-Language"));
            }
        });
    }
}
