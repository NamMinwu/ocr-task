package com.dreamtrue.ocr.sheets;

import com.dreamtrue.ocr.domain.ArchiveRecord;
import com.dreamtrue.ocr.domain.RecordAssembler;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

@Slf4j
@RequiredArgsConstructor
public class SheetsWriter {

    private final SheetsGateway gateway;

    /**
     * @param landscapeByFile 파일명 → 가로 문서 여부
     * @return 스프레드시트 URL
     */
    public String write(List<ArchiveRecord> records, Map<String, Boolean> landscapeByFile) {
        gateway.deleteAllSheetsExceptOne();

        // 1단계: 시트를 먼저 만들어 gid 를 수집한다.
        List<String> titles = new ArrayList<>();
        titles.add(SheetLayout.INDEX_SHEET);
        records.forEach(r -> titles.add(r.sheetName()));
        Map<String, Integer> gids = gateway.createSheets(titles);
        int indexGid = gids.get(SheetLayout.INDEX_SHEET);

        // 2단계: 수집한 gid 로 수식을 조립해 값을 기록한다.
        List<List<Object>> indexRows = new ArrayList<>();
        indexRows.add(SheetLayout.indexHeader());

        List<Runnable> detailWrites = new ArrayList<>();
        for (ArchiveRecord r : records) {
            RecordAssembler.Cells cells = RecordAssembler.assemble(r);
            int detailGid = gids.get(r.sheetName());
            indexRows.add(SheetLayout.indexRow(r, detailGid, cells.title()));

            boolean landscape = landscapeByFile.getOrDefault(r.fileName(), false);
            List<List<Object>> rows = SheetLayout.detailRows(r, cells, indexGid, landscape);
            detailWrites.add(() -> gateway.writeValues(r.sheetName(), rows));
        }

        gateway.writeValues(SheetLayout.INDEX_SHEET, indexRows);
        detailWrites.forEach(Runnable::run);

        gateway.applyFormatting(gids);

        return gateway.spreadsheetUrl();
    }
}
