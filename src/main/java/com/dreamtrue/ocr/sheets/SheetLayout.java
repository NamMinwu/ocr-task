package com.dreamtrue.ocr.sheets;

import com.dreamtrue.ocr.domain.ArchiveRecord;
import com.dreamtrue.ocr.domain.RecordAssembler;

import java.util.List;

public final class SheetLayout {

    public static final String INDEX_SHEET = "목록";

    /** 세로 문서 기준 셀 안 이미지 크기(px). 가로 문서는 뒤집어 쓴다. */
    private static final int PORTRAIT_HEIGHT = 600;
    private static final int PORTRAIT_WIDTH = 430;

    private SheetLayout() {}

    public static List<Object> indexHeader() {
        return List.of("폴더번호", "파일번호", "세부번호", "제목");
    }

    public static List<Object> indexRow(ArchiveRecord r, int detailGid, String title) {
        return List.of(
                r.folderNumber(),
                r.fileNumber(),
                r.detailNumber(),
                "=HYPERLINK(\"#gid=" + detailGid + "\"," + quote(title) + ")");
    }

    public static String backLink(int indexGid) {
        return "=HYPERLINK(\"#gid=" + indexGid + "\",\"◀ 목록으로\")";
    }

    public static String imageFormula(String url, boolean landscape) {
        int height = landscape ? PORTRAIT_WIDTH : PORTRAIT_HEIGHT;
        int width = landscape ? PORTRAIT_HEIGHT : PORTRAIT_WIDTH;
        return "=IMAGE(" + quote(url) + ",4," + height + "," + width + ")";
    }

    public static List<List<Object>> detailRows(
            ArchiveRecord r, RecordAssembler.Cells cells, int indexGid, boolean landscape) {

        Object photo = r.photoUrl().isOk()
                ? imageFormula(cells.photo(), landscape)
                : cells.photo();

        return List.of(
                List.of(backLink(indexGid), ""),
                List.of("", ""),
                List.of("파일번호", r.fileNumber()),
                List.of("세부번호", r.detailNumber()),
                List.of("제목", cells.title()),
                List.of("분석(내용)", cells.analysis()),
                List.of("사진", photo));
    }

    /** 시트 수식 안의 문자열 리터럴. 큰따옴표는 두 번 써서 이스케이프한다. */
    private static String quote(String value) {
        return "\"" + value.replace("\"", "\"\"") + "\"";
    }
}
