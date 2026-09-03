package com.dreamtrue.ocr.domain;

public final class AnalysisComposer {

    private static final String UNKNOWN = "확인되지 않음";
    private static final String NONE = "없음";

    private AnalysisComposer() {}

    public static String compose(OcrResult r) {
        StringBuilder sb = new StringBuilder();
        sb.append(blankTo(r.transcription(), ""));
        sb.append("\n\n※ 특이사항: ");
        sb.append("문서 종류는 ").append(blankTo(r.documentType(), UNKNOWN)).append(". ");
        sb.append("보존상태는 ").append(blankTo(r.preservationState(), UNKNOWN)).append(". ");
        sb.append("관인·서명: ").append(blankTo(r.sealsAndSignatures(), UNKNOWN)).append(". ");
        sb.append("판독 불가 부분: ").append(blankTo(r.illegibleParts(), NONE)).append(".");

        String era = r.eraNote();
        if (era != null && !era.isBlank()) {
            sb.append(" ").append(era.trim());
        }
        return sb.toString();
    }

    private static String blankTo(String value, String fallback) {
        return (value == null || value.isBlank()) ? fallback : value.trim();
    }
}
