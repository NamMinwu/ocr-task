package com.dreamtrue.ocr.claude;

import com.anthropic.models.messages.ContentBlockParam;
import com.dreamtrue.ocr.domain.SourceImage;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ClaudeOcrClientTest {

    private SourceImage image(Path p, int w, int h) {
        return new SourceImage(p, "image/jpeg", w, h, 1);
    }

    @Test
    void 입력_규격의_이미지는_검증을_통과한다(@TempDir Path dir) throws IOException {
        Path p = dir.resolve("img_08.jpg");
        Files.write(p, new byte[89_000]);

        assertThatCode(() -> ClaudeOcrClient.validateSize(image(p, 1150, 1350)))
                .doesNotThrowAnyException();
    }

    @Test
    void 장변이_2576px를_넘으면_축소하지_않고_실패한다(@TempDir Path dir) throws IOException {
        Path p = dir.resolve("big.jpg");
        Files.write(p, new byte[1000]);

        assertThatThrownBy(() -> ClaudeOcrClient.validateSize(image(p, 3000, 4000)))
                .isInstanceOf(ImageTooLargeException.class)
                .hasMessageContaining("2576")
                .hasMessageContaining("4000");
    }

    @Test
    void base64가_10MB를_넘으면_실패한다(@TempDir Path dir) throws IOException {
        Path p = dir.resolve("heavy.jpg");
        // base64는 원본의 약 4/3배. 8MB 원본 -> 약 10.7MB
        Files.write(p, new byte[8 * 1024 * 1024]);

        assertThatThrownBy(() -> ClaudeOcrClient.validateSize(image(p, 100, 100)))
                .isInstanceOf(ImageTooLargeException.class)
                .hasMessageContaining("10MB");
    }

    @Test
    void 파일을_base64로_인코딩한다(@TempDir Path dir) throws IOException {
        Path p = dir.resolve("a.jpg");
        Files.write(p, "ABC".getBytes());

        assertThat(ClaudeOcrClient.encodeBase64(p)).isEqualTo("QUJD");
    }

    @Test
    void 이미지_블록이_텍스트보다_먼저_들어간다(@TempDir Path dir) throws IOException {
        Path p = dir.resolve("a.jpg");
        Files.write(p, new byte[10]);

        // image-then-text 순서는 공식 권장이며 판독 품질에 영향을 준다.
        List<ContentBlockParam> blocks = ClaudeOcrClient.buildContent(image(p, 1000, 1400), "QUJD");

        assertThat(blocks).hasSize(2);
        assertThat(blocks.getFirst().isImage()).isTrue();
        assertThat(blocks.get(1).isText()).isTrue();
    }

    @Test
    void 요청_파라미터가_예외없이_빌드된다(@TempDir Path dir) throws IOException {
        Path p = dir.resolve("a.jpg");
        Files.write(p, new byte[10]);

        assertThatCode(() -> ClaudeOcrClient.buildParams(image(p, 1000, 1400), "QUJD"))
                .doesNotThrowAnyException();
    }

    /** 단일 호출만 대체해 재시도 정책을 검증한다. */
    private static class StubClient extends ClaudeOcrClient {
        int calls = 0;
        private final RuntimeException failure;

        StubClient(RuntimeException failure) {
            super(null, new com.dreamtrue.ocr.config.OcrProperties(
                    "./input", "./output", 1, 1,
                    new com.dreamtrue.ocr.config.OcrProperties.Claude("claude-opus-5", 16000L, "k"),
                    new com.dreamtrue.ocr.config.OcrProperties.Google(
                            "./credentials/oauth-client.json", "./credentials/tokens", "", "")));
            this.failure = failure;
        }

        @Override
        com.dreamtrue.ocr.domain.OcrResult callOnce(
                com.anthropic.models.messages.StructuredMessageCreateParams<
                        com.dreamtrue.ocr.domain.OcrResult> p) {
            calls++;
            throw failure;
        }
    }

    @Test
    void 실패해도_애플리케이션이_다시_호출하지_않는다(@TempDir Path dir) throws IOException {
        // 429·5xx 의 재시도와 Retry-After 준수는 SDK 가 수행한다.
        // 그 위에 루프를 하나 더 두면 호출 횟수가 곱해져 레이트리밋을 악화시킨다.
        Path p = dir.resolve("a.jpg");
        Files.write(p, new byte[10]);
        StubClient stub = new StubClient(new RuntimeException("429 rate limit"));

        var outcome = stub.ocr(image(p, 1000, 1400));

        assertThat(outcome.isOk()).isFalse();
        assertThat(stub.calls).isEqualTo(1);
    }
}
