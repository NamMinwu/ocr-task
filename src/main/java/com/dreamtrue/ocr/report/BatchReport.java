package com.dreamtrue.ocr.report;

import com.dreamtrue.ocr.domain.ArchiveRecord;
import com.dreamtrue.ocr.domain.Outcome;

import java.util.ArrayList;
import java.util.List;

public final class BatchReport {

    private record Failure(String fileName, int detailNumber, String what, String reason) {}

    private final List<ArchiveRecord> records;
    private final String spreadsheetUrl;
    private final String systemicMessage;

    private BatchReport(List<ArchiveRecord> records, String spreadsheetUrl, String systemicMessage) {
        this.records = records;
        this.spreadsheetUrl = spreadsheetUrl;
        this.systemicMessage = systemicMessage;
    }

    public static BatchReport of(List<ArchiveRecord> records, String spreadsheetUrl) {
        return new BatchReport(records, spreadsheetUrl, null);
    }

    public static BatchReport systemicFailure(String message) {
        return new BatchReport(List.of(), null, message);
    }

    public int exitCode() {
        if (systemicMessage != null) {
            return 2;
        }
        return failures().isEmpty() ? 0 : 1;
    }

    public String render() {
        if (systemicMessage != null) {
            return "[중단] " + systemicMessage;
        }

        List<Failure> failures = failures();
        int total = records.size();
        int success = total - (int) records.stream()
                .filter(r -> !r.ocr().isOk() || !r.photoUrl().isOk())
                .count();

        StringBuilder sb = new StringBuilder();
        sb.append("처리 완료: %d건 중 %d건 성공%n".formatted(total, success));

        if (!failures.isEmpty()) {
            sb.append("%n  실패 %d건%n".formatted(failures.size()));
            for (Failure f : failures) {
                sb.append("    %-14s 세부번호 %-3d %s (%s)%n"
                        .formatted(f.fileName(), f.detailNumber(), f.what(), f.reason()));
            }
            sb.append("%n  실패한 항목만 다시 시도:%n".formatted());
            sb.append("    ./gradlew bootRun --args='--retry-failed'%n".formatted());
        }
        sb.append("%n  시트: %s%n".formatted(spreadsheetUrl));
        return sb.toString();
    }

    private List<Failure> failures() {
        List<Failure> out = new ArrayList<>();
        for (ArchiveRecord r : records) {
            if (r.ocr() instanceof Outcome.Failed<?> f) {
                out.add(new Failure(r.fileName(), r.detailNumber(), "OCR 실패", f.reason()));
            }
            if (r.photoUrl() instanceof Outcome.Failed<?> f) {
                out.add(new Failure(r.fileName(), r.detailNumber(), "사진 업로드 실패", f.reason()));
            }
        }
        return out;
    }
}
