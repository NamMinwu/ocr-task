package com.dreamtrue.ocr.domain;

public final class RecordAssembler {

    public record Cells(String title, String analysis, String photo) {}

    private RecordAssembler() {}

    public static Cells assemble(ArchiveRecord r) {
        String source = r.source().toString();

        String title = switch (r.ocr()) {
            case Outcome.Ok<OcrResult> ok -> ok.value().title();
            case Outcome.Failed<OcrResult> ignored -> r.fileName();
        };

        String analysis = switch (r.ocr()) {
            case Outcome.Ok<OcrResult> ok -> AnalysisComposer.compose(ok.value());
            case Outcome.Failed<OcrResult> f ->
                    "[OCR 실패] " + f.reason() + " — 원본: " + source;
        };

        String photo = switch (r.photoUrl()) {
            case Outcome.Ok<String> ok -> ok.value();
            case Outcome.Failed<String> f ->
                    "[사진 업로드 실패] " + f.reason() + " — 원본: " + source;
        };

        return new Cells(title, analysis, photo);
    }
}
