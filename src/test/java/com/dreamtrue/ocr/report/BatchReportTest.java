package com.dreamtrue.ocr.report;

import com.dreamtrue.ocr.domain.ArchiveRecord;
import com.dreamtrue.ocr.domain.OcrResult;
import com.dreamtrue.ocr.domain.Outcome;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class BatchReportTest {

    private static final OcrResult OCR =
            new OcrResult("t", "본문", "종류", "상태", "없음", "확인되지 않음", null);

    private ArchiveRecord ok(int n) {
        return new ArchiveRecord(1, 1, n, Path.of("input/img_0" + n + ".jpg"),
                Outcome.ok(OCR), Outcome.ok("https://x/" + n));
    }

    private ArchiveRecord photoFailed(int n, String reason) {
        return new ArchiveRecord(1, 1, n, Path.of("input/img_0" + n + ".jpg"),
                Outcome.ok(OCR), Outcome.failed(reason));
    }

    @Test
    void 전량_성공이면_종료코드는_0이다() {
        BatchReport r = BatchReport.of(List.of(ok(1), ok(2)), "https://sheet");

        assertThat(r.exitCode()).isZero();
        assertThat(r.render()).contains("2건 중 2건 성공");
    }

    @Test
    void 부분_실패면_종료코드는_1이고_실패건이_나열된다() {
        BatchReport r = BatchReport.of(
                List.of(ok(1), photoFailed(3, "503 backendError")), "https://sheet");

        assertThat(r.exitCode()).isEqualTo(1);
        assertThat(r.render())
                .contains("2건 중 1건 성공")
                .contains("실패 1건")
                .contains("img_03.jpg")
                .contains("세부번호 3")
                .contains("503 backendError");
    }

    @Test
    void 계통_실패면_종료코드는_2이다() {
        BatchReport r = BatchReport.systemicFailure("Drive 업로드 권한 오류 (403)");

        assertThat(r.exitCode()).isEqualTo(2);
        assertThat(r.render()).contains("[중단]").contains("403");
    }

    @Test
    void 실패가_있으면_재시도_명령을_안내한다() {
        BatchReport r = BatchReport.of(
                List.of(ok(1), photoFailed(3, "503 backendError")), "https://sheet");

        assertThat(r.render()).contains("--retry-failed");
    }

    @Test
    void 전량_성공이면_재시도_안내는_없다() {
        BatchReport r = BatchReport.of(List.of(ok(1)), "https://sheet");

        assertThat(r.render()).doesNotContain("--retry-failed");
    }

    @Test
    void 성공하면_시트_URL이_출력된다() {
        BatchReport r = BatchReport.of(List.of(ok(1)), "https://docs.google.com/spreadsheets/d/X");

        assertThat(r.render()).contains("https://docs.google.com/spreadsheets/d/X");
    }
}
