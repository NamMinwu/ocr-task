package com.dreamtrue.ocr.domain;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class AnalysisComposerTest {

    private OcrResult result(String eraNote) {
        return new OcrResult(
                "제목",
                "본문 전사\n둘째 줄",
                "인쇄체 단면 시행문",
                "용지 황변, 본문 판독 가능",
                "없음",
                "확인되지 않음",
                eraNote);
    }

    @Test
    void 전사_뒤에_특이사항_블록이_붙는다() {
        String s = AnalysisComposer.compose(result(null));

        assertThat(s).startsWith("본문 전사\n둘째 줄");
        assertThat(s).contains("※ 특이사항:");
        assertThat(s).contains("문서 종류는 인쇄체 단면 시행문");
        assertThat(s).contains("보존상태는 용지 황변, 본문 판독 가능");
        assertThat(s).contains("관인·서명: 확인되지 않음");
        assertThat(s).contains("판독 불가 부분: 없음");
    }

    @Test
    void eraNote가_없으면_생략된다() {
        String s = AnalysisComposer.compose(result(null));
        assertThat(s).doesNotContain("檀紀");
    }

    @Test
    void eraNote가_있으면_말미에_붙는다() {
        String s = AnalysisComposer.compose(result("檀紀 4296年은 서기 1963年에 해당함"));
        assertThat(s).endsWith("檀紀 4296年은 서기 1963年에 해당함");
    }

    @Test
    void eraNote가_빈문자열이어도_생략된다() {
        String s = AnalysisComposer.compose(result("   "));
        assertThat(s.trim()).endsWith("판독 불가 부분: 없음.");
    }

    @Test
    void 특이사항_블록은_항상_생성된다() {
        OcrResult empty = new OcrResult("t", "본문", null, null, null, null, null);
        String s = AnalysisComposer.compose(empty);

        assertThat(s).contains("※ 특이사항:");
        assertThat(s).contains("문서 종류는 확인되지 않음");
        assertThat(s).contains("판독 불가 부분: 없음");
    }
}
