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

    /**
     * @param failedIndexRows 목록 시트에서 강조할 행 번호(0-based, 0은 헤더).
     *                        목록만 보고도 어느 레코드가 불완전한지 알 수 있어야 한다.
     */
    void applyFormatting(Map<String, Integer> gids, List<Integer> failedIndexRows);

    String spreadsheetUrl();
}
