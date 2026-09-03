package com.dreamtrue.ocr.config;

import com.anthropic.client.AnthropicClient;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.NullSource;

import static org.assertj.core.api.Assertions.assertThat;

class AnthropicConfigTest {

    private final AnthropicConfig config = new AnthropicConfig();

    @ParameterizedTest
    @CsvSource({
            "sk-ant-test123, true",
            "'',             false",
            "'   ',          false"
    })
    void 설정에_키가_있을_때만_설정값을_쓴다(String apiKey, boolean expected) {
        assertThat(AnthropicConfig.useConfiguredKey(apiKey)).isEqualTo(expected);
    }

    @ParameterizedTest
    @NullSource
    void 키가_null이면_환경변수로_넘어간다(String apiKey) {
        assertThat(AnthropicConfig.useConfiguredKey(apiKey)).isFalse();
    }

    @Test
    void 설정에_키가_있으면_환경변수_없이도_클라이언트가_만들어진다() {
        OcrProperties properties = propertiesWithKey("sk-ant-test123");

        AnthropicClient client = config.anthropicClient(properties);

        assertThat(client).isNotNull();
    }

    private OcrProperties propertiesWithKey(String apiKey) {
        return new OcrProperties(
                "./input", "./output", 1, 1,
                new OcrProperties.Claude("claude-opus-5", 16000L, apiKey),
                new OcrProperties.Google("./credentials/oauth-client.json", "./credentials/tokens", "", ""));
    }
}
