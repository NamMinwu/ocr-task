package com.dreamtrue.ocr.sheets;

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

    private static final int ANALYSIS_ROW = 5; // 0-based, "분석(내용)"
    private static final int PHOTO_ROW = 6;    // 0-based, "사진"

    private final Sheets sheets;
    private final OcrProperties properties;

    private String spreadsheetId() {
        String id = properties.google().spreadsheetId();
        if (id == null || id.isBlank()) {
            throw new IllegalStateException(
                    "ocr.google.spreadsheet-id 가 비어 있습니다. application.yml 을 확인하세요.");
        }
        return id;
    }

    @Override
    @SneakyThrows
    public void deleteAllSheetsExceptOne() {
        Spreadsheet ss = sheets.spreadsheets().get(spreadsheetId()).execute();
        List<Sheet> existing = ss.getSheets();
        if (existing.size() <= 1) {
            return;
        }
        List<Request> requests = new ArrayList<>();
        for (int i = 1; i < existing.size(); i++) {
            requests.add(new Request().setDeleteSheet(
                    new DeleteSheetRequest().setSheetId(existing.get(i).getProperties().getSheetId())));
        }
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
    public void applyFormatting(Map<String, Integer> gids) {
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
        batch(requests);
    }

    @Override
    public String spreadsheetUrl() {
        return "https://docs.google.com/spreadsheets/d/" + spreadsheetId();
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
