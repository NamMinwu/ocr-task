package com.dreamtrue.ocr.sheets;

import java.util.List;
import java.util.Map;

/** Sheets API 접근을 좁은 인터페이스로 가둔다. 테스트에서 대역으로 바꾼다. */
public interface SheetsGateway {

    /** 재실행 시 멱등하게 만들기 위해 기존 시트를 정리한다. */
    void deleteAllSheetsExceptOne();

    /** 시트를 만들고 제목→gid 매핑을 돌려준다. gid는 만들어야 알 수 있다. */
    Map<String, Integer> createSheets(List<String> titles);

    /** USER_ENTERED 로 기록한다. 수식으로 해석되어야 한다. */
    void writeValues(String sheetTitle, List<List<Object>> rows);

    /** 목록 시트에서 행을 어떻게 표시할지. 두 갈래 중 어느 쪽이 실패했는지 구분한다. */
    enum RowMark { OCR_FAILED, UPLOAD_FAILED }

    /**
     * @param markedRows 목록 시트의 행 번호(0-based, 0은 헤더) → 표시 종류.
     *                   목록만 보고도 어느 레코드의 어느 절반이 비었는지 알 수 있어야 한다.
     */
    void applyFormatting(Map<String, Integer> gids, Map<Integer, RowMark> markedRows);

    String spreadsheetUrl();
}
