package com.dreamtrue.ocr.image;

import com.dreamtrue.ocr.domain.SourceImage;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.stream.Stream;

@Slf4j
@Component
public class ImageScanner {

    public List<SourceImage> scan(Path dir) throws IOException {
        if (!Files.isDirectory(dir)) {
            throw new IOException("입력 디렉토리를 찾을 수 없습니다: " + dir);
        }

        List<Path> candidates;
        try (Stream<Path> files = Files.list(dir)) {
            candidates = files.filter(Files::isRegularFile)
                    .sorted(Comparator.comparing(p -> p.getFileName().toString()))
                    .toList();
        }

        List<SourceImage> result = new ArrayList<>();
        int detailNumber = 1;
        for (Path path : candidates) {
            Optional<String> mediaType = detectMediaType(path);
            if (mediaType.isEmpty()) {
                log.debug("이미지가 아니므로 건너뜁니다: {}", path.getFileName());
                continue;
            }
            BufferedImage image = ImageIO.read(path.toFile());
            if (image == null) {
                log.warn("이미지를 읽을 수 없어 건너뜁니다: {}", path.getFileName());
                continue;
            }
            result.add(new SourceImage(
                    path, mediaType.get(), image.getWidth(), image.getHeight(), detailNumber));
            detailNumber++;
        }
        return result;
    }

    /** 확장자가 아니라 파일 선두 바이트로 판별한다. */
    private Optional<String> detectMediaType(Path path) throws IOException {
        byte[] head = readHead(path, 12);
        if (head.length < 12) {
            return Optional.empty();
        }
        if ((head[0] & 0xFF) == 0xFF && (head[1] & 0xFF) == 0xD8) {
            return Optional.of("image/jpeg");
        }
        if ((head[0] & 0xFF) == 0x89 && head[1] == 'P' && head[2] == 'N' && head[3] == 'G') {
            return Optional.of("image/png");
        }
        if (head[0] == 'G' && head[1] == 'I' && head[2] == 'F') {
            return Optional.of("image/gif");
        }
        if (head[0] == 'R' && head[1] == 'I' && head[2] == 'F' && head[3] == 'F'
                && head[8] == 'W' && head[9] == 'E' && head[10] == 'B' && head[11] == 'P') {
            return Optional.of("image/webp");
        }
        return Optional.empty();
    }

    private byte[] readHead(Path path, int n) throws IOException {
        try (var in = Files.newInputStream(path)) {
            return in.readNBytes(n);
        }
    }
}
