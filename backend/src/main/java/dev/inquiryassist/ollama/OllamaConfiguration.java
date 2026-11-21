package dev.inquiryassist.ollama;

import dev.inquiryassist.inquiry.InquiryProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import tools.jackson.databind.json.JsonMapper;

/** Ollama のクライアントを、共通の設定（{@code inquiry.ollama.*}）から組み立てる。接続は呼び出すときに行う。 */
@Configuration(proxyBeanMethods = false)
class OllamaConfiguration {

    /**
     * @param properties 共通の設定
     * @param json JSON の読み書き
     * @return Ollama のクライアント
     */
    @Bean
    OllamaClient ollamaClient(InquiryProperties properties, JsonMapper json) {
        return new OllamaClient(properties.ollama().baseUrl(), properties.ollama().keepAlive(), json);
    }
}
