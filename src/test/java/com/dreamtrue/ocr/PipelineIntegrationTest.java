package com.dreamtrue.ocr;

import com.dreamtrue.ocr.domain.ArchiveRecord;
import com.dreamtrue.ocr.domain.OcrResult;
import com.dreamtrue.ocr.domain.Outcome;
import com.dreamtrue.ocr.domain.SourceImage;
import com.dreamtrue.ocr.image.ImageScanner;
import com.dreamtrue.ocr.report.BatchReport;
import com.dreamtrue.ocr.runner.OcrBatchRunner;
import com.dreamtrue.ocr.sheets.SheetsGateway;
import com.dreamtrue.ocr.sheets.SheetsWriter;
import com.dreamtrue.ocr.store.OcrResultStore;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Path;
import java.util.*;

import static org.assertj.core.api.Assertions.assertThat;

class PipelineIntegrationTest {

    static class FakeSheets implements SheetsGateway {
        final Map<String, List<List<Object>>> written = new LinkedHashMap<>();

        @Override public void deleteAllSheetsExceptOne() {}

        @Override public Map<String, Integer> createSheets(List<String> titles) {
            Map<String, Integer> gids = new LinkedHashMap<>();
            int gid = 100;
            for (String t : titles) gids.put(t, gid++);
            return gids;
        }

        @Override public void writeValues(String title, List<List<Object>> rows) {
            written.put(title, rows);
        }

        @Override public void applyFormatting(Map<String, Integer> gids, List<Integer> failedIndexRows) {}

        @Override public String spreadsheetUrl() {
            return "https://docs.google.com/spreadsheets/d/TEST";
        }
    }

    private static final OcrResult OCR = new OcrResult(
            "보관증 — 김경석 수집 기록물 (1968.8.1.)",
            "보 관 증\n\n一. 명    칭 : 김경석 수집 기록물",
            "인쇄체 국한문 혼용 단면 문서(보관증)",
            "훼손·오염 없이 전체 판독 가능",
            "없음",
            "붉은색 원형 관인 날인",
            null);

    private void writeJpeg(Path p, int w, int h) throws IOException {
        ImageIO.write(new BufferedImage(w, h, BufferedImage.TYPE_INT_RGB), "jpg", p.toFile());
    }

    @Test
    void 세로_가로_문서가_섞여도_전체_파이프라인이_동작한다(@TempDir Path dir) throws IOException {
        Path input = dir.resolve("input");
        java.nio.file.Files.createDirectories(input);
        writeJpeg(input.resolve("img_01.jpg"), 1000, 1400);   // 세로
        writeJpeg(input.resolve("img_05.jpg"), 1200, 850);    // 가로

        List<SourceImage> images = new ImageScanner().scan(input);
        OcrResultStore store = new OcrResultStore(dir.resolve("output"));

        List<ArchiveRecord> records = OcrBatchRunner.buildRecords(
                images,
                img -> {
                    try { store.write(img.fileName(), "claude-opus-5", OCR); }
                    catch (IOException e) { throw new UncheckedIOException(e); }
                    return Outcome.ok(OCR);
                },
                img -> Outcome.ok("https://drive.google.com/thumbnail?id=" + img.detailNumber() + "&sz=w1000"),
                1, 1);

        Map<String, Boolean> landscape = new HashMap<>();
        images.forEach(i -> landscape.put(i.fileName(), i.isLandscape()));

        FakeSheets sheets = new FakeSheets();
        String url = new SheetsWriter(sheets).write(records, landscape);
        BatchReport report = BatchReport.of(records, url);

        // 목록 1 + 상세 2
        assertThat(sheets.written.keySet()).containsExactly("목록", "1-1", "1-2");

        // 목록 행 구조
        List<List<Object>> index = sheets.written.get("목록");
        assertThat(index).hasSize(3);
        assertThat(index.get(1).get(0)).isEqualTo(1);   // 폴더번호
        assertThat(index.get(1).get(1)).isEqualTo(1);   // 파일번호
        assertThat(index.get(1).get(2)).isEqualTo(1);   // 세부번호

        // 세로 문서와 가로 문서의 이미지 크기 인자가 다르다
        assertThat((String) sheets.written.get("1-1").get(6).get(1)).contains(",4,600,430)");
        assertThat((String) sheets.written.get("1-2").get(6).get(1)).contains(",4,430,600)");

        // 분석 셀에 특이사항이 조립되어 있다
        assertThat((String) sheets.written.get("1-1").get(5).get(1))
                .contains("보 관 증")
                .contains("※ 특이사항:")
                .contains("붉은색 원형 관인 날인");

        // 결과가 디스크에 남아 --retry-failed 로 재사용 가능하다
        assertThat(store.read("img_01.jpg")).contains(OCR);

        assertThat(report.exitCode()).isZero();
        assertThat(report.render()).contains("2건 중 2건 성공");
    }

    @Test
    void 업로드가_실패해도_분석_내용은_기록된다(@TempDir Path dir) throws IOException {
        Path input = dir.resolve("input");
        java.nio.file.Files.createDirectories(input);
        writeJpeg(input.resolve("img_01.jpg"), 1000, 1400);

        List<SourceImage> images = new ImageScanner().scan(input);
        List<ArchiveRecord> records = OcrBatchRunner.buildRecords(
                images,
                img -> Outcome.ok(OCR),
                img -> Outcome.failed("503 backendError, 3회 재시도 후 포기"),
                1, 1);

        FakeSheets sheets = new FakeSheets();
        String url = new SheetsWriter(sheets).write(records, Map.of());
        BatchReport report = BatchReport.of(records, url);

        List<List<Object>> detail = sheets.written.get("1-1");
        assertThat((String) detail.get(5).get(1)).contains("보 관 증");   // 분석은 정상
        assertThat((String) detail.get(6).get(1))                        // 사진만 실패
                .startsWith("[사진 업로드 실패] 503 backendError")
                .contains("원본:");

        assertThat(report.exitCode()).isEqualTo(1);
        assertThat(report.render()).contains("실패 1건");
    }
}
