package dev.inquiryassist.web;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * 見る人の言語を受け取る引数。リクエストの {@code Accept-Language} とクエリの {@code lang} から、使える言語のどれかに決める
 * （クエリの {@code lang} を優先。当てはまらなければ既定の言語）。
 */
@Documented
@Target(ElementType.PARAMETER)
@Retention(RetentionPolicy.RUNTIME)
public @interface ViewerLang {}
