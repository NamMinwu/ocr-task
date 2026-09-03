package com.dreamtrue.ocr.store;

import com.dreamtrue.ocr.domain.OcrResult;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

class OcrResultStoreTest {

    private static final OcrResult RESULT = new OcrResult(
            "정리 작업 메모 (칠월 이십오일)",
            "정리 작업 메모\n\n칠월 이십오일 맑음",
            "손글씨 낱장 메모",
            "용지 변색, 판독 지장 없음",
            "없음",
            "확인되지 않음",
            null);

    @Test
    void 저장하고_다시_읽으면_같은_값이다(@TempDir Path dir) throws IOException {
        OcrResultStore store = new OcrResultStore(dir);

        store.write("img_03.jpg", "claude-opus-5", RESULT);
        Optional<OcrResult> read = store.read("img_03.jpg");

        assertThat(read).contains(RESULT);
    }

    @Test
    void output_raw_아래에_파일명_기반으로_저장된다(@TempDir Path dir) throws IOException {
        OcrResultStore store = new OcrResultStore(dir);

        store.write("img_03.jpg", "claude-opus-5", RESULT);

        Path expected = dir.resolve("raw").resolve("img_03.json");
        assertThat(expected).exists();
        assertThat(Files.readString(expected))
                .contains("claude-opus-5")
                .contains("정리 작업 메모");
    }

    @Test
    void 파일이_없으면_비어있음을_돌려준다(@TempDir Path dir) throws IOException {
        OcrResultStore store = new OcrResultStore(dir);

        assertThat(store.read("없는파일.jpg")).isEmpty();
    }

    @Test
    void 디렉토리가_없어도_저장하면_생성된다(@TempDir Path dir) throws IOException {
        Path nested = dir.resolve("깊은").resolve("경로");
        OcrResultStore store = new OcrResultStore(nested);

        store.write("a.jpg", "claude-opus-5", RESULT);

        assertThat(nested.resolve("raw").resolve("a.json")).exists();
    }
}
