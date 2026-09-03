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
            @DefaultValue("16000") long maxTokens,
            /** 비워 두면 ANTHROPIC_API_KEY 환경변수를 사용한다. */
            @DefaultValue("") String apiKey
    ) {}

    public record Google(
            /** GCP 콘솔에서 받은 OAuth 2.0 데스크톱 앱 클라이언트 JSON. */
            @DefaultValue("./credentials/oauth-client.json") String oauthClientPath,
            /** 최초 동의 후 토큰이 캐시되는 디렉토리. 지우면 다시 동의해야 한다. */
            @DefaultValue("./credentials/tokens") String tokenStorePath,
            @DefaultValue("") String spreadsheetId,
            @DefaultValue("") String driveFolderId
    ) {}
}
