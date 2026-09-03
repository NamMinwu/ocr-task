package com.dreamtrue.ocr.domain;

import java.nio.file.Path;

public record ArchiveRecord(
        int folderNumber,
        int fileNumber,
        int detailNumber,
        Path source,
        Outcome<OcrResult> ocr,
        Outcome<String> photoUrl
) {
    public String sheetName() {
        return fileNumber + "-" + detailNumber;
    }

    public String fileName() {
        return source.getFileName().toString();
    }
}
