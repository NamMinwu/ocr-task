package com.dreamtrue.ocr.claude;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class OcrPromptTest {

    @Test
    void 시스템프롬프트에_핵심_금지규칙이_모두_들어있다() {
        assertThat(OcrPrompt.SYSTEM)
                .contains("추측")
                .contains("요약")
                .contains("번역");
    }

    @Test
    void 시스템프롬프트에_표_파이프_규칙이_있다() {
        assertThat(OcrPrompt.SYSTEM).contains("|");
    }

    @Test
    void 시스템프롬프트에_한자와_연호_처리_규칙이_있다() {
        assertThat(OcrPrompt.SYSTEM)
                .contains("한자")
                .contains("檀紀");
    }

    @Test
    void 사용자프롬프트는_비어있지_않다() {
        assertThat(OcrPrompt.USER).isNotBlank();
    }
}
