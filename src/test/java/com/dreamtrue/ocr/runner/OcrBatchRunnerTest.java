package com.dreamtrue.ocr.runner;

import com.dreamtrue.ocr.domain.ArchiveRecord;
import com.dreamtrue.ocr.domain.OcrResult;
import com.dreamtrue.ocr.domain.Outcome;
import com.dreamtrue.ocr.domain.SourceImage;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class OcrBatchRunnerTest {

    private static final OcrResult OCR =
            new OcrResult("제목", "본문", "종류", "상태", "없음", "확인되지 않음", null);

    private SourceImage image(int n) {
        return new SourceImage(Path.of("input/img_0" + n + ".jpg"), "image/jpeg", 1000, 1400, n);
    }

    @Test
    void 이미지_순서대로_레코드가_만들어진다() {
        List<ArchiveRecord> records = OcrBatchRunner.buildRecords(
                List.of(image(1), image(2), image(3)),
                img -> Outcome.ok(OCR),
                img -> Outcome.ok("https://x/" + img.detailNumber()),
                1, 1);

        assertThat(records).extracting(ArchiveRecord::detailNumber).containsExactly(1, 2, 3);
        assertThat(records).extracting(ArchiveRecord::sheetName)
                .containsExactly("1-1", "1-2", "1-3");
    }

    @Test
    void 한_장이_실패해도_나머지는_계속_처리된다() {
        List<ArchiveRecord> records = OcrBatchRunner.buildRecords(
                List.of(image(1), image(2), image(3)),
                img -> img.detailNumber() == 2
                        ? Outcome.failed("429 rate limit")
                        : Outcome.ok(OCR),
                img -> Outcome.ok("https://x/" + img.detailNumber()),
                1, 1);

        assertThat(records).hasSize(3);
        assertThat(records.get(0).ocr().isOk()).isTrue();
        assertThat(records.get(1).ocr().isOk()).isFalse();
        assertThat(records.get(2).ocr().isOk()).isTrue();
    }

    @Test
    void 실패해도_세부번호가_밀리지_않는다() {
        List<ArchiveRecord> records = OcrBatchRunner.buildRecords(
                List.of(image(1), image(2), image(3)),
                img -> img.detailNumber() == 1 ? Outcome.failed("실패") : Outcome.ok(OCR),
                img -> Outcome.ok("https://x"),
                1, 1);

        assertThat(records).extracting(ArchiveRecord::detailNumber).containsExactly(1, 2, 3);
    }

    @ParameterizedTest
    @CsvSource({
            "false,false,true,false,true",   // 기본: 저장된 결과를 무시하고 항상 API 호출
            "false,false,false,false,true",  // 저장된 결과가 없으면 API 호출
            "false,true,true,true,true",     // --retry-failed: 저장된 결과는 재사용, 없으면 API 호출
            "false,true,false,false,true",   // --retry-failed + 저장된 결과 없음: API 호출
            "true,false,true,true,false",    // --skip-ocr: 저장된 결과는 재사용, API 호출 안 함
            "true,false,false,false,false",  // --skip-ocr: 저장된 결과 없으면 OCR 실패로 처리
    })
    void useStored_진리표(boolean skipOcr, boolean retryFailed, boolean storedPresent,
                        boolean expectedUseStored, boolean expectedMayCallApi) {
        assertThat(OcrBatchRunner.useStored(skipOcr, retryFailed, storedPresent))
                .isEqualTo(expectedUseStored);
        assertThat(OcrBatchRunner.mayCallApi(skipOcr))
                .isEqualTo(expectedMayCallApi);
    }

    @Test
    void 갈래별_호출_순서는_OCR_다음_업로드다() {
        List<String> calls = new ArrayList<>();

        OcrBatchRunner.buildRecords(
                List.of(image(1), image(2)),
                img -> { calls.add("ocr:" + img.detailNumber()); return Outcome.ok(OCR); },
                img -> { calls.add("upload:" + img.detailNumber()); return Outcome.ok("u"); },
                1, 1);

        assertThat(calls).containsExactly("ocr:1", "upload:1", "ocr:2", "upload:2");
    }
}
