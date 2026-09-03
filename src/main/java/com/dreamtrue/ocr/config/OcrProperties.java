package com.dreamtrue.ocr.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

@ConfigurationProperties(prefix = "ocr")
public record OcrProperties(
        @DefaultValue("./input") String inputDir,
        @DefaultValue("./output") String outputDir,
        @DefaultValue("1") int folderNumber,
        @DefaultValue("1") int fileNumber,
        @DefaultValue Claude claude,
        @DefaultValue Google google
) {
    public record Claude(
            @DefaultValue("claude-opus-5") String model,
            @DefaultValue("16000") long maxTokens
    ) {}

    public record Google(
            @DefaultValue("./credentials/service-account.json") String credentialsPath,
            @DefaultValue("") String spreadsheetId,
            @DefaultValue("") String driveFolderId
    ) {}
}
