package com.dreamtrue.ocr.drive;

import com.dreamtrue.ocr.domain.Outcome;
import com.dreamtrue.ocr.domain.SourceImage;
import com.google.api.client.googleapis.json.GoogleJsonError;
import com.google.api.client.googleapis.json.GoogleJsonResponseException;
import com.google.api.client.http.HttpHeaders;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class DriveImageUploaderTest {

    /** 테스트용 대역: 실제 Drive 대신 호출 결과를 흉내낸다. */
    static class FakeDrive implements DriveImageUploader.DriveGateway {
        final List<String> calls = new ArrayList<>();
        IOException toThrow;
        String fileId = "FILE123";

        @Override
        public String uploadAndShare(Path file, String mediaType, String folderId) throws IOException {
            calls.add("upload:" + file.getFileName());
            if (toThrow != null) throw toThrow;
            return fileId;
        }

        @Override
        public boolean thumbnailReady(String url) {
            calls.add("check:" + url);
            return true;
        }
    }

    private static GoogleJsonResponseException httpError(int status, String message) {
        GoogleJsonError error = new GoogleJsonError();
        error.setCode(status);
        error.setMessage(message);
        return new GoogleJsonResponseException(
                new GoogleJsonResponseException.Builder(status, message, new HttpHeaders()), error);
    }

    private SourceImage image(Path p) {
        return new SourceImage(p, "image/jpeg", 1004, 1292, 3);
    }

    @Test
    void 썸네일_URL은_렌더링_가능한_형식이다() {
        assertThat(DriveImageUploader.thumbnailUrl("ABC123"))
                .isEqualTo("https://drive.google.com/thumbnail?id=ABC123&sz=w1000");
    }

    @Test
    void 업로드에_성공하면_썸네일_URL을_돌려준다(@TempDir Path dir) throws IOException {
        Path p = dir.resolve("img_03.jpg");
        Files.write(p, new byte[10]);
        FakeDrive drive = new FakeDrive();
        var uploader = new DriveImageUploader(drive, "FOLDER1");

        Outcome<String> out = uploader.upload(image(p));

        assertThat(out.isOk()).isTrue();
        assertThat(((Outcome.Ok<String>) out).value())
                .isEqualTo("https://drive.google.com/thumbnail?id=FILE123&sz=w1000");
    }

    @Test
    void 일시적_5xx는_재시도_후_개별실패로_격리된다(@TempDir Path dir) throws IOException {
        Path p = dir.resolve("img_03.jpg");
        Files.write(p, new byte[10]);
        FakeDrive drive = new FakeDrive();
        drive.toThrow = httpError(503, "backendError");
        var uploader = new DriveImageUploader(drive, "FOLDER1");

        Outcome<String> out = uploader.upload(image(p));

        assertThat(out.isOk()).isFalse();
        assertThat(((Outcome.Failed<String>) out).reason()).contains("503");
        assertThat(drive.calls).hasSize(3); // 3회 시도
    }

    @Test
    void 권한오류는_즉시_중단시킨다(@TempDir Path dir) throws IOException {
        Path p = dir.resolve("img_03.jpg");
        Files.write(p, new byte[10]);
        FakeDrive drive = new FakeDrive();
        drive.toThrow = httpError(403, "insufficientPermissions");
        var uploader = new DriveImageUploader(drive, "FOLDER1");

        assertThatThrownBy(() -> uploader.upload(image(p)))
                .isInstanceOf(SystemicFailureException.class)
                .hasMessageContaining("FOLDER1")
                .hasMessageContaining("편집자");

        assertThat(drive.calls).hasSize(1); // 재시도하지 않는다
    }

    @Test
    void 폴더를_찾을_수_없어도_즉시_중단시킨다(@TempDir Path dir) throws IOException {
        Path p = dir.resolve("img_03.jpg");
        Files.write(p, new byte[10]);
        FakeDrive drive = new FakeDrive();
        drive.toThrow = httpError(404, "notFound");
        var uploader = new DriveImageUploader(drive, "FOLDER1");

        assertThatThrownBy(() -> uploader.upload(image(p)))
                .isInstanceOf(SystemicFailureException.class);
        assertThat(drive.calls).hasSize(1);
    }
}
