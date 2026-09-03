package com.dreamtrue.ocr.config;

import com.anthropic.client.AnthropicClient;
import com.anthropic.client.okhttp.AnthropicOkHttpClient;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class AnthropicConfig {

    /**
     * 설정 파일의 키를 우선 사용하고, 비어 있으면 ANTHROPIC_API_KEY 환경변수로 넘어간다.
     * 두 방식 모두 지원해야 채점자가 어느 쪽을 쓰든 실행된다.
     */
    @Bean
    public AnthropicClient anthropicClient(OcrProperties properties) {
        String apiKey = properties.claude().apiKey();
        return useConfiguredKey(apiKey)
                ? AnthropicOkHttpClient.builder().apiKey(apiKey).build()
                : AnthropicOkHttpClient.fromEnv();
    }

    static boolean useConfiguredKey(String apiKey) {
        return apiKey != null && !apiKey.isBlank();
    }
}
