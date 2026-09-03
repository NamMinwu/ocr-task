package com.dreamtrue.ocr.domain;

import org.junit.jupiter.api.Test;

import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

class OutcomeTest {

    @Test
    void ok는_값을_담는다() {
        Outcome<String> o = Outcome.ok("값");
        assertThat(o.isOk()).isTrue();
        assertThat(((Outcome.Ok<String>) o).value()).isEqualTo("값");
    }

    @Test
    void failed는_사유를_담는다() {
        Outcome<String> o = Outcome.failed("403 권한 없음");
        assertThat(o.isOk()).isFalse();
        assertThat(((Outcome.Failed<String>) o).reason()).isEqualTo("403 권한 없음");
    }

    @Test
    void 패턴매칭으로_분기할_수_있다() {
        Outcome<String> o = Outcome.failed("사유");
        String s = switch (o) {
            case Outcome.Ok<String> ok -> "성공:" + ok.value();
            case Outcome.Failed<String> f -> "실패:" + f.reason();
        };
        assertThat(s).isEqualTo("실패:사유");
    }

    @Test
    void 시트이름은_파일번호와_세부번호를_잇는다() {
        ArchiveRecord r = new ArchiveRecord(
                1, 1, 3, Path.of("input/img_03.jpg"),
                Outcome.failed("x"), Outcome.failed("y"));
        assertThat(r.sheetName()).isEqualTo("1-3");
    }
}
