package com.dreamtrue.ocr.config;

import com.anthropic.client.AnthropicClient;
import com.anthropic.client.okhttp.AnthropicOkHttpClient;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class AnthropicConfig {

    @Bean
    public AnthropicClient anthropicClient() {
        // ANTHROPIC_API_KEY 환경변수를 읽는다.
        return AnthropicOkHttpClient.fromEnv();
    }
}
