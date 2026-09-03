package com.dreamtrue.ocr.domain;

import org.junit.jupiter.api.Test;

import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

class RecordAssemblerTest {

    private static final OcrResult OCR = new OcrResult(
            "보관증 — 김경석 수집 기록물 (1968.8.1.)",
            "보 관 증\n아래 기록물을 정히 인수하여",
            "인쇄체 국한문 혼용 단면 문서",
            "훼손·오염 없음",
            "없음",
            "붉은색 원형 관인 날인",
            null);

    private ArchiveRecord record(Outcome<OcrResult> ocr, Outcome<String> photo) {
        return new ArchiveRecord(1, 1, 3, Path.of("input/img_03.jpg"), ocr, photo);
    }

    @Test
    void 둘_다_성공하면_정상_기록된다() {
        var cells = RecordAssembler.assemble(
                record(Outcome.ok(OCR), Outcome.ok("https://drive.google.com/thumbnail?id=X&sz=w1000")));

        assertThat(cells.title()).isEqualTo("보관증 — 김경석 수집 기록물 (1968.8.1.)");
        assertThat(cells.analysis()).contains("보 관 증").contains("※ 특이사항:");
        assertThat(cells.photo()).isEqualTo("https://drive.google.com/thumbnail?id=X&sz=w1000");
    }

    @Test
    void OCR성공_업로드실패면_사진칸에_사유와_원본경로가_남는다() {
        var cells = RecordAssembler.assemble(
                record(Outcome.ok(OCR), Outcome.failed("503 backendError")));

        assertThat(cells.title()).isEqualTo("보관증 — 김경석 수집 기록물 (1968.8.1.)");
        assertThat(cells.analysis()).contains("※ 특이사항:");
        assertThat(cells.photo())
                .startsWith("[사진 업로드 실패] 503 backendError")
                .contains("원본: input/img_03.jpg");
    }

    @Test
    void OCR실패_업로드성공이면_제목은_파일명으로_대체된다() {
        var cells = RecordAssembler.assemble(
                record(Outcome.failed("429 rate limit"), Outcome.ok("https://x/y")));

        assertThat(cells.title()).isEqualTo("img_03.jpg");
        assertThat(cells.analysis())
                .startsWith("[OCR 실패] 429 rate limit")
                .contains("원본: input/img_03.jpg");
        assertThat(cells.photo()).isEqualTo("https://x/y");
    }

    @Test
    void 둘_다_실패하면_두_칸_모두_실패표시된다() {
        var cells = RecordAssembler.assemble(
                record(Outcome.failed("OCR 사유"), Outcome.failed("업로드 사유")));

        assertThat(cells.title()).isEqualTo("img_03.jpg");
        assertThat(cells.analysis()).startsWith("[OCR 실패] OCR 사유");
        assertThat(cells.photo()).startsWith("[사진 업로드 실패] 업로드 사유");
    }
}
