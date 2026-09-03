package com.dreamtrue.ocr.domain;

import java.nio.file.Path;

public record SourceImage(
        Path path,
        String mediaType,
        int width,
        int height,
        int detailNumber
) {
    public String fileName() {
        return path.getFileName().toString();
    }

    public int longEdge() {
        return Math.max(width, height);
    }

    public boolean isLandscape() {
        return width > height;
    }
}
