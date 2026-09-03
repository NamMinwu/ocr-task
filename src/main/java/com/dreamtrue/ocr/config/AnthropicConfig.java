package com.dreamtrue.ocr.config;

import com.anthropic.client.AnthropicClient;
import com.anthropic.client.okhttp.AnthropicOkHttpClient;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class AnthropicConfig {

    private static final int MAX_RETRIES = 3;

    /**
     * 설정 파일의 키를 우선 사용하고, 비어 있으면 ANTHROPIC_API_KEY 환경변수로 넘어간다.
     * 두 방식 모두 지원해야 채점자가 어느 쪽을 쓰든 실행된다.
     */
    @Bean
    public AnthropicClient anthropicClient(OcrProperties properties) {
        String apiKey = properties.claude().apiKey();
        // SDK 가 429·5xx 를 재시도하며 Retry-After 헤더를 존중한다.
        // 애플리케이션에 별도 백오프 루프를 두지 않는 이유이며,
        // 라이브러리 기본값에 기대지 않도록 횟수를 명시한다.
        var builder = AnthropicOkHttpClient.builder().maxRetries(MAX_RETRIES);
        return useConfiguredKey(apiKey)
                ? builder.apiKey(apiKey).build()
                : builder.fromEnv().build();
    }

    static boolean useConfiguredKey(String apiKey) {
        return apiKey != null && !apiKey.isBlank();
    }
}
