package com.dreamtrue.ocr.sheets;

import com.dreamtrue.ocr.config.GoogleConfig;
import com.dreamtrue.ocr.config.OcrProperties;
import com.google.api.services.sheets.v4.Sheets;
import com.google.api.services.sheets.v4.model.*;
import lombok.RequiredArgsConstructor;
import lombok.SneakyThrows;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Component
@RequiredArgsConstructor
public class GoogleSheetsGateway implements SheetsGateway {

    /** 재생성 도중 시트 개수가 0이 되지 않도록 남겨 두는 임시 시트 이름. */
    private static final String PLACEHOLDER_SHEET = "_재생성중";

    private static final int ANALYSIS_ROW = 5; // 0-based, "분석(내용)"
    private static final int PHOTO_ROW = 6;    // 0-based, "사진"

    private final Sheets sheets;
    private final OcrProperties properties;

    private String spreadsheetId() {
        return GoogleConfig.requireSpreadsheetId(properties.google().spreadsheetId());
    }
    @Override
    @SneakyThrows
    public void deleteAllSheetsExceptOne() {
        Spreadsheet ss = sheets.spreadsheets().get(spreadsheetId()).execute();
        List<Sheet> existing = ss.getSheets();

        List<Request> requests = new ArrayList<>();
        for (int i = 1; i < existing.size(); i++) {
            requests.add(new Request().setDeleteSheet(
                    new DeleteSheetRequest().setSheetId(existing.get(i).getProperties().getSheetId())));
        }

        // 스프레드시트는 시트가 최소 하나 있어야 하므로 첫 시트는 지울 수 없다.
        // 그 이름이 이번에 만들 이름(예: '목록')과 겹치면 addSheet 가 중복으로 거부되므로,
        // 겹치지 않는 임시 이름으로 바꿔 둔다. createSheets 가 마지막에 이 시트를 지운다.
        SheetProperties survivor = existing.getFirst().getProperties();
        requests.add(new Request().setUpdateSheetProperties(new UpdateSheetPropertiesRequest()
                .setProperties(new SheetProperties()
                        .setSheetId(survivor.getSheetId())
                        .setTitle(PLACEHOLDER_SHEET))
                .setFields("title")));

        batch(requests);
    }

    @Override
    @SneakyThrows
    public Map<String, Integer> createSheets(List<String> titles) {
        List<Request> requests = titles.stream()
                .map(t -> new Request().setAddSheet(new AddSheetRequest()
                        .setProperties(new SheetProperties().setTitle(t))))
                .toList();

        BatchUpdateSpreadsheetResponse response = batch(requests);

        Map<String, Integer> gids = new LinkedHashMap<>();
        List<Response> replies = response.getReplies();
        for (int i = 0; i < titles.size(); i++) {
            gids.put(titles.get(i), replies.get(i).getAddSheet().getProperties().getSheetId());
        }

        // 기본 시트(Sheet1 등)를 정리한다.
        Spreadsheet ss = sheets.spreadsheets().get(spreadsheetId()).execute();
        List<Request> cleanup = ss.getSheets().stream()
                .map(Sheet::getProperties)
                .filter(p -> !gids.containsKey(p.getTitle()))
                .map(p -> new Request().setDeleteSheet(
                        new DeleteSheetRequest().setSheetId(p.getSheetId())))
                .toList();
        if (!cleanup.isEmpty()) {
            batch(cleanup);
        }
        return gids;
    }

    @Override
    @SneakyThrows
    public void writeValues(String sheetTitle, List<List<Object>> rows) {
        sheets.spreadsheets().values()
                .update(spreadsheetId(), "'" + sheetTitle + "'!A1",
                        new ValueRange().setValues(rows))
                .setValueInputOption("USER_ENTERED")   // 수식으로 해석되어야 한다
                .execute();
    }

    @Override
    @SneakyThrows
    public void applyFormatting(Map<String, Integer> gids, Map<Integer, RowMark> markedRows) {
        List<Request> requests = new ArrayList<>();
        for (Map.Entry<String, Integer> e : gids.entrySet()) {
            int gid = e.getValue();
            boolean isIndex = SheetLayout.INDEX_SHEET.equals(e.getKey());

            requests.add(columnWidth(gid, 0, isIndex ? 90 : 110));
            requests.add(columnWidth(gid, 1, isIndex ? 90 : 620));
            if (isIndex) {
                requests.add(columnWidth(gid, 2, 90));
                requests.add(columnWidth(gid, 3, 700));
                continue;
            }
            requests.add(wrapText(gid, ANALYSIS_ROW));
            requests.add(rowHeight(gid, PHOTO_ROW, 620));
        }

        // 목록만 보고도 불완전한 레코드를 알 수 있게 한다.
        // 평가양식이 4열로 정해져 있어 열을 늘리지 않고 배경색으로만 표시한다.
        int indexGid = gids.get(SheetLayout.INDEX_SHEET);
        markedRows.forEach((row, mark) -> requests.add(highlightRow(indexGid, row, mark)));
        batch(requests);
    }

    @Override
    public String spreadsheetUrl() {
        return "https://docs.google.com/spreadsheets/d/" + spreadsheetId();
    }

    /**
     * 실패한 레코드의 목록 행에 배경색을 깐다.
     * 전사가 없는 쪽(OCR)은 붉게, 사진이 없는 쪽(업로드)은 호박색으로 구분한다.
     */
    private Request highlightRow(int gid, int rowIndex, RowMark mark) {
        Color color = switch (mark) {
            case OCR_FAILED    -> new Color().setRed(0.97f).setGreen(0.83f).setBlue(0.82f);
            case UPLOAD_FAILED -> new Color().setRed(1.00f).setGreen(0.93f).setBlue(0.76f);
        };
        return new Request().setRepeatCell(new RepeatCellRequest()
                .setRange(new GridRange().setSheetId(gid)
                        .setStartRowIndex(rowIndex).setEndRowIndex(rowIndex + 1)
                        .setStartColumnIndex(0).setEndColumnIndex(4))
                .setCell(new CellData().setUserEnteredFormat(new CellFormat()
                        .setBackgroundColor(color)))
                .setFields("userEnteredFormat.backgroundColor"));
    }

    private Request columnWidth(int gid, int columnIndex, int pixels) {
        return new Request().setUpdateDimensionProperties(new UpdateDimensionPropertiesRequest()
                .setRange(new DimensionRange().setSheetId(gid).setDimension("COLUMNS")
                        .setStartIndex(columnIndex).setEndIndex(columnIndex + 1))
                .setProperties(new DimensionProperties().setPixelSize(pixels))
                .setFields("pixelSize"));
    }

    private Request rowHeight(int gid, int rowIndex, int pixels) {
        return new Request().setUpdateDimensionProperties(new UpdateDimensionPropertiesRequest()
                .setRange(new DimensionRange().setSheetId(gid).setDimension("ROWS")
                        .setStartIndex(rowIndex).setEndIndex(rowIndex + 1))
                .setProperties(new DimensionProperties().setPixelSize(pixels))
                .setFields("pixelSize"));
    }

    private Request wrapText(int gid, int rowIndex) {
        return new Request().setRepeatCell(new RepeatCellRequest()
                .setRange(new GridRange().setSheetId(gid)
                        .setStartRowIndex(rowIndex).setEndRowIndex(rowIndex + 1)
                        .setStartColumnIndex(1).setEndColumnIndex(2))
                .setCell(new CellData().setUserEnteredFormat(new CellFormat()
                        .setWrapStrategy("WRAP")
                        .setVerticalAlignment("TOP")))
                .setFields("userEnteredFormat(wrapStrategy,verticalAlignment)"));
    }

    private BatchUpdateSpreadsheetResponse batch(List<Request> requests) throws java.io.IOException {
        return sheets.spreadsheets()
                .batchUpdate(spreadsheetId(),
                        new BatchUpdateSpreadsheetRequest().setRequests(requests))
                .execute();
    }
}
