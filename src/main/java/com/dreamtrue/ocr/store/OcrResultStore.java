package com.dreamtrue.ocr.store;

import com.dreamtrue.ocr.domain.OcrResult;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.json.JsonMapper;
import lombok.extern.slf4j.Slf4j;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.OffsetDateTime;
import java.util.Optional;

@Slf4j
public class OcrResultStore {

    /** 디스크에 남는 형식. result 외의 필드는 사람이 읽기 위한 것이다. */
    public record Entry(
            String sourceFile,
            String model,
            String ocredAt,
            OcrResult result
    ) {}

    private final Path rawDir;
    private final ObjectMapper mapper;

    public OcrResultStore(Path outputDir) {
        this.rawDir = outputDir.resolve("raw");
        this.mapper = JsonMapper.builder()
                .enable(SerializationFeature.INDENT_OUTPUT)
                .build();
    }

    public void write(String fileName, String model, OcrResult result) throws IOException {
        Files.createDirectories(rawDir);
        Entry entry = new Entry(fileName, model, OffsetDateTime.now().toString(), result);
        Files.writeString(pathFor(fileName), mapper.writeValueAsString(entry));
    }

    public Optional<OcrResult> read(String fileName) throws IOException {
        Path path = pathFor(fileName);
        if (!Files.exists(path)) {
            return Optional.empty();
        }
        Entry entry = mapper.readValue(Files.readString(path), Entry.class);
        return Optional.ofNullable(entry.result());
    }

    private Path pathFor(String fileName) {
        int dot = fileName.lastIndexOf('.');
        String base = dot > 0 ? fileName.substring(0, dot) : fileName;
        return rawDir.resolve(base + ".json");
    }
}
