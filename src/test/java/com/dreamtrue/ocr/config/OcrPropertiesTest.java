package com.dreamtrue.ocr.config;

import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import static org.assertj.core.api.Assertions.assertThat;

class OcrPropertiesTest {

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withUserConfiguration(Config.class);

    @EnableConfigurationProperties(OcrProperties.class)
    static class Config {}

    @Test
    void 기본값이_적용된다() {
        runner.run(ctx -> {
            OcrProperties p = ctx.getBean(OcrProperties.class);
            assertThat(p.inputDir()).isEqualTo("./input");
            assertThat(p.outputDir()).isEqualTo("./output");
            assertThat(p.folderNumber()).isEqualTo(1);
            assertThat(p.fileNumber()).isEqualTo(1);
            assertThat(p.claude().model()).isEqualTo("claude-opus-5");
            assertThat(p.claude().maxTokens()).isEqualTo(16000L);
        });
    }

    @Test
    void 설정값이_바인딩된다() {
        runner.withPropertyValues(
                "ocr.input-dir=/tmp/in",
                "ocr.claude.model=claude-sonnet-5",
                "ocr.google.spreadsheet-id=SHEET123",
                "ocr.google.drive-folder-id=FOLDER456"
        ).run(ctx -> {
            OcrProperties p = ctx.getBean(OcrProperties.class);
            assertThat(p.inputDir()).isEqualTo("/tmp/in");
            assertThat(p.claude().model()).isEqualTo("claude-sonnet-5");
            assertThat(p.google().spreadsheetId()).isEqualTo("SHEET123");
            assertThat(p.google().driveFolderId()).isEqualTo("FOLDER456");
        });
    }
}
