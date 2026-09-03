package com.dreamtrue.ocr.sheets;

import com.dreamtrue.ocr.domain.ArchiveRecord;
import com.dreamtrue.ocr.domain.OcrResult;
import com.dreamtrue.ocr.domain.Outcome;
import com.dreamtrue.ocr.domain.RecordAssembler;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class SheetLayoutTest {

    private ArchiveRecord record() {
        return new ArchiveRecord(1, 1, 3, Path.of("input/img_03.jpg"),
                Outcome.ok(new OcrResult("제목", "본문", "종류", "상태", "없음", "확인되지 않음", null)),
                Outcome.ok("https://drive.google.com/thumbnail?id=X&sz=w1000"));
    }

    @Test
    void 목록_헤더는_네_열이다() {
        assertThat(SheetLayout.indexHeader())
                .containsExactly("폴더번호", "파일번호", "세부번호", "제목");
    }

    @Test
    void 목록_행의_제목은_상세시트로_가는_하이퍼링크다() {
        List<Object> row = SheetLayout.indexRow(record(), 12345, "보관증");

        assertThat(row.get(0)).isEqualTo(1);
        assertThat(row.get(1)).isEqualTo(1);
        assertThat(row.get(2)).isEqualTo(3);
        assertThat(row.get(3)).isEqualTo("=HYPERLINK(\"#gid=12345\",\"보관증\")");
    }

    @Test
    void 제목에_큰따옴표가_있어도_수식이_깨지지_않는다() {
        List<Object> row = SheetLayout.indexRow(record(), 1, "제목 \"인용\" 포함");

        assertThat((String) row.get(3)).isEqualTo("=HYPERLINK(\"#gid=1\",\"제목 \"\"인용\"\" 포함\")");
    }

    @Test
    void 세로문서와_가로문서의_이미지_크기_인자가_다르다() {
        assertThat(SheetLayout.imageFormula("https://x/y", false))
                .isEqualTo("=IMAGE(\"https://x/y\",4,600,430)");
        assertThat(SheetLayout.imageFormula("https://x/y", true))
                .isEqualTo("=IMAGE(\"https://x/y\",4,430,600)");
    }

    @Test
    void 상세시트는_예시_양식과_같은_배치를_갖는다() {
        var cells = RecordAssembler.assemble(record());
        List<List<Object>> rows = SheetLayout.detailRows(record(), cells, 999, false);

        assertThat(rows.get(0)).containsExactly("=HYPERLINK(\"#gid=999\",\"◀ 목록으로\")", "");
        assertThat(rows.get(1)).containsExactly("", "");
        assertThat(rows.get(2)).containsExactly("파일번호", 1);
        assertThat(rows.get(3)).containsExactly("세부번호", 3);
        assertThat(rows.get(4).get(0)).isEqualTo("제목");
        assertThat(rows.get(5).get(0)).isEqualTo("분석(내용)");
        assertThat(rows.get(6).get(0)).isEqualTo("사진");
        assertThat((String) rows.get(6).get(1)).startsWith("=IMAGE(");
    }

    @Test
    void 사진_업로드가_실패하면_수식이_아니라_문자열이_들어간다() {
        ArchiveRecord failed = new ArchiveRecord(1, 1, 3, Path.of("input/img_03.jpg"),
                record().ocr(), Outcome.failed("503 backendError"));
        var cells = RecordAssembler.assemble(failed);

        List<List<Object>> rows = SheetLayout.detailRows(failed, cells, 999, false);

        assertThat((String) rows.get(6).get(1))
                .startsWith("[사진 업로드 실패]")
                .doesNotStartWith("=IMAGE(");
    }
}
