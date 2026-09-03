package com.dreamtrue.ocr.sheets;

import com.dreamtrue.ocr.domain.ArchiveRecord;
import com.dreamtrue.ocr.domain.OcrResult;
import com.dreamtrue.ocr.domain.Outcome;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.entry;

class SheetsWriterTest {

    static class FakeSheets implements SheetsGateway {
        final List<String> calls = new ArrayList<>();
        final Map<String, List<List<Object>>> written = new LinkedHashMap<>();

        @Override
        public void deleteAllSheetsExceptOne() {
            calls.add("clear");
        }

        @Override
        public Map<String, Integer> createSheets(List<String> titles) {
            calls.add("create:" + titles);
            Map<String, Integer> gids = new LinkedHashMap<>();
            int gid = 100;
            for (String t : titles) {
                gids.put(t, gid++);
            }
            return gids;
        }

        @Override
        public void writeValues(String sheetTitle, List<List<Object>> rows) {
            calls.add("write:" + sheetTitle);
            written.put(sheetTitle, rows);
        }

        Map<Integer, SheetsGateway.RowMark> marks = Map.of();

        @Override
        public void applyFormatting(Map<String, Integer> gids,
                                   Map<Integer, SheetsGateway.RowMark> markedRows) {
            this.marks = markedRows;
            calls.add("format");
        }

        @Override
        public String spreadsheetUrl() {
            return "https://docs.google.com/spreadsheets/d/TEST";
        }
    }

    private ArchiveRecord record(int detail) {
        return new ArchiveRecord(1, 1, detail, Path.of("input/img_0" + detail + ".jpg"),
                Outcome.ok(new OcrResult("제목" + detail, "본문", "종류", "상태",
                        "없음", "확인되지 않음", null)),
                Outcome.ok("https://drive.google.com/thumbnail?id=X" + detail + "&sz=w1000"));
    }

    @Test
    void 시트를_먼저_만들고_그다음에_값을_쓴다() {
        FakeSheets fake = new FakeSheets();
        new SheetsWriter(fake).write(List.of(record(1), record(2)), Map.of());

        int createIdx = indexOfPrefix(fake.calls, "create:");
        int firstWriteIdx = indexOfPrefix(fake.calls, "write:");
        assertThat(createIdx).isLessThan(firstWriteIdx);
        assertThat(fake.calls.getFirst()).isEqualTo("clear");
    }

    @Test
    void 목록과_상세시트가_모두_생성된다() {
        FakeSheets fake = new FakeSheets();
        new SheetsWriter(fake).write(List.of(record(1), record(2)), Map.of());

        assertThat(fake.written.keySet()).containsExactly("목록", "1-1", "1-2");
    }

    @Test
    void 목록의_제목은_해당_상세시트_gid를_가리킨다() {
        FakeSheets fake = new FakeSheets();
        new SheetsWriter(fake).write(List.of(record(1), record(2)), Map.of());

        List<List<Object>> index = fake.written.get("목록");
        assertThat(index.get(0)).containsExactly("폴더번호", "파일번호", "세부번호", "제목");
        // 목록=100, 1-1=101, 1-2=102
        assertThat((String) index.get(1).get(3)).contains("#gid=101").contains("제목1");
        assertThat((String) index.get(2).get(3)).contains("#gid=102").contains("제목2");
    }

    @Test
    void 상세시트의_역링크는_목록_gid를_가리킨다() {
        FakeSheets fake = new FakeSheets();
        new SheetsWriter(fake).write(List.of(record(1)), Map.of());

        List<List<Object>> detail = fake.written.get("1-1");
        assertThat((String) detail.get(0).get(0)).contains("#gid=100").contains("◀ 목록으로");
    }

    @Test
    void 가로문서는_이미지_크기_인자가_뒤집힌다() {
        FakeSheets fake = new FakeSheets();
        new SheetsWriter(fake).write(List.of(record(5)), Map.of("img_05.jpg", true));

        String photo = (String) fake.written.get("1-5").get(6).get(1);
        assertThat(photo).isEqualTo("=IMAGE(\"https://drive.google.com/thumbnail?id=X5&sz=w1000\",4,430,600)");
    }

    @Test
    void 서식은_값을_쓴_뒤에_적용된다() {
        FakeSheets fake = new FakeSheets();
        new SheetsWriter(fake).write(List.of(record(1)), Map.of());

        assertThat(fake.calls.getLast()).isEqualTo("format");
    }

    private int indexOfPrefix(List<String> calls, String prefix) {
        for (int i = 0; i < calls.size(); i++) {
            if (calls.get(i).startsWith(prefix)) return i;
        }
        return -1;
    }

    private ArchiveRecord photoFailed(int detail) {
        return new ArchiveRecord(1, 1, detail, Path.of("input/img_0" + detail + ".jpg"),
                Outcome.ok(new OcrResult("제목" + detail, "본문", "종류", "상태",
                        "없음", "확인되지 않음", null)),
                Outcome.failed("400 Bad Request"));
    }

    private ArchiveRecord ocrFailed(int detail) {
        return new ArchiveRecord(1, 1, detail, Path.of("input/img_0" + detail + ".jpg"),
                Outcome.failed("401 authentication_error"),
                Outcome.ok("https://x/" + detail));
    }

    private ArchiveRecord bothFailed(int detail) {
        return new ArchiveRecord(1, 1, detail, Path.of("input/img_0" + detail + ".jpg"),
                Outcome.failed("401"), Outcome.failed("400"));
    }

    @Test
    void 업로드가_실패한_행을_표시한다() {
        FakeSheets fake = new FakeSheets();

        // 0행은 헤더. 1행=1번, 2행=2번(업로드 실패), 3행=3번
        new SheetsWriter(fake).write(
                List.of(record(1), photoFailed(2), record(3)), Map.of());

        assertThat(fake.marks).containsExactly(
                entry(2, SheetsGateway.RowMark.UPLOAD_FAILED));
    }

    @Test
    void OCR이_실패한_행은_다른_표시를_쓴다() {
        FakeSheets fake = new FakeSheets();

        new SheetsWriter(fake).write(List.of(record(1), ocrFailed(2)), Map.of());

        assertThat(fake.marks).containsExactly(
                entry(2, SheetsGateway.RowMark.OCR_FAILED));
    }

    @Test
    void 둘_다_실패하면_OCR_실패로_표시한다() {
        // 전사가 아예 없는 쪽이 더 근본적인 결손이다.
        FakeSheets fake = new FakeSheets();

        new SheetsWriter(fake).write(List.of(bothFailed(1)), Map.of());

        assertThat(fake.marks).containsExactly(
                entry(1, SheetsGateway.RowMark.OCR_FAILED));
    }

    @Test
    void 전부_성공하면_표시할_행이_없다() {
        FakeSheets fake = new FakeSheets();

        new SheetsWriter(fake).write(List.of(record(1), record(2)), Map.of());

        assertThat(fake.marks).isEmpty();
    }
}
