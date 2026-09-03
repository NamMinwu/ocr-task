package com.dreamtrue.ocr.image;

import com.dreamtrue.ocr.domain.SourceImage;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ImageScannerTest {

    private final ImageScanner scanner = new ImageScanner();

    private void writeJpeg(Path p, int w, int h) throws IOException {
        ImageIO.write(new BufferedImage(w, h, BufferedImage.TYPE_INT_RGB), "jpg", p.toFile());
    }

    private void writePng(Path p, int w, int h) throws IOException {
        ImageIO.write(new BufferedImage(w, h, BufferedImage.TYPE_INT_RGB), "png", p.toFile());
    }

    @Test
    void 파일명순으로_세부번호를_1부터_부여한다(@TempDir Path dir) throws IOException {
        writeJpeg(dir.resolve("img_02.jpg"), 100, 200);
        writeJpeg(dir.resolve("img_01.jpg"), 100, 200);
        writeJpeg(dir.resolve("img_10.jpg"), 100, 200);

        List<SourceImage> found = scanner.scan(dir);

        assertThat(found).extracting(SourceImage::fileName)
                .containsExactly("img_01.jpg", "img_02.jpg", "img_10.jpg");
        assertThat(found).extracting(SourceImage::detailNumber)
                .containsExactly(1, 2, 3);
    }

    @Test
    void 해상도를_읽는다(@TempDir Path dir) throws IOException {
        writeJpeg(dir.resolve("a.jpg"), 1004, 1292);

        SourceImage img = scanner.scan(dir).getFirst();

        assertThat(img.width()).isEqualTo(1004);
        assertThat(img.height()).isEqualTo(1292);
        assertThat(img.longEdge()).isEqualTo(1292);
        assertThat(img.isLandscape()).isFalse();
    }

    @Test
    void 확장자가_아니라_매직바이트로_MIME을_판별한다(@TempDir Path dir) throws IOException {
        // .jpg 확장자인데 내용은 PNG
        writePng(dir.resolve("거짓말.jpg"), 50, 50);

        SourceImage img = scanner.scan(dir).getFirst();

        assertThat(img.mediaType()).isEqualTo("image/png");
    }

    @Test
    void 지원하지_않는_파일은_건너뛴다(@TempDir Path dir) throws IOException {
        writeJpeg(dir.resolve("a.jpg"), 10, 10);
        Files.writeString(dir.resolve("메모.txt"), "이건 이미지가 아님");
        Files.writeString(dir.resolve(".DS_Store"), "x");

        List<SourceImage> found = scanner.scan(dir);

        assertThat(found).hasSize(1);
        assertThat(found.getFirst().fileName()).isEqualTo("a.jpg");
    }

    @Test
    void 디렉토리가_없으면_명확히_실패한다(@TempDir Path dir) {
        Path missing = dir.resolve("없는폴더");

        assertThatThrownBy(() -> scanner.scan(missing))
                .isInstanceOf(IOException.class)
                .hasMessageContaining("없는폴더");
    }
}
