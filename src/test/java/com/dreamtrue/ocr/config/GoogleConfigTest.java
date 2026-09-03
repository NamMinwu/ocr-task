package com.dreamtrue.ocr.config;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class GoogleConfigTest {

    @Test
    void OAuth_클라이언트_파일이_없으면_경로를_알려주며_실패한다(@TempDir Path dir) {
        Path missing = dir.resolve("oauth-client.json");

        assertThatThrownBy(() -> GoogleConfig.requireClientFile(missing))
                .isInstanceOf(IOException.class)
                .hasMessageContaining("oauth-client.json")
                .hasMessageContaining("OAuth 클라이언트");
    }

    @Test
    void OAuth_클라이언트_파일이_있으면_통과한다(@TempDir Path dir) throws IOException {
        Path present = dir.resolve("oauth-client.json");
        Files.writeString(present, "{}");

        assertThatCode(() -> GoogleConfig.requireClientFile(present)).doesNotThrowAnyException();
    }

    @Test
    void 스프레드시트_ID가_비어_있으면_설정_파일을_안내한다() {
        assertThatThrownBy(() -> GoogleConfig.requireSpreadsheetId(""))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("spreadsheet-id")
                .hasMessageContaining("application-local.yml");
    }

    @Test
    void 스프레드시트_ID가_있으면_그대로_돌려준다() {
        assertThatCode(() -> GoogleConfig.requireSpreadsheetId("SHEET123")).doesNotThrowAnyException();
    }
}
