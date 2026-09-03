package com.dreamtrue.ocr.claude;

import com.anthropic.client.AnthropicClient;
import com.anthropic.models.messages.Base64ImageSource;
import com.anthropic.models.messages.ContentBlockParam;
import com.anthropic.models.messages.ImageBlockParam;
import com.anthropic.models.messages.MessageCreateParams;
import com.anthropic.models.messages.StructuredMessageCreateParams;
import com.anthropic.models.messages.TextBlockParam;
import com.anthropic.models.messages.ThinkingConfigAdaptive;
import com.dreamtrue.ocr.config.OcrProperties;
import com.dreamtrue.ocr.domain.OcrResult;
import com.dreamtrue.ocr.domain.Outcome;
import com.dreamtrue.ocr.domain.SourceImage;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Base64;
import java.util.List;

@Slf4j
@Component
@RequiredArgsConstructor
public class ClaudeOcrClient {

    /** 고해상도 티어(Claude 4.7 이후) 장변 상한. */
    private static final int MAX_LONG_EDGE_PX = 2576;
    /** Claude API 직접 호출 시 이미지당 base64 상한. */
    private static final long MAX_BASE64_BYTES = 10L * 1024 * 1024;

    private static final int MAX_ATTEMPTS = 3;
    private static final long BASE_BACKOFF_MS = 1000L;

    private final AnthropicClient client;
    private final OcrProperties properties;

    public Outcome<OcrResult> ocr(SourceImage image) {
        try {
            validateSize(image);
        } catch (ImageTooLargeException e) {
            return Outcome.failed(e.getMessage());
        }

        String base64;
        try {
            base64 = encodeBase64(image.path());
        } catch (IOException e) {
            return Outcome.failed("파일 읽기 실패: " + e.getMessage());
        }

        StructuredMessageCreateParams<OcrResult> params =
                buildParams(image, base64, properties.claude().model(), properties.claude().maxTokens());

        RuntimeException last = null;
        for (int attempt = 1; attempt <= MAX_ATTEMPTS; attempt++) {
            try {
                OcrResult result = client.messages().create(params).content().stream()
                        .flatMap(cb -> cb.text().stream())
                        .map(t -> t.text())
                        .findFirst()
                        .orElseThrow(() -> new IllegalStateException("구조화 출력이 비어 있습니다"));
                return Outcome.ok(result);
            } catch (RuntimeException e) {
                last = e;
                log.warn("OCR 실패 ({}/{}) {}: {}", attempt, MAX_ATTEMPTS,
                        image.fileName(), e.getMessage());
                if (attempt < MAX_ATTEMPTS) {
                    sleepBackoff(attempt);
                }
            }
        }
        return Outcome.failed(last == null ? "알 수 없는 오류" : last.getMessage());
    }

    static void validateSize(SourceImage image) {
        if (image.longEdge() > MAX_LONG_EDGE_PX) {
            throw new ImageTooLargeException(
                    "이미지 장변이 상한을 초과했습니다 (%dpx, 상한 %dpx). 축소 후 다시 시도하세요: %s"
                            .formatted(image.longEdge(), MAX_LONG_EDGE_PX, image.fileName()));
        }
        long fileBytes;
        try {
            fileBytes = Files.size(image.path());
        } catch (IOException e) {
            throw new ImageTooLargeException("파일 크기를 확인할 수 없습니다: " + e.getMessage());
        }
        long base64Bytes = (fileBytes + 2) / 3 * 4;
        if (base64Bytes > MAX_BASE64_BYTES) {
            throw new ImageTooLargeException(
                    "base64 크기가 상한 10MB를 초과했습니다 (%.1fMB): %s"
                            .formatted(base64Bytes / 1024.0 / 1024.0, image.fileName()));
        }
    }

    static String encodeBase64(Path path) throws IOException {
        return Base64.getEncoder().encodeToString(Files.readAllBytes(path));
    }

    static StructuredMessageCreateParams<OcrResult> buildParams(SourceImage image, String base64) {
        return buildParams(image, base64, "claude-opus-5", 16000L);
    }

    /** 이미지를 텍스트보다 먼저 놓는다 (공식 권장 image-then-text). */
    static List<ContentBlockParam> buildContent(SourceImage image, String base64) {
        return List.of(
                ContentBlockParam.ofImage(ImageBlockParam.builder()
                        .source(Base64ImageSource.builder()
                                .mediaType(mediaType(image.mediaType()))
                                .data(base64)
                                .build())
                        .build()),
                ContentBlockParam.ofText(TextBlockParam.builder()
                        .text(OcrPrompt.USER)
                        .build()));
    }

    static StructuredMessageCreateParams<OcrResult> buildParams(
            SourceImage image, String base64, String model, long maxTokens) {
        List<ContentBlockParam> content = buildContent(image, base64);

        // effort는 지정하지 않는다. .outputConfig()는 Class 또는 OutputConfig 중
        // 하나만 받으며, effort 기본값이 high 라 생략해도 동일하다.
        // temperature/top_p/top_k 는 Opus 5 에서 제거되어 사용하지 않는다.
        return MessageCreateParams.builder()
                .model(model)
                .maxTokens(maxTokens)
                .system(OcrPrompt.SYSTEM)
                .thinking(ThinkingConfigAdaptive.builder().build())
                .outputConfig(OcrResult.class)
                .addUserMessageOfBlockParams(content)
                .build();
    }

    private static Base64ImageSource.MediaType mediaType(String mime) {
        return switch (mime) {
            case "image/jpeg" -> Base64ImageSource.MediaType.IMAGE_JPEG;
            case "image/png" -> Base64ImageSource.MediaType.IMAGE_PNG;
            case "image/gif" -> Base64ImageSource.MediaType.IMAGE_GIF;
            case "image/webp" -> Base64ImageSource.MediaType.IMAGE_WEBP;
            default -> throw new IllegalArgumentException("지원하지 않는 이미지 형식: " + mime);
        };
    }

    private void sleepBackoff(int attempt) {
        long delay = BASE_BACKOFF_MS * (1L << (attempt - 1))
                + (long) (Math.random() * 250);
        try {
            Thread.sleep(delay);
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
        }
    }
}
