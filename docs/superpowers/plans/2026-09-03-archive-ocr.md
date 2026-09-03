# 기록물 정리 OCR 솔루션 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 디렉토리의 기록물 사진을 Claude로 OCR 하여 결과와 원본 사진을 Google Sheet에 자동 기록하는 CLI 배치 프로그램을 만든다.

**Architecture:** 로컬 이미지 파일에서 두 갈래가 독립적으로 갈라진다 — 갈래 A는 base64로 Claude Messages API를 호출해 구조화 출력을 받고, 갈래 B는 원본 바이트를 Drive에 올려 `=IMAGE()`용 URL을 얻는다. 두 결과를 `Outcome<T>`로 감싸 `ArchiveRecord`에 담고, 전부 모인 뒤 Google Sheets에 1회 기록한다. 이미지는 파일명 순으로 순차 처리한다.

**Tech Stack:** Java 21, Spring Boot 4.1.1, Gradle 9.7.1 (Wrapper 동봉), Lombok 1.18.44, anthropic-java 2.34.0, google-api-services-sheets/drive, JUnit 5 + AssertJ + MockWebServer

**Spec:** `docs/superpowers/specs/2026-09-03-archive-ocr-design.md`

## Global Constraints

- Java 21 toolchain. Spring Boot 4.1.1. Gradle 9.7.1 (`./gradlew`로만 실행, 시스템 gradle 요구 금지)
- 모델 기본값은 `claude-opus-5`. 설정 `ocr.claude.model`과 `--model`로 전환 가능
- **`temperature`/`top_p`/`top_k` 사용 금지** — Opus 5 계열에서 제거되어 400 응답
- **`.outputConfig()`에 effort를 함께 줄 수 없다** — 이 setter는 구조화 출력용 `Class` 또는 effort용 `OutputConfig` 중 하나만 받는다. spec의 `effort: high`는 API 기본값과 동일하므로 **effort를 생략**한다
- 이미지는 리사이즈·재인코딩하지 않는다. 장변 2576px 초과 또는 base64 10MB 초과 시 축소하지 말고 명확한 메시지로 실패시킨다
- 파일번호는 1 고정, 폴더번호 1, 세부번호는 파일명 정렬 순 1..N. **채번은 OCR 이전에 확정**한다
- 데이터 타입은 `record`와 `sealed interface`를 쓴다. Lombok은 `@Slf4j`와 `@RequiredArgsConstructor`에만 쓴다
- 자격증명(`credentials/`)과 출력물(`output/`)은 `.gitignore` 대상
- 한 파일 200~400줄 목표, 800줄 초과 금지
- 모든 사용자 대면 문자열과 로그는 한국어

## 사전 확인된 사실 (재조사 불필요)

아래는 이 계획 작성 시 실제로 실행해 확인한 것이다. 추측이 아니다.

- `./gradlew build` 통과 (Spring Boot 4.1.1 + Gradle 9.7.1 + Java 21 + Lombok 애노테이션 처리)
- 다음 Anthropic SDK 형태가 컴파일된다:
  - `ContentBlockParam.ofImage(ImageBlockParam.builder().source(Base64ImageSource.builder().mediaType(Base64ImageSource.MediaType.IMAGE_JPEG).data(b64).build()).build())`
  - `MessageCreateParams.builder().model(String).maxTokens(Long).thinking(ThinkingConfigAdaptive.builder().build()).outputConfig(OcrResult.class).addUserMessageOfBlockParams(List<ContentBlockParam>).build()` → `StructuredMessageCreateParams<OcrResult>`
  - record 컴포넌트의 `@JsonPropertyDescription`이 스키마에 반영된다
- 입력 이미지 10장 실측: 전부 baseline JPEG, 장변 최대 1400px, 파일 최대 122KB
- 골격은 이미 프로젝트에 설치되어 있다 (`build.gradle`, `settings.gradle`, `gradle/libs.versions.toml`, `gradlew`, `src/main/java/com/dreamtrue/ocr/OcrApplication.java`)

## File Structure

| 파일 | 책임 |
|---|---|
| `config/OcrProperties.java` | `ocr.*` 설정 바인딩 |
| `domain/Outcome.java` | 성공값 또는 실패사유 (sealed) |
| `domain/SourceImage.java` | 입력 이미지 1장의 메타 |
| `domain/OcrResult.java` | 구조화 출력 스키마 |
| `domain/ArchiveRecord.java` | 시트 1건 = 번호 + 두 개의 Outcome |
| `domain/AnalysisComposer.java` | OcrResult → 분석(내용) 문자열 |
| `domain/RecordAssembler.java` | 실패 조합 4가지 → 셀 문자열 3개 |
| `image/ImageScanner.java` | 스캔·정렬·MIME 판별·채번 |
| `claude/OcrPrompt.java` | 시스템/사용자 프롬프트 |
| `claude/ClaudeOcrClient.java` | Messages API 호출, 크기 검증, 재시도 |
| `store/OcrResultStore.java` | `output/raw/*.json` 쓰기·읽기 |
| `drive/DriveImageUploader.java` | 업로드·권한·썸네일 URL |
| `drive/SystemicFailureException.java` | 계통 실패 (제어 흐름 차단) |
| `sheets/SheetLayout.java` | 셀 좌표·수식 조립 (순수) |
| `sheets/SheetsWriter.java` | 시트 생성 2단계·값·서식 |
| `report/BatchReport.java` | 요약 출력·종료 코드 |
| `runner/OcrBatchRunner.java` | 전체 배선, CLI 플래그 |

---

### Task 1: 설정 바인딩과 프로젝트 정리

**Files:**
- Create: `src/main/java/com/dreamtrue/ocr/config/OcrProperties.java`
- Create: `src/main/resources/application.yml`
- Create: `application-example.yml`
- Modify: `.gitignore`
- Modify: `src/main/java/com/dreamtrue/ocr/OcrApplication.java`
- Test: `src/test/java/com/dreamtrue/ocr/config/OcrPropertiesTest.java`

**Interfaces:**
- Produces: `OcrProperties` — `inputDir()`, `outputDir()`, `folderNumber()`, `fileNumber()`, `claude()`, `google()`. 중첩 record `Claude(String model, long maxTokens)`, `Google(String credentialsPath, String spreadsheetId, String driveFolderId)`

- [ ] **Step 1: Write the failing test**

```java
package com.dreamtrue.ocr.config;

import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import static org.assertj.core.api.Assertions.assertThat;

class OcrPropertiesTest {

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withUserConfiguration(Config.class);

    @EnableConfigurationProperties(OcrProperties.class)
    static class Config {}

    @Test
    void 기본값이_적용된다() {
        runner.run(ctx -> {
            OcrProperties p = ctx.getBean(OcrProperties.class);
            assertThat(p.inputDir()).isEqualTo("./input");
            assertThat(p.outputDir()).isEqualTo("./output");
            assertThat(p.folderNumber()).isEqualTo(1);
            assertThat(p.fileNumber()).isEqualTo(1);
            assertThat(p.claude().model()).isEqualTo("claude-opus-5");
            assertThat(p.claude().maxTokens()).isEqualTo(16000L);
        });
    }

    @Test
    void 설정값이_바인딩된다() {
        runner.withPropertyValues(
                "ocr.input-dir=/tmp/in",
                "ocr.claude.model=claude-sonnet-5",
                "ocr.google.spreadsheet-id=SHEET123",
                "ocr.google.drive-folder-id=FOLDER456"
        ).run(ctx -> {
            OcrProperties p = ctx.getBean(OcrProperties.class);
            assertThat(p.inputDir()).isEqualTo("/tmp/in");
            assertThat(p.claude().model()).isEqualTo("claude-sonnet-5");
            assertThat(p.google().spreadsheetId()).isEqualTo("SHEET123");
            assertThat(p.google().driveFolderId()).isEqualTo("FOLDER456");
        });
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew test --tests '*OcrPropertiesTest*'`
Expected: FAIL — `cannot find symbol: class OcrProperties`

- [ ] **Step 3: Write minimal implementation**

`src/main/java/com/dreamtrue/ocr/config/OcrProperties.java`:

```java
package com.dreamtrue.ocr.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

@ConfigurationProperties(prefix = "ocr")
public record OcrProperties(
        @DefaultValue("./input") String inputDir,
        @DefaultValue("./output") String outputDir,
        @DefaultValue("1") int folderNumber,
        @DefaultValue("1") int fileNumber,
        @DefaultValue Claude claude,
        @DefaultValue Google google
) {
    public record Claude(
            @DefaultValue("claude-opus-5") String model,
            @DefaultValue("16000") long maxTokens
    ) {}

    public record Google(
            @DefaultValue("./credentials/service-account.json") String credentialsPath,
            @DefaultValue("") String spreadsheetId,
            @DefaultValue("") String driveFolderId
    ) {}
}
```

`src/main/java/com/dreamtrue/ocr/OcrApplication.java` 를 교체:

```java
package com.dreamtrue.ocr;

import com.dreamtrue.ocr.config.OcrProperties;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;

@SpringBootApplication
@EnableConfigurationProperties(OcrProperties.class)
public class OcrApplication {
    public static void main(String[] args) {
        System.exit(SpringApplication.exit(SpringApplication.run(OcrApplication.class, args)));
    }
}
```

`src/main/resources/application.yml`:

```yaml
spring:
  main:
    web-application-type: none
    banner-mode: off
logging:
  pattern:
    console: "%d{HH:mm:ss} %-5level %msg%n"
  level:
    root: WARN
    com.dreamtrue.ocr: INFO

ocr:
  input-dir: ./input
  output-dir: ./output
  folder-number: 1
  file-number: 1
  claude:
    model: claude-opus-5
    max-tokens: 16000
  google:
    credentials-path: ./credentials/service-account.json
    spreadsheet-id: ""
    drive-folder-id: ""
```

`application-example.yml` (저장소 루트, 제출물에 포함):

```yaml
# 이 파일을 src/main/resources/application.yml 로 복사한 뒤 값을 채우세요.
ocr:
  google:
    credentials-path: ./credentials/service-account.json
    # 스프레드시트 URL의 /d/ 와 /edit 사이 문자열
    spreadsheet-id: "여기에_스프레드시트_ID"
    # Drive 폴더 URL의 folders/ 뒤 문자열
    drive-folder-id: "여기에_폴더_ID"
```

`.gitignore` 끝에 추가:

```
credentials/
output/
```

- [ ] **Step 4: Run test to verify it passes**

Run: `./gradlew test --tests '*OcrPropertiesTest*'`
Expected: PASS (2 tests)

- [ ] **Step 5: Commit**

```bash
git add -A
git commit -m "feat: 프로젝트 골격과 설정 바인딩 추가"
```

---

### Task 2: 도메인 타입 (Outcome, SourceImage, OcrResult, ArchiveRecord)

**Files:**
- Create: `src/main/java/com/dreamtrue/ocr/domain/Outcome.java`
- Create: `src/main/java/com/dreamtrue/ocr/domain/SourceImage.java`
- Create: `src/main/java/com/dreamtrue/ocr/domain/OcrResult.java`
- Create: `src/main/java/com/dreamtrue/ocr/domain/ArchiveRecord.java`
- Test: `src/test/java/com/dreamtrue/ocr/domain/OutcomeTest.java`

**Interfaces:**
- Produces:
  - `Outcome<T>` sealed — `Outcome.Ok<T>(T value)`, `Outcome.Failed<T>(String reason)`; 정적 팩토리 `Outcome.ok(T)`, `Outcome.failed(String)`; `boolean isOk()`
  - `SourceImage(Path path, String mediaType, int width, int height, int detailNumber)` — `String fileName()`
  - `OcrResult(String title, String transcription, String documentType, String preservationState, String illegibleParts, String sealsAndSignatures, String eraNote)`
  - `ArchiveRecord(int folderNumber, int fileNumber, int detailNumber, Path source, Outcome<OcrResult> ocr, Outcome<String> photoUrl)` — `String sheetName()` → `"1-3"`

- [ ] **Step 1: Write the failing test**

```java
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
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew test --tests '*OutcomeTest*'`
Expected: FAIL — `cannot find symbol: class Outcome`

- [ ] **Step 3: Write minimal implementation**

`domain/Outcome.java`:

```java
package com.dreamtrue.ocr.domain;

public sealed interface Outcome<T> permits Outcome.Ok, Outcome.Failed {

    record Ok<T>(T value) implements Outcome<T> {}

    record Failed<T>(String reason) implements Outcome<T> {}

    static <T> Outcome<T> ok(T value) {
        return new Ok<>(value);
    }

    static <T> Outcome<T> failed(String reason) {
        return new Failed<>(reason);
    }

    default boolean isOk() {
        return this instanceof Ok<T>;
    }
}
```

`domain/SourceImage.java`:

```java
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
```

`domain/OcrResult.java`:

```java
package com.dreamtrue.ocr.domain;

import com.fasterxml.jackson.annotation.JsonPropertyDescription;

public record OcrResult(
        @JsonPropertyDescription("문서의 성격을 나타내는 제목. 문서번호·일자 등 식별정보가 있으면 괄호로 병기한다. 예: 보관증 — 김경석 수집 기록물 인수·보관 증명 (1968.8.1.)")
        String title,

        @JsonPropertyDescription("원문을 레이아웃 그대로 옮긴 전사. 표는 '항목 | 항목' 파이프 형식으로 옮긴다. 요약·번역하지 말고 원문 표기를 보존한다. 특이사항은 여기에 쓰지 않는다.")
        String transcription,

        @JsonPropertyDescription("문서 종류. 예: 인쇄체 단면 시행문(통보 공문), 손글씨 낱장 메모(작업 일지), 기록물 편철 표지")
        String documentType,

        @JsonPropertyDescription("보존상태. 황변·얼룩·수침·훼손 여부와 본문 판독에 지장이 있는지. 예: 용지 전반 황변, 우측 하단 원형 얼룩, 본문 판독 가능")
        String preservationState,

        @JsonPropertyDescription("판독이 불가능한 구간. 추측해서 채우지 말고 여기에 기술한다. 없으면 정확히 '없음'")
        String illegibleParts,

        @JsonPropertyDescription("관인·서명의 유무와 형태. 없으면 정확히 '확인되지 않음'")
        String sealsAndSignatures,

        @JsonPropertyDescription("檀紀·간지 등 서기가 아닌 연호가 있으면 서기 환산 주석. 해당 없으면 null")
        String eraNote
) {}
```

`domain/ArchiveRecord.java`:

```java
package com.dreamtrue.ocr.domain;

import java.nio.file.Path;

public record ArchiveRecord(
        int folderNumber,
        int fileNumber,
        int detailNumber,
        Path source,
        Outcome<OcrResult> ocr,
        Outcome<String> photoUrl
) {
    public String sheetName() {
        return fileNumber + "-" + detailNumber;
    }

    public String fileName() {
        return source.getFileName().toString();
    }
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `./gradlew test --tests '*OutcomeTest*'`
Expected: PASS (4 tests)

- [ ] **Step 5: Commit**

```bash
git add -A
git commit -m "feat: 도메인 타입 추가 (Outcome, SourceImage, OcrResult, ArchiveRecord)"
```

---

### Task 3: ImageScanner — 스캔·정렬·MIME 판별·채번

**Files:**
- Create: `src/main/java/com/dreamtrue/ocr/image/ImageScanner.java`
- Test: `src/test/java/com/dreamtrue/ocr/image/ImageScannerTest.java`

**Interfaces:**
- Consumes: `SourceImage`, `OcrProperties`
- Produces: `ImageScanner.scan(Path dir) throws IOException` → `List<SourceImage>` (파일명 오름차순, `detailNumber` 1부터 부여)

**설계 근거:** MIME은 확장자가 아니라 매직 바이트로 판별한다. `.jpg` 확장자에 PNG가 들어있으면 API가 거부하는데 확장자만 믿으면 원인 파악이 어려운 오류가 된다. 채번은 스캔 시점에 확정되어 이후 어떤 실패에도 밀리지 않는다.

- [ ] **Step 1: Write the failing test**

```java
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
```

`import static org.assertj.core.api.Assertions.assertThatThrownBy;` 를 함께 추가한다.

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew test --tests '*ImageScannerTest*'`
Expected: FAIL — `cannot find symbol: class ImageScanner`

- [ ] **Step 3: Write minimal implementation**

```java
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
```

- [ ] **Step 4: Run test to verify it passes**

Run: `./gradlew test --tests '*ImageScannerTest*'`
Expected: PASS (5 tests)

- [ ] **Step 5: Commit**

```bash
git add -A
git commit -m "feat: ImageScanner 추가 (매직바이트 MIME 판별, OCR 이전 채번)"
```

---

### Task 4: AnalysisComposer — 분석(내용) 조립

**Files:**
- Create: `src/main/java/com/dreamtrue/ocr/domain/AnalysisComposer.java`
- Test: `src/test/java/com/dreamtrue/ocr/domain/AnalysisComposerTest.java`

**Interfaces:**
- Consumes: `OcrResult`
- Produces: `AnalysisComposer.compose(OcrResult)` → `String` (정적 메서드)

**설계 근거:** 평가양식 예시의 특이사항 표기가 `※ 특이사항:` / `※ 문서 종류:` / `[특이사항]` 세 가지로 제각각이다. 모델에게 통째로 맡기지 않고 필드로 받아 코드가 조립하면 10건이 동일한 순서를 갖고, 모델이 항목을 누락할 수 없다.

- [ ] **Step 1: Write the failing test**

```java
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
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew test --tests '*AnalysisComposerTest*'`
Expected: FAIL — `cannot find symbol: class AnalysisComposer`

- [ ] **Step 3: Write minimal implementation**

```java
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
```

- [ ] **Step 4: Run test to verify it passes**

Run: `./gradlew test --tests '*AnalysisComposerTest*'`
Expected: PASS (5 tests)

- [ ] **Step 5: Commit**

```bash
git add -A
git commit -m "feat: AnalysisComposer 추가 (특이사항 블록 항상 생성)"
```

---

### Task 5: RecordAssembler — 실패 조합 4가지를 셀 문자열로

**Files:**
- Create: `src/main/java/com/dreamtrue/ocr/domain/RecordAssembler.java`
- Test: `src/test/java/com/dreamtrue/ocr/domain/RecordAssemblerTest.java`

**Interfaces:**
- Consumes: `ArchiveRecord`, `Outcome`, `AnalysisComposer`
- Produces: `RecordAssembler.assemble(ArchiveRecord)` → `RecordAssembler.Cells(String title, String analysis, String photo)` (정적 메서드)

**설계 근거:** spec §6.1의 실패 조합 4가지를 한 곳의 패턴 매칭으로 표현한다. 이 규칙이 여러 컴포넌트에 흩어지면 "OCR 실패면 제목은 파일명" 같은 결정이 세 군데에 중복된다. 외부 I/O가 없어 네 조합을 그대로 단위테스트한다.

- [ ] **Step 1: Write the failing test**

```java
package com.dreamtrue.ocr.domain;

import org.junit.jupiter.api.Test;

import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

class RecordAssemblerTest {

    private static final OcrResult OCR = new OcrResult(
            "보관증 — 김경석 수집 기록물 (1968.8.1.)",
            "보 관 증\n아래 기록물을 정히 인수하여",
            "인쇄체 국한문 혼용 단면 문서",
            "훼손·오염 없음",
            "없음",
            "붉은색 원형 관인 날인",
            null);

    private ArchiveRecord record(Outcome<OcrResult> ocr, Outcome<String> photo) {
        return new ArchiveRecord(1, 1, 3, Path.of("input/img_03.jpg"), ocr, photo);
    }

    @Test
    void 둘_다_성공하면_정상_기록된다() {
        var cells = RecordAssembler.assemble(
                record(Outcome.ok(OCR), Outcome.ok("https://drive.google.com/thumbnail?id=X&sz=w1000")));

        assertThat(cells.title()).isEqualTo("보관증 — 김경석 수집 기록물 (1968.8.1.)");
        assertThat(cells.analysis()).contains("보 관 증").contains("※ 특이사항:");
        assertThat(cells.photo()).isEqualTo("https://drive.google.com/thumbnail?id=X&sz=w1000");
    }

    @Test
    void OCR성공_업로드실패면_사진칸에_사유와_원본경로가_남는다() {
        var cells = RecordAssembler.assemble(
                record(Outcome.ok(OCR), Outcome.failed("503 backendError")));

        assertThat(cells.title()).isEqualTo("보관증 — 김경석 수집 기록물 (1968.8.1.)");
        assertThat(cells.analysis()).contains("※ 특이사항:");
        assertThat(cells.photo())
                .startsWith("[사진 업로드 실패] 503 backendError")
                .contains("원본: input/img_03.jpg");
    }

    @Test
    void OCR실패_업로드성공이면_제목은_파일명으로_대체된다() {
        var cells = RecordAssembler.assemble(
                record(Outcome.failed("429 rate limit"), Outcome.ok("https://x/y")));

        assertThat(cells.title()).isEqualTo("img_03.jpg");
        assertThat(cells.analysis())
                .startsWith("[OCR 실패] 429 rate limit")
                .contains("원본: input/img_03.jpg");
        assertThat(cells.photo()).isEqualTo("https://x/y");
    }

    @Test
    void 둘_다_실패하면_두_칸_모두_실패표시된다() {
        var cells = RecordAssembler.assemble(
                record(Outcome.failed("OCR 사유"), Outcome.failed("업로드 사유")));

        assertThat(cells.title()).isEqualTo("img_03.jpg");
        assertThat(cells.analysis()).startsWith("[OCR 실패] OCR 사유");
        assertThat(cells.photo()).startsWith("[사진 업로드 실패] 업로드 사유");
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew test --tests '*RecordAssemblerTest*'`
Expected: FAIL — `cannot find symbol: class RecordAssembler`

- [ ] **Step 3: Write minimal implementation**

```java
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
```

- [ ] **Step 4: Run test to verify it passes**

Run: `./gradlew test --tests '*RecordAssemblerTest*'`
Expected: PASS (4 tests)

- [ ] **Step 5: Commit**

```bash
git add -A
git commit -m "feat: RecordAssembler 추가 (실패 조합 4가지를 한 곳에서 처리)"
```

---

### Task 6: OcrPrompt — 프롬프트 텍스트

**Files:**
- Create: `src/main/java/com/dreamtrue/ocr/claude/OcrPrompt.java`
- Test: `src/test/java/com/dreamtrue/ocr/claude/OcrPromptTest.java`

**Interfaces:**
- Produces: `OcrPrompt.SYSTEM` (String 상수), `OcrPrompt.USER` (String 상수)

**설계 근거:** 규칙은 평가양식 예시 정답에서 역산했다. 표 파이프 형식(1-2 수집자료 정리 목록표), 한자 독음 병기(`記錄物目錄(기록물 목록)`), 연호 환산(`檀紀 4296年`), 세로쓰기 대괄호 표기(1-6 표지)가 근거다. 프롬프트를 상수로 분리하면 프롬프트 튜닝이 이 파일 하나로 격리된다.

- [ ] **Step 1: Write the failing test**

```java
package com.dreamtrue.ocr.claude;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class OcrPromptTest {

    @Test
    void 시스템프롬프트에_핵심_금지규칙이_모두_들어있다() {
        assertThat(OcrPrompt.SYSTEM)
                .contains("추측")
                .contains("요약")
                .contains("번역");
    }

    @Test
    void 시스템프롬프트에_표_파이프_규칙이_있다() {
        assertThat(OcrPrompt.SYSTEM).contains("|");
    }

    @Test
    void 시스템프롬프트에_한자와_연호_처리_규칙이_있다() {
        assertThat(OcrPrompt.SYSTEM)
                .contains("한자")
                .contains("檀紀");
    }

    @Test
    void 사용자프롬프트는_비어있지_않다() {
        assertThat(OcrPrompt.USER).isNotBlank();
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew test --tests '*OcrPromptTest*'`
Expected: FAIL — `cannot find symbol: class OcrPrompt`

- [ ] **Step 3: Write minimal implementation**

```java
package com.dreamtrue.ocr.claude;

public final class OcrPrompt {

    private OcrPrompt() {}

    public static final String SYSTEM = """
            당신은 한국 근현대 기록물을 정리하는 아키비스트입니다.
            사진으로 제시된 기록물 1건을 판독하여 정해진 항목으로 기술합니다.

            [전사 원칙]
            - 원문의 레이아웃을 최대한 보존하여 옮깁니다. 줄바꿈과 항목 구분을 유지합니다.
            - 표는 머리행을 포함해 파이프로 구분합니다.
              예: 연번 | 자료명 | 수량 | 생산연도 | 비고
            - 한자는 원문 그대로 두고 필요하면 괄호로 독음을 병기합니다.
              예: 記錄物目錄(기록물 목록), 二百二十四(224) 묶음
            - 세로쓰기·테두리·중앙 배치 등 레이아웃 특성은 대괄호로 명시합니다.
              예: [표지 중앙 세로쓰기 · 사각 테두리 안]

            [금지 사항]
            - 추측 금지: 보이지 않는 글자를 그럴듯하게 채우지 않습니다.
              판독 불가 구간은 전사에 넣지 말고 illegibleParts 항목에 기술합니다.
            - 요약 금지: 이것은 전사이지 요약이 아닙니다. 본문을 줄이지 않습니다.
            - 번역 금지: 현대어로 바꾸지 않고 원문 표기를 보존합니다.

            [항목별 지침]
            - title: 문서의 성격을 나타내는 제목. 문서번호·일자 등 식별정보가 있으면
              괄호로 병기합니다.
            - transcription: 본문 전사만 넣습니다. 특이사항은 여기에 쓰지 않습니다.
            - documentType: 인쇄체/손글씨, 시행문/대장/표지/서한 등 문서 종류.
            - preservationState: 황변·얼룩·수침·훼손 여부와 본문 판독 지장 여부.
            - illegibleParts: 판독 불가 구간. 없으면 정확히 "없음".
            - sealsAndSignatures: 관인·서명의 유무와 형태. 없으면 정확히 "확인되지 않음".
            - eraNote: 檀紀·간지 등 서기가 아닌 연호가 있으면 서기로 환산해 주석합니다.
              예: 檀紀 4296年은 서기 1963年에 해당함(원문에는 서기 표기 없음).
              해당 사항이 없으면 null.
            """;

    public static final String USER =
            "이 기록물 사진을 판독하여 각 항목을 채워 주세요.";
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `./gradlew test --tests '*OcrPromptTest*'`
Expected: PASS (4 tests)

- [ ] **Step 5: Commit**

```bash
git add -A
git commit -m "feat: OcrPrompt 추가 (평가양식 예시에서 역산한 전사 규칙)"
```

---

### Task 7: ClaudeOcrClient — 크기 검증과 API 호출

**Files:**
- Create: `src/main/java/com/dreamtrue/ocr/claude/ClaudeOcrClient.java`
- Create: `src/main/java/com/dreamtrue/ocr/claude/ImageTooLargeException.java`
- Test: `src/test/java/com/dreamtrue/ocr/claude/ClaudeOcrClientTest.java`

**Interfaces:**
- Consumes: `SourceImage`, `OcrResult`, `Outcome`, `OcrPrompt`, `OcrProperties`
- Produces:
  - `ClaudeOcrClient.validateSize(SourceImage)` → `void`, 초과 시 `ImageTooLargeException`
  - `ClaudeOcrClient.encodeBase64(Path)` → `String`
  - `ClaudeOcrClient.buildContent(SourceImage, String base64)` → `List<ContentBlockParam>`
  - `ClaudeOcrClient.buildParams(SourceImage, String base64)` → `StructuredMessageCreateParams<OcrResult>`
  - `ClaudeOcrClient.ocr(SourceImage)` → `Outcome<OcrResult>`

**설계 근거 (재조사 불필요):**
- 상한값은 Claude Vision 문서 확인값이다. 고해상도 티어(Claude 4.7 이후, Opus 5 포함) 장변 **2576px**, 이미지당 base64 **10MB**(직접 호출 시). 입력 10장은 장변 최대 1400px이므로 발동하지 않는다.
- `.outputConfig(OcrResult.class)`와 effort는 **동시에 지정할 수 없다.** effort 기본값이 `high`라 생략한다.
- `temperature`/`top_p`/`top_k`는 Opus 5에서 제거되어 400을 반환하므로 쓰지 않는다.
- 이미지 블록을 텍스트보다 **먼저** 넣는다 (공식 권장 image-then-text).

- [ ] **Step 1: Write the failing test**

```java
package com.dreamtrue.ocr.claude;

import com.anthropic.models.messages.ContentBlockParam;
import com.dreamtrue.ocr.domain.SourceImage;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ClaudeOcrClientTest {

    private SourceImage image(Path p, int w, int h) {
        return new SourceImage(p, "image/jpeg", w, h, 1);
    }

    @Test
    void 입력_규격의_이미지는_검증을_통과한다(@TempDir Path dir) throws IOException {
        Path p = dir.resolve("img_08.jpg");
        Files.write(p, new byte[89_000]);

        assertThatCode(() -> ClaudeOcrClient.validateSize(image(p, 1150, 1350)))
                .doesNotThrowAnyException();
    }

    @Test
    void 장변이_2576px를_넘으면_축소하지_않고_실패한다(@TempDir Path dir) throws IOException {
        Path p = dir.resolve("big.jpg");
        Files.write(p, new byte[1000]);

        assertThatThrownBy(() -> ClaudeOcrClient.validateSize(image(p, 3000, 4000)))
                .isInstanceOf(ImageTooLargeException.class)
                .hasMessageContaining("2576")
                .hasMessageContaining("4000");
    }

    @Test
    void base64가_10MB를_넘으면_실패한다(@TempDir Path dir) throws IOException {
        Path p = dir.resolve("heavy.jpg");
        // base64는 원본의 약 4/3배. 8MB 원본 -> 약 10.7MB
        Files.write(p, new byte[8 * 1024 * 1024]);

        assertThatThrownBy(() -> ClaudeOcrClient.validateSize(image(p, 100, 100)))
                .isInstanceOf(ImageTooLargeException.class)
                .hasMessageContaining("10MB");
    }

    @Test
    void 파일을_base64로_인코딩한다(@TempDir Path dir) throws IOException {
        Path p = dir.resolve("a.jpg");
        Files.write(p, "ABC".getBytes());

        assertThat(ClaudeOcrClient.encodeBase64(p)).isEqualTo("QUJD");
    }

    @Test
    void 이미지_블록이_텍스트보다_먼저_들어간다(@TempDir Path dir) throws IOException {
        Path p = dir.resolve("a.jpg");
        Files.write(p, new byte[10]);

        // image-then-text 순서는 공식 권장이며 판독 품질에 영향을 준다.
        List<ContentBlockParam> blocks = ClaudeOcrClient.buildContent(image(p, 1000, 1400), "QUJD");

        assertThat(blocks).hasSize(2);
        assertThat(blocks.getFirst().isImage()).isTrue();
        assertThat(blocks.get(1).isText()).isTrue();
    }

    @Test
    void 요청_파라미터가_예외없이_빌드된다(@TempDir Path dir) throws IOException {
        Path p = dir.resolve("a.jpg");
        Files.write(p, new byte[10]);

        assertThatCode(() -> ClaudeOcrClient.buildParams(image(p, 1000, 1400), "QUJD"))
                .doesNotThrowAnyException();
    }
}
```

> **참고:** `buildParams`는 모델명·maxTokens를 인자로 받지 않도록 상수를 쓰는 대신,
> 테스트를 단순하게 유지하기 위해 정적 메서드로 두고 기본 모델/토큰을 사용한다.
> 인스턴스 경로(`ocr(...)`)는 `OcrProperties` 값을 사용한다.

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew test --tests '*ClaudeOcrClientTest*'`
Expected: FAIL — `cannot find symbol: class ClaudeOcrClient`

- [ ] **Step 3: Write minimal implementation**

`claude/ImageTooLargeException.java`:

```java
package com.dreamtrue.ocr.claude;

public class ImageTooLargeException extends RuntimeException {
    public ImageTooLargeException(String message) {
        super(message);
    }
}
```

`claude/ClaudeOcrClient.java`:

```java
package com.dreamtrue.ocr.claude;

import com.anthropic.client.AnthropicClient;
import com.anthropic.models.messages.Base64ImageSource;
import com.anthropic.models.messages.ContentBlockParam;
import com.anthropic.models.messages.ImageBlockParam;
import com.anthropic.models.messages.MessageCreateParams;
import com.anthropic.models.messages.StructuredMessageCreateParams;
import com.anthropic.models.messages.TextBlockParam;
import com.anthropic.models.messages.ThinkingConfigAdaptive;
import com.dreamtrue.ocr.config.OcrProperties;
import com.dreamtrue.ocr.domain.OcrResult;
import com.dreamtrue.ocr.domain.Outcome;
import com.dreamtrue.ocr.domain.SourceImage;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Base64;
import java.util.List;

@Slf4j
@Component
@RequiredArgsConstructor
public class ClaudeOcrClient {

    /** 고해상도 티어(Claude 4.7 이후) 장변 상한. */
    private static final int MAX_LONG_EDGE_PX = 2576;
    /** Claude API 직접 호출 시 이미지당 base64 상한. */
    private static final long MAX_BASE64_BYTES = 10L * 1024 * 1024;

    private static final int MAX_ATTEMPTS = 3;
    private static final long BASE_BACKOFF_MS = 1000L;

    private final AnthropicClient client;
    private final OcrProperties properties;

    public Outcome<OcrResult> ocr(SourceImage image) {
        try {
            validateSize(image);
        } catch (ImageTooLargeException e) {
            return Outcome.failed(e.getMessage());
        }

        String base64;
        try {
            base64 = encodeBase64(image.path());
        } catch (IOException e) {
            return Outcome.failed("파일 읽기 실패: " + e.getMessage());
        }

        StructuredMessageCreateParams<OcrResult> params =
                buildParams(image, base64, properties.claude().model(), properties.claude().maxTokens());

        RuntimeException last = null;
        for (int attempt = 1; attempt <= MAX_ATTEMPTS; attempt++) {
            try {
                OcrResult result = client.messages().create(params).content().stream()
                        .flatMap(cb -> cb.text().stream())
                        .map(t -> t.text())
                        .findFirst()
                        .orElseThrow(() -> new IllegalStateException("구조화 출력이 비어 있습니다"));
                return Outcome.ok(result);
            } catch (RuntimeException e) {
                last = e;
                log.warn("OCR 실패 ({}/{}) {}: {}", attempt, MAX_ATTEMPTS,
                        image.fileName(), e.getMessage());
                if (attempt < MAX_ATTEMPTS) {
                    sleepBackoff(attempt);
                }
            }
        }
        return Outcome.failed(last == null ? "알 수 없는 오류" : last.getMessage());
    }

    static void validateSize(SourceImage image) {
        if (image.longEdge() > MAX_LONG_EDGE_PX) {
            throw new ImageTooLargeException(
                    "이미지 장변이 상한을 초과했습니다 (%dpx, 상한 %dpx). 축소 후 다시 시도하세요: %s"
                            .formatted(image.longEdge(), MAX_LONG_EDGE_PX, image.fileName()));
        }
        long fileBytes;
        try {
            fileBytes = Files.size(image.path());
        } catch (IOException e) {
            throw new ImageTooLargeException("파일 크기를 확인할 수 없습니다: " + e.getMessage());
        }
        long base64Bytes = (fileBytes + 2) / 3 * 4;
        if (base64Bytes > MAX_BASE64_BYTES) {
            throw new ImageTooLargeException(
                    "base64 크기가 상한 10MB를 초과했습니다 (%.1fMB): %s"
                            .formatted(base64Bytes / 1024.0 / 1024.0, image.fileName()));
        }
    }

    static String encodeBase64(Path path) throws IOException {
        return Base64.getEncoder().encodeToString(Files.readAllBytes(path));
    }

    static StructuredMessageCreateParams<OcrResult> buildParams(SourceImage image, String base64) {
        return buildParams(image, base64, "claude-opus-5", 16000L);
    }

    /** 이미지를 텍스트보다 먼저 놓는다 (공식 권장 image-then-text). */
    static List<ContentBlockParam> buildContent(SourceImage image, String base64) {
        return List.of(
                ContentBlockParam.ofImage(ImageBlockParam.builder()
                        .source(Base64ImageSource.builder()
                                .mediaType(mediaType(image.mediaType()))
                                .data(base64)
                                .build())
                        .build()),
                ContentBlockParam.ofText(TextBlockParam.builder()
                        .text(OcrPrompt.USER)
                        .build()));
    }

    static StructuredMessageCreateParams<OcrResult> buildParams(
            SourceImage image, String base64, String model, long maxTokens) {
        List<ContentBlockParam> content = buildContent(image, base64);

        // effort는 지정하지 않는다. .outputConfig()는 Class 또는 OutputConfig 중
        // 하나만 받으며, effort 기본값이 high 라 생략해도 동일하다.
        // temperature/top_p/top_k 는 Opus 5 에서 제거되어 사용하지 않는다.
        return MessageCreateParams.builder()
                .model(model)
                .maxTokens(maxTokens)
                .system(OcrPrompt.SYSTEM)
                .thinking(ThinkingConfigAdaptive.builder().build())
                .outputConfig(OcrResult.class)
                .addUserMessageOfBlockParams(content)
                .build();
    }

    private static Base64ImageSource.MediaType mediaType(String mime) {
        return switch (mime) {
            case "image/jpeg" -> Base64ImageSource.MediaType.IMAGE_JPEG;
            case "image/png" -> Base64ImageSource.MediaType.IMAGE_PNG;
            case "image/gif" -> Base64ImageSource.MediaType.IMAGE_GIF;
            case "image/webp" -> Base64ImageSource.MediaType.IMAGE_WEBP;
            default -> throw new IllegalArgumentException("지원하지 않는 이미지 형식: " + mime);
        };
    }

    private void sleepBackoff(int attempt) {
        long delay = BASE_BACKOFF_MS * (1L << (attempt - 1))
                + (long) (Math.random() * 250);
        try {
            Thread.sleep(delay);
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
        }
    }
}
```

> **컴파일 조정 지침:** 구조화 응답에서 타입 객체를 꺼내는 정확한 표현은 SDK 버전에
> 따라 `cb.text()` 가 `StructuredContentBlock<OcrResult>` 를 주는 형태다. 위 코드가
> `cannot find symbol` 이나 타입 불일치를 내면, `client.messages().create(params)` 의
> 반환 타입을 IDE/컴파일러 오류 메시지로 확인해 `.content()` 순회 부분만 맞춘다.
> 나머지(파라미터 빌드, 검증, 재시도)는 이미 컴파일이 검증된 형태이므로 바꾸지 않는다.

`AnthropicClient` 빈을 등록한다 — `config/AnthropicConfig.java`:

```java
package com.dreamtrue.ocr.config;

import com.anthropic.client.AnthropicClient;
import com.anthropic.client.okhttp.AnthropicOkHttpClient;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class AnthropicConfig {

    @Bean
    public AnthropicClient anthropicClient() {
        // ANTHROPIC_API_KEY 환경변수를 읽는다.
        return AnthropicOkHttpClient.fromEnv();
    }
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `./gradlew test --tests '*ClaudeOcrClientTest*'`
Expected: PASS (6 tests)

- [ ] **Step 5: Commit**

```bash
git add -A
git commit -m "feat: ClaudeOcrClient 추가 (크기 검증, 구조화 출력, 지수 백오프)"
```

---

### Task 8: OcrResultStore — 결과 저장과 재사용

**Files:**
- Create: `src/main/java/com/dreamtrue/ocr/store/OcrResultStore.java`
- Test: `src/test/java/com/dreamtrue/ocr/store/OcrResultStoreTest.java`

**Interfaces:**
- Consumes: `OcrResult`
- Produces:
  - `OcrResultStore(Path outputDir)` 생성자
  - `write(String fileName, String model, OcrResult)` → `void`
  - `read(String fileName)` → `Optional<OcrResult>`

**설계 근거:** 쓰기는 항상, 읽기는 `--skip-ocr`일 때만. 기본 실행이 항상 새 API 호출이므로 sha256·promptVersion·model 비교 같은 무효화 판정이 전부 불필요하다. 낡은 결과를 쓰는 경우는 `--skip-ocr`을 직접 지정했을 때뿐이다.

- [ ] **Step 1: Write the failing test**

```java
package com.dreamtrue.ocr.store;

import com.dreamtrue.ocr.domain.OcrResult;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

class OcrResultStoreTest {

    private static final OcrResult RESULT = new OcrResult(
            "정리 작업 메모 (칠월 이십오일)",
            "정리 작업 메모\n\n칠월 이십오일 맑음",
            "손글씨 낱장 메모",
            "용지 변색, 판독 지장 없음",
            "없음",
            "확인되지 않음",
            null);

    @Test
    void 저장하고_다시_읽으면_같은_값이다(@TempDir Path dir) throws IOException {
        OcrResultStore store = new OcrResultStore(dir);

        store.write("img_03.jpg", "claude-opus-5", RESULT);
        Optional<OcrResult> read = store.read("img_03.jpg");

        assertThat(read).contains(RESULT);
    }

    @Test
    void output_raw_아래에_파일명_기반으로_저장된다(@TempDir Path dir) throws IOException {
        OcrResultStore store = new OcrResultStore(dir);

        store.write("img_03.jpg", "claude-opus-5", RESULT);

        Path expected = dir.resolve("raw").resolve("img_03.json");
        assertThat(expected).exists();
        assertThat(Files.readString(expected))
                .contains("claude-opus-5")
                .contains("정리 작업 메모");
    }

    @Test
    void 파일이_없으면_비어있음을_돌려준다(@TempDir Path dir) throws IOException {
        OcrResultStore store = new OcrResultStore(dir);

        assertThat(store.read("없는파일.jpg")).isEmpty();
    }

    @Test
    void 디렉토리가_없어도_저장하면_생성된다(@TempDir Path dir) throws IOException {
        Path nested = dir.resolve("깊은").resolve("경로");
        OcrResultStore store = new OcrResultStore(nested);

        store.write("a.jpg", "claude-opus-5", RESULT);

        assertThat(nested.resolve("raw").resolve("a.json")).exists();
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew test --tests '*OcrResultStoreTest*'`
Expected: FAIL — `cannot find symbol: class OcrResultStore`

- [ ] **Step 3: Write minimal implementation**

```java
package com.dreamtrue.ocr.store;

import com.dreamtrue.ocr.domain.OcrResult;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
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
        this.mapper = new ObjectMapper()
                .enable(SerializationFeature.INDENT_OUTPUT)
                .setSerializationInclusion(JsonInclude.Include.ALWAYS);
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
```

- [ ] **Step 4: Run test to verify it passes**

Run: `./gradlew test --tests '*OcrResultStoreTest*'`
Expected: PASS (4 tests)

- [ ] **Step 5: Commit**

```bash
git add -A
git commit -m "feat: OcrResultStore 추가 (쓰기는 항상, 읽기는 --skip-ocr 시에만)"
```

---

### Task 9: DriveImageUploader — 업로드·권한·썸네일 URL

**Files:**
- Create: `src/main/java/com/dreamtrue/ocr/drive/DriveImageUploader.java`
- Create: `src/main/java/com/dreamtrue/ocr/drive/SystemicFailureException.java`
- Create: `src/main/java/com/dreamtrue/ocr/config/GoogleConfig.java`
- Test: `src/test/java/com/dreamtrue/ocr/drive/DriveImageUploaderTest.java`

**Interfaces:**
- Consumes: `SourceImage`, `Outcome`, `OcrProperties`
- Produces:
  - `DriveImageUploader.thumbnailUrl(String fileId)` → `String` (정적)
  - `DriveImageUploader.upload(SourceImage)` → `Outcome<String>`
  - `SystemicFailureException(String message)` — 계통 실패는 값이 아니라 예외로 흐른다

**설계 근거:** `=IMAGE()`는 Sheets 렌더러가 **익명으로** 이미지를 가져가므로 링크 공유(`type=anyone`, `role=reader`)가 없으면 셀이 깨진다. URL은 `/file/d/.../view`가 아니라 `thumbnail?id=…&sz=w1000` 형식이어야 셀에서 렌더링된다. 403·404 같은 권한·설정 오류는 10장 전부 동일하게 실패하므로 개별 실패로 취급하지 않고 즉시 중단시킨다.

- [ ] **Step 1: Write the failing test**

```java
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
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew test --tests '*DriveImageUploaderTest*'`
Expected: FAIL — `cannot find symbol: class DriveImageUploader`

- [ ] **Step 3: Write minimal implementation**

`drive/SystemicFailureException.java`:

```java
package com.dreamtrue.ocr.drive;

/**
 * 개별 이미지가 아니라 환경 설정의 문제. 10장 전부 동일하게 실패하므로
 * 격리하지 않고 배치를 즉시 중단시킨다.
 */
public class SystemicFailureException extends RuntimeException {
    public SystemicFailureException(String message) {
        super(message);
    }
}
```

`drive/DriveImageUploader.java`:

```java
package com.dreamtrue.ocr.drive;

import com.dreamtrue.ocr.domain.Outcome;
import com.dreamtrue.ocr.domain.SourceImage;
import com.google.api.client.googleapis.json.GoogleJsonResponseException;
import lombok.extern.slf4j.Slf4j;

import java.io.IOException;
import java.nio.file.Path;

@Slf4j
public class DriveImageUploader {

    /** Drive 접근을 좁은 인터페이스로 가둔다. 테스트에서 대역으로 바꾼다. */
    public interface DriveGateway {
        String uploadAndShare(Path file, String mediaType, String folderId) throws IOException;

        boolean thumbnailReady(String url);
    }

    private static final int MAX_ATTEMPTS = 3;
    private static final long BASE_BACKOFF_MS = 1000L;
    private static final int THUMBNAIL_ATTEMPTS = 5;

    private final DriveGateway drive;
    private final String folderId;

    public DriveImageUploader(DriveGateway drive, String folderId) {
        this.drive = drive;
        this.folderId = folderId;
    }

    public static String thumbnailUrl(String fileId) {
        return "https://drive.google.com/thumbnail?id=" + fileId + "&sz=w1000";
    }

    public Outcome<String> upload(SourceImage image) {
        IOException last = null;
        for (int attempt = 1; attempt <= MAX_ATTEMPTS; attempt++) {
            try {
                String fileId = drive.uploadAndShare(image.path(), image.mediaType(), folderId);
                String url = thumbnailUrl(fileId);
                awaitThumbnail(url);
                return Outcome.ok(url);
            } catch (GoogleJsonResponseException e) {
                if (isSystemic(e.getStatusCode())) {
                    throw new SystemicFailureException(systemicMessage(e));
                }
                last = e;
                log.warn("업로드 실패 ({}/{}) {}: {}", attempt, MAX_ATTEMPTS,
                        image.fileName(), e.getStatusCode());
            } catch (IOException e) {
                last = e;
                log.warn("업로드 실패 ({}/{}) {}: {}", attempt, MAX_ATTEMPTS,
                        image.fileName(), e.getMessage());
            }
            if (attempt < MAX_ATTEMPTS) {
                sleepBackoff(attempt);
            }
        }
        String reason = (last instanceof GoogleJsonResponseException g)
                ? g.getStatusCode() + " " + g.getStatusMessage()
                : String.valueOf(last == null ? "알 수 없는 오류" : last.getMessage());
        return Outcome.failed(reason + ", " + MAX_ATTEMPTS + "회 재시도 후 포기");
    }

    /** 업로드 직후에는 썸네일이 아직 생성되지 않아 일시적으로 404가 난다. */
    private void awaitThumbnail(String url) {
        for (int i = 1; i <= THUMBNAIL_ATTEMPTS; i++) {
            if (drive.thumbnailReady(url)) {
                return;
            }
            sleepBackoff(i);
        }
        log.warn("썸네일이 준비되지 않았습니다. 셀에서 잠시 후 표시될 수 있습니다: {}", url);
    }

    private static boolean isSystemic(int status) {
        return status == 401 || status == 403 || status == 404;
    }

    private String systemicMessage(GoogleJsonResponseException e) {
        return """
                Drive 업로드 권한 오류 (%d %s)
                  폴더 ID : %s
                  확인 : 해당 폴더를 서비스 계정 이메일에 '편집자'로 공유했는지 확인하세요.
                         조직 정책이 링크 공유를 차단하는 경우 =IMAGE() 렌더링이 불가능합니다.
                  OCR 결과는 output/raw/ 에 보존되었습니다. 권한 수정 후 --skip-ocr 로 재실행하세요."""
                .formatted(e.getStatusCode(), e.getStatusMessage(), folderId);
    }

    private void sleepBackoff(int attempt) {
        long delay = BASE_BACKOFF_MS * (1L << (attempt - 1)) + (long) (Math.random() * 250);
        try {
            Thread.sleep(delay);
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
        }
    }
}
```

`config/GoogleConfig.java` — 실제 Drive/Sheets 클라이언트 빈:

```java
package com.dreamtrue.ocr.config;

import com.google.api.client.googleapis.javanet.GoogleNetHttpTransport;
import com.google.api.client.http.HttpRequestInitializer;
import com.google.api.client.json.gson.GsonFactory;
import com.google.api.services.drive.Drive;
import com.google.api.services.drive.DriveScopes;
import com.google.api.services.sheets.v4.Sheets;
import com.google.api.services.sheets.v4.SheetsScopes;
import com.google.auth.http.HttpCredentialsAdapter;
import com.google.auth.oauth2.GoogleCredentials;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.GeneralSecurityException;
import java.util.List;

@Configuration
public class GoogleConfig {

    private static final String APP_NAME = "archive-ocr";

    @Bean
    public GoogleCredentials googleCredentials(OcrProperties properties) throws IOException {
        Path keyPath = Path.of(properties.google().credentialsPath());
        if (!Files.exists(keyPath)) {
            throw new IOException("서비스 계정 키 파일을 찾을 수 없습니다: " + keyPath.toAbsolutePath());
        }
        try (InputStream in = Files.newInputStream(keyPath)) {
            return GoogleCredentials.fromStream(in)
                    .createScoped(List.of(DriveScopes.DRIVE, SheetsScopes.SPREADSHEETS));
        }
    }

    @Bean
    public Drive drive(GoogleCredentials credentials) throws IOException, GeneralSecurityException {
        return new Drive.Builder(
                GoogleNetHttpTransport.newTrustedTransport(),
                GsonFactory.getDefaultInstance(),
                initializer(credentials))
                .setApplicationName(APP_NAME)
                .build();
    }

    @Bean
    public Sheets sheets(GoogleCredentials credentials) throws IOException, GeneralSecurityException {
        return new Sheets.Builder(
                GoogleNetHttpTransport.newTrustedTransport(),
                GsonFactory.getDefaultInstance(),
                initializer(credentials))
                .setApplicationName(APP_NAME)
                .build();
    }

    private HttpRequestInitializer initializer(GoogleCredentials credentials) {
        return new HttpCredentialsAdapter(credentials);
    }
}
```

`DriveGateway` 실제 구현 — `drive/GoogleDriveGateway.java`:

```java
package com.dreamtrue.ocr.drive;

import com.dreamtrue.ocr.config.OcrProperties;
import com.google.api.client.http.FileContent;
import com.google.api.services.drive.Drive;
import com.google.api.services.drive.model.File;
import com.google.api.services.drive.model.Permission;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.net.HttpURLConnection;
import java.net.URI;
import java.nio.file.Path;
import java.util.List;

@Component
@RequiredArgsConstructor
public class GoogleDriveGateway implements DriveImageUploader.DriveGateway {

    private final Drive drive;

    @Override
    public String uploadAndShare(Path file, String mediaType, String folderId) throws IOException {
        String name = file.getFileName().toString();

        // 같은 이름이 이미 있으면 재사용한다 (멱등).
        List<File> existing = drive.files().list()
                .setQ("name = '" + name + "' and '" + folderId + "' in parents and trashed = false")
                .setFields("files(id)")
                .execute()
                .getFiles();
        if (existing != null && !existing.isEmpty()) {
            return existing.getFirst().getId();
        }

        File metadata = new File().setName(name).setParents(List.of(folderId));
        File created = drive.files()
                .create(metadata, new FileContent(mediaType, file.toFile()))
                .setFields("id")
                .execute();

        // =IMAGE() 는 익명으로 이미지를 가져간다. 링크 공유가 없으면 셀이 깨진다.
        drive.permissions()
                .create(created.getId(), new Permission().setType("anyone").setRole("reader"))
                .execute();

        return created.getId();
    }

    @Override
    public boolean thumbnailReady(String url) {
        try {
            HttpURLConnection conn = (HttpURLConnection) URI.create(url).toURL().openConnection();
            conn.setRequestMethod("HEAD");
            conn.setConnectTimeout(5000);
            conn.setReadTimeout(5000);
            int code = conn.getResponseCode();
            conn.disconnect();
            return code >= 200 && code < 400;
        } catch (IOException e) {
            return false;
        }
    }
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `./gradlew test --tests '*DriveImageUploaderTest*'`
Expected: PASS (5 tests)

- [ ] **Step 5: Commit**

```bash
git add -A
git commit -m "feat: DriveImageUploader 추가 (링크 공유, 썸네일 대기, 계통 실패 즉시 중단)"
```

---

### Task 10: SheetLayout — 셀 좌표와 수식

**Files:**
- Create: `src/main/java/com/dreamtrue/ocr/sheets/SheetLayout.java`
- Test: `src/test/java/com/dreamtrue/ocr/sheets/SheetLayoutTest.java`

**Interfaces:**
- Consumes: `ArchiveRecord`, `SourceImage`, `RecordAssembler.Cells`
- Produces (모두 정적):
  - `SheetLayout.INDEX_SHEET` = `"목록"`
  - `SheetLayout.indexHeader()` → `List<Object>`
  - `SheetLayout.indexRow(ArchiveRecord, int gid, String title)` → `List<Object>`
  - `SheetLayout.detailRows(ArchiveRecord, RecordAssembler.Cells, int indexGid, boolean landscape)` → `List<List<Object>>`
  - `SheetLayout.imageFormula(String url, boolean landscape)` → `String`
  - `SheetLayout.backLink(int indexGid)` → `String`

**설계 근거:** `=IMAGE(url, 4, 높이, 너비)` — mode 4(사용자 지정 크기)를 쓰면 세로 문서와 가로 문서(img_05는 1200×850)가 섞여도 행 높이가 일정하게 유지된다. 사진 칸에 실패 문구 같은 일반 문자열이 오면 수식이 아니라 그대로 넣는다.

- [ ] **Step 1: Write the failing test**

```java
package com.dreamtrue.ocr.sheets;

import com.dreamtrue.ocr.domain.ArchiveRecord;
import com.dreamtrue.ocr.domain.OcrResult;
import com.dreamtrue.ocr.domain.Outcome;
import com.dreamtrue.ocr.domain.RecordAssembler;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class SheetLayoutTest {

    private ArchiveRecord record() {
        return new ArchiveRecord(1, 1, 3, Path.of("input/img_03.jpg"),
                Outcome.ok(new OcrResult("제목", "본문", "종류", "상태", "없음", "확인되지 않음", null)),
                Outcome.ok("https://drive.google.com/thumbnail?id=X&sz=w1000"));
    }

    @Test
    void 목록_헤더는_네_열이다() {
        assertThat(SheetLayout.indexHeader())
                .containsExactly("폴더번호", "파일번호", "세부번호", "제목");
    }

    @Test
    void 목록_행의_제목은_상세시트로_가는_하이퍼링크다() {
        List<Object> row = SheetLayout.indexRow(record(), 12345, "보관증");

        assertThat(row.get(0)).isEqualTo(1);
        assertThat(row.get(1)).isEqualTo(1);
        assertThat(row.get(2)).isEqualTo(3);
        assertThat(row.get(3)).isEqualTo("=HYPERLINK(\"#gid=12345\",\"보관증\")");
    }

    @Test
    void 제목에_큰따옴표가_있어도_수식이_깨지지_않는다() {
        List<Object> row = SheetLayout.indexRow(record(), 1, "제목 \"인용\" 포함");

        assertThat((String) row.get(3)).isEqualTo("=HYPERLINK(\"#gid=1\",\"제목 \"\"인용\"\" 포함\")");
    }

    @Test
    void 세로문서와_가로문서의_이미지_크기_인자가_다르다() {
        assertThat(SheetLayout.imageFormula("https://x/y", false))
                .isEqualTo("=IMAGE(\"https://x/y\",4,600,430)");
        assertThat(SheetLayout.imageFormula("https://x/y", true))
                .isEqualTo("=IMAGE(\"https://x/y\",4,430,600)");
    }

    @Test
    void 상세시트는_예시_양식과_같은_배치를_갖는다() {
        var cells = RecordAssembler.assemble(record());
        List<List<Object>> rows = SheetLayout.detailRows(record(), cells, 999, false);

        assertThat(rows.get(0)).containsExactly("=HYPERLINK(\"#gid=999\",\"◀ 목록으로\")", "");
        assertThat(rows.get(1)).containsExactly("", "");
        assertThat(rows.get(2)).containsExactly("파일번호", 1);
        assertThat(rows.get(3)).containsExactly("세부번호", 3);
        assertThat(rows.get(4).get(0)).isEqualTo("제목");
        assertThat(rows.get(5).get(0)).isEqualTo("분석(내용)");
        assertThat(rows.get(6).get(0)).isEqualTo("사진");
        assertThat((String) rows.get(6).get(1)).startsWith("=IMAGE(");
    }

    @Test
    void 사진_업로드가_실패하면_수식이_아니라_문자열이_들어간다() {
        ArchiveRecord failed = new ArchiveRecord(1, 1, 3, Path.of("input/img_03.jpg"),
                record().ocr(), Outcome.failed("503 backendError"));
        var cells = RecordAssembler.assemble(failed);

        List<List<Object>> rows = SheetLayout.detailRows(failed, cells, 999, false);

        assertThat((String) rows.get(6).get(1))
                .startsWith("[사진 업로드 실패]")
                .doesNotStartWith("=IMAGE(");
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew test --tests '*SheetLayoutTest*'`
Expected: FAIL — `cannot find symbol: class SheetLayout`

- [ ] **Step 3: Write minimal implementation**

```java
package com.dreamtrue.ocr.sheets;

import com.dreamtrue.ocr.domain.ArchiveRecord;
import com.dreamtrue.ocr.domain.RecordAssembler;

import java.util.List;

public final class SheetLayout {

    public static final String INDEX_SHEET = "목록";

    /** 세로 문서 기준 셀 안 이미지 크기(px). 가로 문서는 뒤집어 쓴다. */
    private static final int PORTRAIT_HEIGHT = 600;
    private static final int PORTRAIT_WIDTH = 430;

    private SheetLayout() {}

    public static List<Object> indexHeader() {
        return List.of("폴더번호", "파일번호", "세부번호", "제목");
    }

    public static List<Object> indexRow(ArchiveRecord r, int detailGid, String title) {
        return List.of(
                r.folderNumber(),
                r.fileNumber(),
                r.detailNumber(),
                "=HYPERLINK(\"#gid=" + detailGid + "\"," + quote(title) + ")");
    }

    public static String backLink(int indexGid) {
        return "=HYPERLINK(\"#gid=" + indexGid + "\",\"◀ 목록으로\")";
    }

    public static String imageFormula(String url, boolean landscape) {
        int height = landscape ? PORTRAIT_WIDTH : PORTRAIT_HEIGHT;
        int width = landscape ? PORTRAIT_HEIGHT : PORTRAIT_WIDTH;
        return "=IMAGE(" + quote(url) + ",4," + height + "," + width + ")";
    }

    public static List<List<Object>> detailRows(
            ArchiveRecord r, RecordAssembler.Cells cells, int indexGid, boolean landscape) {

        Object photo = r.photoUrl().isOk()
                ? imageFormula(cells.photo(), landscape)
                : cells.photo();

        return List.of(
                List.of(backLink(indexGid), ""),
                List.of("", ""),
                List.of("파일번호", r.fileNumber()),
                List.of("세부번호", r.detailNumber()),
                List.of("제목", cells.title()),
                List.of("분석(내용)", cells.analysis()),
                List.of("사진", photo));
    }

    /** 시트 수식 안의 문자열 리터럴. 큰따옴표는 두 번 써서 이스케이프한다. */
    private static String quote(String value) {
        return "\"" + value.replace("\"", "\"\"") + "\"";
    }
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `./gradlew test --tests '*SheetLayoutTest*'`
Expected: PASS (6 tests)

- [ ] **Step 5: Commit**

```bash
git add -A
git commit -m "feat: SheetLayout 추가 (IMAGE mode 4, HYPERLINK 이스케이프)"
```

---

### Task 11: SheetsWriter — 2단계 시트 생성과 기록

**Files:**
- Create: `src/main/java/com/dreamtrue/ocr/sheets/SheetsWriter.java`
- Create: `src/main/java/com/dreamtrue/ocr/sheets/SheetsGateway.java`
- Create: `src/main/java/com/dreamtrue/ocr/sheets/GoogleSheetsGateway.java`
- Test: `src/test/java/com/dreamtrue/ocr/sheets/SheetsWriterTest.java`

**Interfaces:**
- Consumes: `ArchiveRecord`, `RecordAssembler`, `SheetLayout`, `SourceImage`
- Produces:
  - `SheetsGateway` 인터페이스 — `deleteAllSheetsExceptOne()`, `createSheets(List<String> titles)` → `Map<String,Integer>` (제목→gid), `writeValues(String sheetTitle, List<List<Object>> rows)`, `applyFormatting(Map<String,Integer> gids)`, `spreadsheetUrl()`
  - `SheetsWriter.write(List<ArchiveRecord>, Map<String,Boolean> landscapeByFile)` → `String` (스프레드시트 URL)

**설계 근거:** 목록 시트의 하이퍼링크는 `=HYPERLINK("#gid=<시트ID>")` 형태인데 gid는 시트를 생성해야 알 수 있다. 따라서 ① 시트 11개를 먼저 만들어 gid를 수집 → ② 그 gid로 수식을 조립해 값 기록, 두 단계로 나뉜다. 한 번의 batchUpdate로는 불가능하다. 값은 `USER_ENTERED`로 넣어야 수식으로 해석된다. 모든 이미지 처리가 끝난 뒤 1회 기록하므로 반쯤 쓰인 시트가 생기지 않는다.

- [ ] **Step 1: Write the failing test**

```java
package com.dreamtrue.ocr.sheets;

import com.dreamtrue.ocr.domain.ArchiveRecord;
import com.dreamtrue.ocr.domain.OcrResult;
import com.dreamtrue.ocr.domain.Outcome;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class SheetsWriterTest {

    static class FakeSheets implements SheetsGateway {
        final List<String> calls = new ArrayList<>();
        final Map<String, List<List<Object>>> written = new LinkedHashMap<>();

        @Override
        public void deleteAllSheetsExceptOne() {
            calls.add("clear");
        }

        @Override
        public Map<String, Integer> createSheets(List<String> titles) {
            calls.add("create:" + titles);
            Map<String, Integer> gids = new LinkedHashMap<>();
            int gid = 100;
            for (String t : titles) {
                gids.put(t, gid++);
            }
            return gids;
        }

        @Override
        public void writeValues(String sheetTitle, List<List<Object>> rows) {
            calls.add("write:" + sheetTitle);
            written.put(sheetTitle, rows);
        }

        @Override
        public void applyFormatting(Map<String, Integer> gids) {
            calls.add("format");
        }

        @Override
        public String spreadsheetUrl() {
            return "https://docs.google.com/spreadsheets/d/TEST";
        }
    }

    private ArchiveRecord record(int detail) {
        return new ArchiveRecord(1, 1, detail, Path.of("input/img_0" + detail + ".jpg"),
                Outcome.ok(new OcrResult("제목" + detail, "본문", "종류", "상태",
                        "없음", "확인되지 않음", null)),
                Outcome.ok("https://drive.google.com/thumbnail?id=X" + detail + "&sz=w1000"));
    }

    @Test
    void 시트를_먼저_만들고_그다음에_값을_쓴다() {
        FakeSheets fake = new FakeSheets();
        new SheetsWriter(fake).write(List.of(record(1), record(2)), Map.of());

        int createIdx = indexOfPrefix(fake.calls, "create:");
        int firstWriteIdx = indexOfPrefix(fake.calls, "write:");
        assertThat(createIdx).isLessThan(firstWriteIdx);
        assertThat(fake.calls.getFirst()).isEqualTo("clear");
    }

    @Test
    void 목록과_상세시트가_모두_생성된다() {
        FakeSheets fake = new FakeSheets();
        new SheetsWriter(fake).write(List.of(record(1), record(2)), Map.of());

        assertThat(fake.written.keySet()).containsExactly("목록", "1-1", "1-2");
    }

    @Test
    void 목록의_제목은_해당_상세시트_gid를_가리킨다() {
        FakeSheets fake = new FakeSheets();
        new SheetsWriter(fake).write(List.of(record(1), record(2)), Map.of());

        List<List<Object>> index = fake.written.get("목록");
        assertThat(index.get(0)).containsExactly("폴더번호", "파일번호", "세부번호", "제목");
        // 목록=100, 1-1=101, 1-2=102
        assertThat((String) index.get(1).get(3)).contains("#gid=101").contains("제목1");
        assertThat((String) index.get(2).get(3)).contains("#gid=102").contains("제목2");
    }

    @Test
    void 상세시트의_역링크는_목록_gid를_가리킨다() {
        FakeSheets fake = new FakeSheets();
        new SheetsWriter(fake).write(List.of(record(1)), Map.of());

        List<List<Object>> detail = fake.written.get("1-1");
        assertThat((String) detail.get(0).get(0)).contains("#gid=100").contains("◀ 목록으로");
    }

    @Test
    void 가로문서는_이미지_크기_인자가_뒤집힌다() {
        FakeSheets fake = new FakeSheets();
        new SheetsWriter(fake).write(List.of(record(5)), Map.of("img_05.jpg", true));

        String photo = (String) fake.written.get("1-5").get(6).get(1);
        assertThat(photo).isEqualTo("=IMAGE(\"https://drive.google.com/thumbnail?id=X5&sz=w1000\",4,430,600)");
    }

    @Test
    void 서식은_값을_쓴_뒤에_적용된다() {
        FakeSheets fake = new FakeSheets();
        new SheetsWriter(fake).write(List.of(record(1)), Map.of());

        assertThat(fake.calls.getLast()).isEqualTo("format");
    }

    private int indexOfPrefix(List<String> calls, String prefix) {
        for (int i = 0; i < calls.size(); i++) {
            if (calls.get(i).startsWith(prefix)) return i;
        }
        return -1;
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew test --tests '*SheetsWriterTest*'`
Expected: FAIL — `cannot find symbol: interface SheetsGateway`

- [ ] **Step 3: Write minimal implementation**

`sheets/SheetsGateway.java`:

```java
package com.dreamtrue.ocr.sheets;

import java.util.List;
import java.util.Map;

/** Sheets API 접근을 좁은 인터페이스로 가둔다. 테스트에서 대역으로 바꾼다. */
public interface SheetsGateway {

    /** 재실행 시 멱등하게 만들기 위해 기존 시트를 정리한다. */
    void deleteAllSheetsExceptOne();

    /** 시트를 만들고 제목→gid 매핑을 돌려준다. gid는 만들어야 알 수 있다. */
    Map<String, Integer> createSheets(List<String> titles);

    /** USER_ENTERED 로 기록한다. 수식으로 해석되어야 한다. */
    void writeValues(String sheetTitle, List<List<Object>> rows);

    void applyFormatting(Map<String, Integer> gids);

    String spreadsheetUrl();
}
```

`sheets/SheetsWriter.java`:

```java
package com.dreamtrue.ocr.sheets;

import com.dreamtrue.ocr.domain.ArchiveRecord;
import com.dreamtrue.ocr.domain.RecordAssembler;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

@Slf4j
@RequiredArgsConstructor
public class SheetsWriter {

    private final SheetsGateway gateway;

    /**
     * @param landscapeByFile 파일명 → 가로 문서 여부
     * @return 스프레드시트 URL
     */
    public String write(List<ArchiveRecord> records, Map<String, Boolean> landscapeByFile) {
        gateway.deleteAllSheetsExceptOne();

        // 1단계: 시트를 먼저 만들어 gid 를 수집한다.
        List<String> titles = new ArrayList<>();
        titles.add(SheetLayout.INDEX_SHEET);
        records.forEach(r -> titles.add(r.sheetName()));
        Map<String, Integer> gids = gateway.createSheets(titles);
        int indexGid = gids.get(SheetLayout.INDEX_SHEET);

        // 2단계: 수집한 gid 로 수식을 조립해 값을 기록한다.
        List<List<Object>> indexRows = new ArrayList<>();
        indexRows.add(SheetLayout.indexHeader());

        List<Runnable> detailWrites = new ArrayList<>();
        for (ArchiveRecord r : records) {
            RecordAssembler.Cells cells = RecordAssembler.assemble(r);
            int detailGid = gids.get(r.sheetName());
            indexRows.add(SheetLayout.indexRow(r, detailGid, cells.title()));

            boolean landscape = landscapeByFile.getOrDefault(r.fileName(), false);
            List<List<Object>> rows = SheetLayout.detailRows(r, cells, indexGid, landscape);
            detailWrites.add(() -> gateway.writeValues(r.sheetName(), rows));
        }

        gateway.writeValues(SheetLayout.INDEX_SHEET, indexRows);
        detailWrites.forEach(Runnable::run);

        gateway.applyFormatting(gids);

        return gateway.spreadsheetUrl();
    }
}
```

`sheets/GoogleSheetsGateway.java`:

```java
package com.dreamtrue.ocr.sheets;

import com.dreamtrue.ocr.config.OcrProperties;
import com.google.api.services.sheets.v4.Sheets;
import com.google.api.services.sheets.v4.model.*;
import lombok.RequiredArgsConstructor;
import lombok.SneakyThrows;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Component
@RequiredArgsConstructor
public class GoogleSheetsGateway implements SheetsGateway {

    private static final int ANALYSIS_ROW = 5; // 0-based, "분석(내용)"
    private static final int PHOTO_ROW = 6;    // 0-based, "사진"

    private final Sheets sheets;
    private final OcrProperties properties;

    private String spreadsheetId() {
        String id = properties.google().spreadsheetId();
        if (id == null || id.isBlank()) {
            throw new IllegalStateException(
                    "ocr.google.spreadsheet-id 가 비어 있습니다. application.yml 을 확인하세요.");
        }
        return id;
    }

    @Override
    @SneakyThrows
    public void deleteAllSheetsExceptOne() {
        Spreadsheet ss = sheets.spreadsheets().get(spreadsheetId()).execute();
        List<Sheet> existing = ss.getSheets();
        if (existing.size() <= 1) {
            return;
        }
        List<Request> requests = new ArrayList<>();
        for (int i = 1; i < existing.size(); i++) {
            requests.add(new Request().setDeleteSheet(
                    new DeleteSheetRequest().setSheetId(existing.get(i).getProperties().getSheetId())));
        }
        batch(requests);
    }

    @Override
    @SneakyThrows
    public Map<String, Integer> createSheets(List<String> titles) {
        List<Request> requests = titles.stream()
                .map(t -> new Request().setAddSheet(new AddSheetRequest()
                        .setProperties(new SheetProperties().setTitle(t))))
                .toList();

        BatchUpdateSpreadsheetResponse response = batch(requests);

        Map<String, Integer> gids = new LinkedHashMap<>();
        List<Response> replies = response.getReplies();
        for (int i = 0; i < titles.size(); i++) {
            gids.put(titles.get(i), replies.get(i).getAddSheet().getProperties().getSheetId());
        }

        // 기본 시트(Sheet1 등)를 정리한다.
        Spreadsheet ss = sheets.spreadsheets().get(spreadsheetId()).execute();
        List<Request> cleanup = ss.getSheets().stream()
                .map(Sheet::getProperties)
                .filter(p -> !gids.containsKey(p.getTitle()))
                .map(p -> new Request().setDeleteSheet(
                        new DeleteSheetRequest().setSheetId(p.getSheetId())))
                .toList();
        if (!cleanup.isEmpty()) {
            batch(cleanup);
        }
        return gids;
    }

    @Override
    @SneakyThrows
    public void writeValues(String sheetTitle, List<List<Object>> rows) {
        sheets.spreadsheets().values()
                .update(spreadsheetId(), "'" + sheetTitle + "'!A1",
                        new ValueRange().setValues(rows))
                .setValueInputOption("USER_ENTERED")   // 수식으로 해석되어야 한다
                .execute();
    }

    @Override
    @SneakyThrows
    public void applyFormatting(Map<String, Integer> gids) {
        List<Request> requests = new ArrayList<>();
        for (Map.Entry<String, Integer> e : gids.entrySet()) {
            int gid = e.getValue();
            boolean isIndex = SheetLayout.INDEX_SHEET.equals(e.getKey());

            requests.add(columnWidth(gid, 0, isIndex ? 90 : 110));
            requests.add(columnWidth(gid, 1, isIndex ? 90 : 620));
            if (isIndex) {
                requests.add(columnWidth(gid, 2, 90));
                requests.add(columnWidth(gid, 3, 700));
                continue;
            }
            requests.add(wrapText(gid, ANALYSIS_ROW));
            requests.add(rowHeight(gid, PHOTO_ROW, 620));
        }
        batch(requests);
    }

    @Override
    public String spreadsheetUrl() {
        return "https://docs.google.com/spreadsheets/d/" + spreadsheetId();
    }

    private Request columnWidth(int gid, int columnIndex, int pixels) {
        return new Request().setUpdateDimensionProperties(new UpdateDimensionPropertiesRequest()
                .setRange(new DimensionRange().setSheetId(gid).setDimension("COLUMNS")
                        .setStartIndex(columnIndex).setEndIndex(columnIndex + 1))
                .setProperties(new DimensionProperties().setPixelSize(pixels))
                .setFields("pixelSize"));
    }

    private Request rowHeight(int gid, int rowIndex, int pixels) {
        return new Request().setUpdateDimensionProperties(new UpdateDimensionPropertiesRequest()
                .setRange(new DimensionRange().setSheetId(gid).setDimension("ROWS")
                        .setStartIndex(rowIndex).setEndIndex(rowIndex + 1))
                .setProperties(new DimensionProperties().setPixelSize(pixels))
                .setFields("pixelSize"));
    }

    private Request wrapText(int gid, int rowIndex) {
        return new Request().setRepeatCell(new RepeatCellRequest()
                .setRange(new GridRange().setSheetId(gid)
                        .setStartRowIndex(rowIndex).setEndRowIndex(rowIndex + 1)
                        .setStartColumnIndex(1).setEndColumnIndex(2))
                .setCell(new CellData().setUserEnteredFormat(new CellFormat()
                        .setWrapStrategy("WRAP")
                        .setVerticalAlignment("TOP")))
                .setFields("userEnteredFormat(wrapStrategy,verticalAlignment)"));
    }

    private BatchUpdateSpreadsheetResponse batch(List<Request> requests) throws java.io.IOException {
        return sheets.spreadsheets()
                .batchUpdate(spreadsheetId(),
                        new BatchUpdateSpreadsheetRequest().setRequests(requests))
                .execute();
    }
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `./gradlew test --tests '*SheetsWriterTest*'`
Expected: PASS (6 tests)

- [ ] **Step 5: Commit**

```bash
git add -A
git commit -m "feat: SheetsWriter 추가 (gid 수집 후 수식 조립 2단계)"
```

---

### Task 12: BatchReport — 요약과 종료 코드

**Files:**
- Create: `src/main/java/com/dreamtrue/ocr/report/BatchReport.java`
- Test: `src/test/java/com/dreamtrue/ocr/report/BatchReportTest.java`

**Interfaces:**
- Consumes: `ArchiveRecord`, `Outcome`
- Produces:
  - `BatchReport.of(List<ArchiveRecord>, String spreadsheetUrl)` → `BatchReport`
  - `BatchReport.systemicFailure(String message)` → `BatchReport`
  - `render()` → `String`, `exitCode()` → `int`

**설계 근거:** 종료 코드 `0` 전량 성공 / `1` 부분 실패 / `2` 계통 실패. CI나 스케줄러에서 "일부 실패"와 "설정 오류"를 구분해 대응할 수 있다.

- [ ] **Step 1: Write the failing test**

```java
package com.dreamtrue.ocr.report;

import com.dreamtrue.ocr.domain.ArchiveRecord;
import com.dreamtrue.ocr.domain.OcrResult;
import com.dreamtrue.ocr.domain.Outcome;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class BatchReportTest {

    private static final OcrResult OCR =
            new OcrResult("t", "본문", "종류", "상태", "없음", "확인되지 않음", null);

    private ArchiveRecord ok(int n) {
        return new ArchiveRecord(1, 1, n, Path.of("input/img_0" + n + ".jpg"),
                Outcome.ok(OCR), Outcome.ok("https://x/" + n));
    }

    private ArchiveRecord photoFailed(int n, String reason) {
        return new ArchiveRecord(1, 1, n, Path.of("input/img_0" + n + ".jpg"),
                Outcome.ok(OCR), Outcome.failed(reason));
    }

    @Test
    void 전량_성공이면_종료코드는_0이다() {
        BatchReport r = BatchReport.of(List.of(ok(1), ok(2)), "https://sheet");

        assertThat(r.exitCode()).isZero();
        assertThat(r.render()).contains("2건 중 2건 성공");
    }

    @Test
    void 부분_실패면_종료코드는_1이고_실패건이_나열된다() {
        BatchReport r = BatchReport.of(
                List.of(ok(1), photoFailed(3, "503 backendError")), "https://sheet");

        assertThat(r.exitCode()).isEqualTo(1);
        assertThat(r.render())
                .contains("2건 중 1건 성공")
                .contains("실패 1건")
                .contains("img_03.jpg")
                .contains("세부번호 3")
                .contains("503 backendError");
    }

    @Test
    void 계통_실패면_종료코드는_2이다() {
        BatchReport r = BatchReport.systemicFailure("Drive 업로드 권한 오류 (403)");

        assertThat(r.exitCode()).isEqualTo(2);
        assertThat(r.render()).contains("[중단]").contains("403");
    }

    @Test
    void 실패가_있으면_재시도_명령을_안내한다() {
        BatchReport r = BatchReport.of(
                List.of(ok(1), photoFailed(3, "503 backendError")), "https://sheet");

        assertThat(r.render()).contains("--retry-failed");
    }

    @Test
    void 전량_성공이면_재시도_안내는_없다() {
        BatchReport r = BatchReport.of(List.of(ok(1)), "https://sheet");

        assertThat(r.render()).doesNotContain("--retry-failed");
    }

    @Test
    void 성공하면_시트_URL이_출력된다() {
        BatchReport r = BatchReport.of(List.of(ok(1)), "https://docs.google.com/spreadsheets/d/X");

        assertThat(r.render()).contains("https://docs.google.com/spreadsheets/d/X");
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew test --tests '*BatchReportTest*'`
Expected: FAIL — `cannot find symbol: class BatchReport`

- [ ] **Step 3: Write minimal implementation**

```java
package com.dreamtrue.ocr.report;

import com.dreamtrue.ocr.domain.ArchiveRecord;
import com.dreamtrue.ocr.domain.Outcome;

import java.util.ArrayList;
import java.util.List;

public final class BatchReport {

    private record Failure(String fileName, int detailNumber, String what, String reason) {}

    private final List<ArchiveRecord> records;
    private final String spreadsheetUrl;
    private final String systemicMessage;

    private BatchReport(List<ArchiveRecord> records, String spreadsheetUrl, String systemicMessage) {
        this.records = records;
        this.spreadsheetUrl = spreadsheetUrl;
        this.systemicMessage = systemicMessage;
    }

    public static BatchReport of(List<ArchiveRecord> records, String spreadsheetUrl) {
        return new BatchReport(records, spreadsheetUrl, null);
    }

    public static BatchReport systemicFailure(String message) {
        return new BatchReport(List.of(), null, message);
    }

    public int exitCode() {
        if (systemicMessage != null) {
            return 2;
        }
        return failures().isEmpty() ? 0 : 1;
    }

    public String render() {
        if (systemicMessage != null) {
            return "[중단] " + systemicMessage;
        }

        List<Failure> failures = failures();
        int total = records.size();
        int success = total - (int) records.stream()
                .filter(r -> !r.ocr().isOk() || !r.photoUrl().isOk())
                .count();

        StringBuilder sb = new StringBuilder();
        sb.append("처리 완료: %d건 중 %d건 성공%n".formatted(total, success));

        if (!failures.isEmpty()) {
            sb.append("%n  실패 %d건%n".formatted(failures.size()));
            for (Failure f : failures) {
                sb.append("    %-14s 세부번호 %-3d %s (%s)%n"
                        .formatted(f.fileName(), f.detailNumber(), f.what(), f.reason()));
            }
            sb.append("%n  실패한 항목만 다시 시도:%n");
            sb.append("    ./gradlew bootRun --args='--retry-failed'%n");
        }
        sb.append("%n  시트: %s%n".formatted(spreadsheetUrl));
        return sb.toString();
    }

    private List<Failure> failures() {
        List<Failure> out = new ArrayList<>();
        for (ArchiveRecord r : records) {
            if (r.ocr() instanceof Outcome.Failed<?> f) {
                out.add(new Failure(r.fileName(), r.detailNumber(), "OCR 실패", f.reason()));
            }
            if (r.photoUrl() instanceof Outcome.Failed<?> f) {
                out.add(new Failure(r.fileName(), r.detailNumber(), "사진 업로드 실패", f.reason()));
            }
        }
        return out;
    }
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `./gradlew test --tests '*BatchReportTest*'`
Expected: PASS (6 tests)

- [ ] **Step 5: Commit**

```bash
git add -A
git commit -m "feat: BatchReport 추가 (종료 코드 0/1/2)"
```

---

### Task 13: OcrBatchRunner — 전체 배선과 CLI 플래그

**Files:**
- Create: `src/main/java/com/dreamtrue/ocr/runner/OcrBatchRunner.java`
- Test: `src/test/java/com/dreamtrue/ocr/runner/OcrBatchRunnerTest.java`

**Interfaces:**
- Consumes: `ImageScanner`, `ClaudeOcrClient`, `OcrResultStore`, `DriveImageUploader`, `SheetsWriter`, `BatchReport`, `OcrProperties`
- Produces: `OcrBatchRunner implements ApplicationRunner, ExitCodeGenerator`

**설계 근거:** 순차 for 루프. 이미지 1장 처리가 자기완결적이라 공유 가변 상태가 없다. `SheetsWriter`는 루프 밖에 두어 전부 모인 뒤 1회 기록한다 — 루프 안에 넣으면 API 호출이 10배가 되고 중간에 죽으면 반쯤 쓰인 시트가 남는다.

**OCR 모드 세 가지:**

| 플래그 | 저장된 결과가 있으면 | 없으면 |
|---|---|---|
| (기본) | 무시하고 API 호출 | API 호출 |
| `--retry-failed` | 재사용 | API 호출 ← 실패했던 장만 |
| `--skip-ocr` | 재사용 | OCR 실패로 처리 |

`--retry-failed`는 별도의 실패 상태 저장소를 두지 않는다. OCR 실패는 `output/raw/*.json`의
부재가 표식이고, 업로드 실패는 Drive 폴더 내 파일의 부재가 표식이다. 업로더가 이미 같은
이름을 재사용하므로(Task 9) 성공한 장은 다시 올라가지 않는다.

- [ ] **Step 1: Write the failing test**

```java
package com.dreamtrue.ocr.runner;

import com.dreamtrue.ocr.domain.ArchiveRecord;
import com.dreamtrue.ocr.domain.OcrResult;
import com.dreamtrue.ocr.domain.Outcome;
import com.dreamtrue.ocr.domain.SourceImage;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

class OcrBatchRunnerTest {

    private static final OcrResult OCR =
            new OcrResult("제목", "본문", "종류", "상태", "없음", "확인되지 않음", null);

    private SourceImage image(int n) {
        return new SourceImage(Path.of("input/img_0" + n + ".jpg"), "image/jpeg", 1000, 1400, n);
    }

    @Test
    void 이미지_순서대로_레코드가_만들어진다() {
        List<ArchiveRecord> records = OcrBatchRunner.buildRecords(
                List.of(image(1), image(2), image(3)),
                img -> Outcome.ok(OCR),
                img -> Outcome.ok("https://x/" + img.detailNumber()),
                1, 1);

        assertThat(records).extracting(ArchiveRecord::detailNumber).containsExactly(1, 2, 3);
        assertThat(records).extracting(ArchiveRecord::sheetName)
                .containsExactly("1-1", "1-2", "1-3");
    }

    @Test
    void 한_장이_실패해도_나머지는_계속_처리된다() {
        List<ArchiveRecord> records = OcrBatchRunner.buildRecords(
                List.of(image(1), image(2), image(3)),
                img -> img.detailNumber() == 2
                        ? Outcome.failed("429 rate limit")
                        : Outcome.ok(OCR),
                img -> Outcome.ok("https://x/" + img.detailNumber()),
                1, 1);

        assertThat(records).hasSize(3);
        assertThat(records.get(0).ocr().isOk()).isTrue();
        assertThat(records.get(1).ocr().isOk()).isFalse();
        assertThat(records.get(2).ocr().isOk()).isTrue();
    }

    @Test
    void 실패해도_세부번호가_밀리지_않는다() {
        List<ArchiveRecord> records = OcrBatchRunner.buildRecords(
                List.of(image(1), image(2), image(3)),
                img -> img.detailNumber() == 1 ? Outcome.failed("실패") : Outcome.ok(OCR),
                img -> Outcome.ok("https://x"),
                1, 1);

        assertThat(records).extracting(ArchiveRecord::detailNumber).containsExactly(1, 2, 3);
    }

    @Test
    void retryFailed는_저장된_장은_건너뛰고_없는_장만_호출한다() {
        List<String> ocrCalls = new ArrayList<>();
        Set<String> stored = Set.of("img_01.jpg", "img_03.jpg");   // 2번만 지난번 실패

        List<ArchiveRecord> records = OcrBatchRunner.buildRecords(
                List.of(image(1), image(2), image(3)),
                img -> {
                    if (stored.contains(img.fileName())) {
                        return Outcome.ok(OCR);            // 재사용 경로
                    }
                    ocrCalls.add(img.fileName());          // API 호출 경로
                    return Outcome.ok(OCR);
                },
                img -> Outcome.ok("https://x"),
                1, 1);

        assertThat(ocrCalls).containsExactly("img_02.jpg");
        assertThat(records).hasSize(3);
        assertThat(records).allMatch(r -> r.ocr().isOk());
    }

    @Test
    void 갈래별_호출_순서는_OCR_다음_업로드다() {
        List<String> calls = new ArrayList<>();

        OcrBatchRunner.buildRecords(
                List.of(image(1), image(2)),
                img -> { calls.add("ocr:" + img.detailNumber()); return Outcome.ok(OCR); },
                img -> { calls.add("upload:" + img.detailNumber()); return Outcome.ok("u"); },
                1, 1);

        assertThat(calls).containsExactly("ocr:1", "upload:1", "ocr:2", "upload:2");
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew test --tests '*OcrBatchRunnerTest*'`
Expected: FAIL — `cannot find symbol: class OcrBatchRunner`

- [ ] **Step 3: Write minimal implementation**

```java
package com.dreamtrue.ocr.runner;

import com.dreamtrue.ocr.claude.ClaudeOcrClient;
import com.dreamtrue.ocr.config.OcrProperties;
import com.dreamtrue.ocr.domain.ArchiveRecord;
import com.dreamtrue.ocr.domain.OcrResult;
import com.dreamtrue.ocr.domain.Outcome;
import com.dreamtrue.ocr.domain.SourceImage;
import com.dreamtrue.ocr.drive.DriveImageUploader;
import com.dreamtrue.ocr.drive.SystemicFailureException;
import com.dreamtrue.ocr.image.ImageScanner;
import com.dreamtrue.ocr.report.BatchReport;
import com.dreamtrue.ocr.sheets.SheetsGateway;
import com.dreamtrue.ocr.sheets.SheetsWriter;
import com.dreamtrue.ocr.store.OcrResultStore;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.ExitCodeGenerator;
import org.springframework.stereotype.Component;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;

@Slf4j
@Component
@RequiredArgsConstructor
public class OcrBatchRunner implements ApplicationRunner, ExitCodeGenerator {

    private final ImageScanner scanner;
    private final ClaudeOcrClient claude;
    private final DriveImageUploader.DriveGateway driveGateway;
    private final SheetsGateway sheetsGateway;
    private final OcrProperties properties;

    private int exitCode = 0;

    @Override
    public void run(ApplicationArguments args) {
        String inputDir = firstOr(args, "input", properties.inputDir());
        String outputDir = firstOr(args, "output", properties.outputDir());
        boolean skipOcr = args.containsOption("skip-ocr");
        // 저장된 결과는 재사용하고, 없는 장(= 지난 실행에서 실패한 장)만 다시 호출한다.
        boolean retryFailed = args.containsOption("retry-failed");

        BatchReport report;
        try {
            List<SourceImage> images = scanner.scan(Path.of(inputDir));
            if (images.isEmpty()) {
                log.warn("입력 디렉토리에 이미지가 없습니다: {}", inputDir);
            }
            log.info("이미지 {}장을 처리합니다 ({}){}", images.size(), inputDir,
                    skipOcr ? " [--skip-ocr: API 호출 없음]"
                            : retryFailed ? " [--retry-failed: 저장된 결과 재사용]" : "");

            OcrResultStore store = new OcrResultStore(Path.of(outputDir));
            DriveImageUploader uploader =
                    new DriveImageUploader(driveGateway, properties.google().driveFolderId());

            List<ArchiveRecord> records = buildRecords(
                    images,
                    img -> runOcr(img, store, skipOcr, retryFailed),
                    uploader::upload,
                    properties.folderNumber(),
                    properties.fileNumber());

            Map<String, Boolean> landscape = new HashMap<>();
            images.forEach(i -> landscape.put(i.fileName(), i.isLandscape()));

            String url = new SheetsWriter(sheetsGateway).write(records, landscape);
            report = BatchReport.of(records, url);

        } catch (SystemicFailureException e) {
            report = BatchReport.systemicFailure(e.getMessage());
        } catch (Exception e) {
            log.debug("배치 실패", e);
            report = BatchReport.systemicFailure(e.getMessage());
        }

        System.out.println(report.render());
        this.exitCode = report.exitCode();
    }

    /** 순차 처리. 이미지 1장 처리가 자기완결적이라 공유 가변 상태가 없다. */
    static List<ArchiveRecord> buildRecords(
            List<SourceImage> images,
            Function<SourceImage, Outcome<OcrResult>> ocr,
            Function<SourceImage, Outcome<String>> upload,
            int folderNumber,
            int fileNumber) {

        List<ArchiveRecord> records = new ArrayList<>();
        for (SourceImage image : images) {
            Outcome<OcrResult> ocrResult = ocr.apply(image);
            Outcome<String> photoUrl = upload.apply(image);
            records.add(new ArchiveRecord(
                    folderNumber, fileNumber, image.detailNumber(),
                    image.path(), ocrResult, photoUrl));
        }
        return records;
    }

    private Outcome<OcrResult> runOcr(SourceImage image, OcrResultStore store,
                                      boolean skipOcr, boolean retryFailed) {
        try {
            if (skipOcr || retryFailed) {
                Optional<OcrResult> stored = store.read(image.fileName());
                if (stored.isPresent()) {
                    log.info("{}: 저장된 결과를 재사용합니다", image.fileName());
                    return Outcome.ok(stored.get());
                }
                if (skipOcr) {
                    return Outcome.failed("--skip-ocr 이지만 저장된 결과가 없습니다: output/raw/");
                }
                // retryFailed 는 여기서 멈추지 않고 아래로 내려가 API 를 호출한다.
                log.info("{}: 저장된 결과가 없어 다시 인식합니다", image.fileName());
            }
            Outcome<OcrResult> result = claude.ocr(image);
            if (result instanceof Outcome.Ok<OcrResult> ok) {
                store.write(image.fileName(), properties.claude().model(), ok.value());
            }
            return result;
        } catch (Exception e) {
            return Outcome.failed(e.getMessage());
        }
    }

    private String firstOr(ApplicationArguments args, String name, String fallback) {
        List<String> values = args.getOptionValues(name);
        return (values == null || values.isEmpty()) ? fallback : values.getFirst();
    }

    @Override
    public int getExitCode() {
        return exitCode;
    }
}
```

> **참고:** `--model` 플래그는 Spring Boot가 `--ocr.claude.model=...` 형태로 이미
> 지원한다. README 에는 `--ocr.claude.model=claude-sonnet-5` 로 안내한다.

- [ ] **Step 4: Run test to verify it passes**

Run: `./gradlew test --tests '*OcrBatchRunnerTest*'`
Expected: PASS (5 tests)

- [ ] **Step 5: Commit**

```bash
git add -A
git commit -m "feat: OcrBatchRunner 추가 (순차 처리, 재시도 모드, 종료 코드)"
```

---

### Task 14: 통합 테스트 — Anthropic 스텁으로 파이프라인 전체 검증

**Files:**
- Test: `src/test/java/com/dreamtrue/ocr/PipelineIntegrationTest.java`

**Interfaces:**
- Consumes: 모든 컴포넌트

**설계 근거:** 외부 접점이 4곳(파일시스템·Anthropic·Drive·Sheets)뿐이라, Anthropic만 MockWebServer로 스텁하고 Drive/Sheets는 대역으로 바꾸면 파이프라인 전체를 실제 API 없이 돌릴 수 있다.

- [ ] **Step 1: Write the failing test**

```java
package com.dreamtrue.ocr;

import com.dreamtrue.ocr.domain.ArchiveRecord;
import com.dreamtrue.ocr.domain.OcrResult;
import com.dreamtrue.ocr.domain.Outcome;
import com.dreamtrue.ocr.domain.SourceImage;
import com.dreamtrue.ocr.image.ImageScanner;
import com.dreamtrue.ocr.report.BatchReport;
import com.dreamtrue.ocr.runner.OcrBatchRunner;
import com.dreamtrue.ocr.sheets.SheetsGateway;
import com.dreamtrue.ocr.sheets.SheetsWriter;
import com.dreamtrue.ocr.store.OcrResultStore;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.file.Path;
import java.util.*;

import static org.assertj.core.api.Assertions.assertThat;

class PipelineIntegrationTest {

    static class FakeSheets implements SheetsGateway {
        final Map<String, List<List<Object>>> written = new LinkedHashMap<>();

        @Override public void deleteAllSheetsExceptOne() {}

        @Override public Map<String, Integer> createSheets(List<String> titles) {
            Map<String, Integer> gids = new LinkedHashMap<>();
            int gid = 100;
            for (String t : titles) gids.put(t, gid++);
            return gids;
        }

        @Override public void writeValues(String title, List<List<Object>> rows) {
            written.put(title, rows);
        }

        @Override public void applyFormatting(Map<String, Integer> gids) {}

        @Override public String spreadsheetUrl() {
            return "https://docs.google.com/spreadsheets/d/TEST";
        }
    }

    private static final OcrResult OCR = new OcrResult(
            "보관증 — 김경석 수집 기록물 (1968.8.1.)",
            "보 관 증\n\n一. 명    칭 : 김경석 수집 기록물",
            "인쇄체 국한문 혼용 단면 문서(보관증)",
            "훼손·오염 없이 전체 판독 가능",
            "없음",
            "붉은색 원형 관인 날인",
            null);

    private void writeJpeg(Path p, int w, int h) throws IOException {
        ImageIO.write(new BufferedImage(w, h, BufferedImage.TYPE_INT_RGB), "jpg", p.toFile());
    }

    @Test
    void 세로_가로_문서가_섞여도_전체_파이프라인이_동작한다(@TempDir Path dir) throws IOException {
        Path input = dir.resolve("input");
        java.nio.file.Files.createDirectories(input);
        writeJpeg(input.resolve("img_01.jpg"), 1000, 1400);   // 세로
        writeJpeg(input.resolve("img_05.jpg"), 1200, 850);    // 가로

        List<SourceImage> images = new ImageScanner().scan(input);
        OcrResultStore store = new OcrResultStore(dir.resolve("output"));

        List<ArchiveRecord> records = OcrBatchRunner.buildRecords(
                images,
                img -> {
                    try { store.write(img.fileName(), "claude-opus-5", OCR); }
                    catch (IOException e) { throw new UncheckedIOException(e); }
                    return Outcome.ok(OCR);
                },
                img -> Outcome.ok("https://drive.google.com/thumbnail?id=" + img.detailNumber() + "&sz=w1000"),
                1, 1);

        Map<String, Boolean> landscape = new HashMap<>();
        images.forEach(i -> landscape.put(i.fileName(), i.isLandscape()));

        FakeSheets sheets = new FakeSheets();
        String url = new SheetsWriter(sheets).write(records, landscape);
        BatchReport report = BatchReport.of(records, url);

        // 목록 1 + 상세 2
        assertThat(sheets.written.keySet()).containsExactly("목록", "1-1", "1-2");

        // 목록 행 구조
        List<List<Object>> index = sheets.written.get("목록");
        assertThat(index).hasSize(3);
        assertThat(index.get(1).get(0)).isEqualTo(1);   // 폴더번호
        assertThat(index.get(1).get(1)).isEqualTo(1);   // 파일번호
        assertThat(index.get(1).get(2)).isEqualTo(1);   // 세부번호

        // 세로 문서와 가로 문서의 이미지 크기 인자가 다르다
        assertThat((String) sheets.written.get("1-1").get(6).get(1)).contains(",4,600,430)");
        assertThat((String) sheets.written.get("1-2").get(6).get(1)).contains(",4,430,600)");

        // 분석 셀에 특이사항이 조립되어 있다
        assertThat((String) sheets.written.get("1-1").get(5).get(1))
                .contains("보 관 증")
                .contains("※ 특이사항:")
                .contains("붉은색 원형 관인 날인");

        // 결과가 디스크에 남아 --skip-ocr 로 재사용 가능하다
        assertThat(store.read("img_01.jpg")).contains(OCR);

        assertThat(report.exitCode()).isZero();
        assertThat(report.render()).contains("2건 중 2건 성공");
    }

    @Test
    void 업로드가_실패해도_분석_내용은_기록된다(@TempDir Path dir) throws IOException {
        Path input = dir.resolve("input");
        java.nio.file.Files.createDirectories(input);
        writeJpeg(input.resolve("img_01.jpg"), 1000, 1400);

        List<SourceImage> images = new ImageScanner().scan(input);
        List<ArchiveRecord> records = OcrBatchRunner.buildRecords(
                images,
                img -> Outcome.ok(OCR),
                img -> Outcome.failed("503 backendError, 3회 재시도 후 포기"),
                1, 1);

        FakeSheets sheets = new FakeSheets();
        String url = new SheetsWriter(sheets).write(records, Map.of());
        BatchReport report = BatchReport.of(records, url);

        List<List<Object>> detail = sheets.written.get("1-1");
        assertThat((String) detail.get(5).get(1)).contains("보 관 증");   // 분석은 정상
        assertThat((String) detail.get(6).get(1))                        // 사진만 실패
                .startsWith("[사진 업로드 실패] 503 backendError")
                .contains("원본:");

        assertThat(report.exitCode()).isEqualTo(1);
        assertThat(report.render()).contains("실패 1건");
    }
}
```

`import java.io.UncheckedIOException;` 를 함께 추가한다.

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew test --tests '*PipelineIntegrationTest*'`
Expected: FAIL — 아직 일부 클래스가 없거나 배선이 맞지 않음

- [ ] **Step 3: 구현 조정**

새 프로덕션 코드를 쓰지 않는다. 테스트가 실패하면 Task 1~13에서 만든 클래스의 시그니처 불일치를 맞춘다. 이 태스크의 목적은 컴포넌트 간 계약이 실제로 맞물리는지 확인하는 것이다.

- [ ] **Step 4: Run test to verify it passes**

Run: `./gradlew test`
Expected: 전체 테스트 PASS

- [ ] **Step 5: Commit**

```bash
git add -A
git commit -m "test: 파이프라인 통합 테스트 추가"
```

---

### Task 15: README 작성과 실제 실행 검증

**Files:**
- Create: `README.md`
- Modify: `.gitignore` (확인)

**Interfaces:**
- Consumes: 전체

- [ ] **Step 1: README 작성**

`README.md`:

````markdown
# 기록물 정리 OCR 솔루션

특정 디렉토리의 기록물 사진을 Claude로 OCR 하여, 인식 결과와 원본 사진을
Google Sheet에 자동으로 기록하는 배치 프로그램입니다.

## 동작

```
input/*.jpg
   ├─ Claude Messages API (base64 직접 전달)  → 제목 + 분석(내용)
   └─ Google Drive 업로드 → 링크 공유          → 시트 셀의 =IMAGE()
                    ↓
          Google Sheet
            목록 시트    : 폴더번호 | 파일번호 | 세부번호 | 제목
            상세 시트 N개 : 파일번호/세부번호/제목/분석(내용)/사진
```

파일번호는 1 고정, 세부번호는 파일명 정렬 순서로 1부터 부여됩니다.

## 요구사항

- JDK 21 이상 (`java -version`으로 확인)
- Gradle 설치 불필요 — `./gradlew`가 알아서 받습니다
- Anthropic API 키
- Google Cloud 프로젝트

## 준비

### 1. Anthropic API 키

```bash
export ANTHROPIC_API_KEY=sk-ant-...
```

### 2. Google Cloud 설정

1. [Google Cloud Console](https://console.cloud.google.com/)에서 프로젝트를 만듭니다.
2. **API 및 서비스 → 라이브러리**에서 다음 두 개를 사용 설정합니다.
   - Google Sheets API
   - Google Drive API
3. **API 및 서비스 → 사용자 인증 정보 → 사용자 인증 정보 만들기 → 서비스 계정**
   으로 서비스 계정을 만듭니다.
4. 만든 서비스 계정 → **키 → 키 추가 → 새 키 만들기 → JSON**을 내려받아
   `credentials/service-account.json`으로 저장합니다.
5. 서비스 계정 이메일(`...@<프로젝트>.iam.gserviceaccount.com`)을 복사해 둡니다.

### 3. 스프레드시트와 Drive 폴더 공유

이 단계를 빠뜨리면 403으로 중단됩니다.

1. 빈 Google 스프레드시트를 만들고, **공유**에서 위 서비스 계정 이메일을
   **편집자**로 추가합니다.
   URL의 `/d/`와 `/edit` 사이 문자열이 스프레드시트 ID입니다.
2. Google Drive에 폴더를 만들고, 같은 서비스 계정 이메일을 **편집자**로 추가합니다.
   URL의 `folders/` 뒤 문자열이 폴더 ID입니다.

### 4. 설정 파일

`application-example.yml`을 참고해 `src/main/resources/application.yml`의
아래 두 값을 채웁니다.

```yaml
ocr:
  google:
    spreadsheet-id: "여기에_스프레드시트_ID"
    drive-folder-id: "여기에_폴더_ID"
```

## 실행

기록물 사진을 `input/`에 넣고:

```bash
./gradlew bootRun
```

옵션:

```bash
# 입력 디렉토리 지정
./gradlew bootRun --args='--input=./다른폴더'

# 실패했던 항목만 다시 처리 (성공한 것은 저장된 결과 재사용)
./gradlew bootRun --args='--retry-failed'

# 저장된 OCR 결과만 사용 (Claude를 호출하지 않음)
./gradlew bootRun --args='--skip-ocr'

# 모델 변경
./gradlew bootRun --args='--ocr.claude.model=claude-sonnet-5'
```

실행이 끝나면 콘솔에 스프레드시트 URL과 성공/실패 요약이 출력됩니다.

## 결과 확인

- **Google Sheet** — `목록` 시트의 제목을 클릭하면 해당 상세 시트로 이동하고,
  상세 시트 좌상단 `◀ 목록으로`로 돌아옵니다.
- **`output/raw/*.json`** — Claude 응답 원본이 항목별로 분리되어 남습니다.
  시트에는 조립된 최종 문자열만 보이므로, 항목별 원본을 보려면 이 파일을 확인하세요.

## 재실행 모드

OCR 결과는 항상 `output/raw/`에 저장됩니다. 기본 실행은 이 파일을 **읽지 않고**
매번 새로 호출하므로 낡은 결과가 섞일 일이 없습니다.

| 명령 | 저장된 결과가 있으면 | 없으면 |
|---|---|---|
| `./gradlew bootRun` | 무시하고 새로 호출 | 새로 호출 |
| `... --args='--retry-failed'` | 재사용 | 새로 호출 |
| `... --args='--skip-ocr'` | 재사용 | 실패 처리 (호출 안 함) |

**`--retry-failed`** — 일부 이미지만 실패했을 때 씁니다. 성공한 것은 저장된 결과를
재사용하고 실패했던 것만 다시 처리하므로, 10장 중 1장이 실패했다면 1장만 비용이 듭니다.
사진 업로드도 마찬가지입니다. 이미 올라간 파일은 다시 올리지 않습니다.

실패가 있으면 실행 결과 마지막에 이 명령이 그대로 안내됩니다.

**`--skip-ocr`** — Claude를 절대 호출하지 않습니다.

- 시트 서식(열 너비, 행 높이)을 다듬으며 여러 번 돌릴 때
- Drive 권한 오류로 중단된 뒤 권한을 고치고 재실행할 때

`--retry-failed`와 달리 저장된 결과가 없는 장은 호출하지 않고 실패로 남깁니다.
"호출하지 않는다"가 확실해야 할 때 씁니다.

## 모델과 비용

기본 모델은 `claude-opus-5`입니다. 10장 1회 실행 실비는 약 $0.54입니다.

| 모델 | 10장 1회 | 비고 |
|---|---|---|
| `claude-opus-5` (기본) | ~$0.54 | 고해상도 티어(장변 2576px)라 입력 이미지가 축소되지 않음 |
| `claude-sonnet-5` | ~$0.21 | 동일한 고해상도 티어 |
| `claude-haiku-4-5` | ~$0.11 | 표준 티어(1568 비주얼 토큰)라 다수 이미지가 강제 축소됨 |

세로쓰기 한자·손글씨·훼손 문서 판독이 이 작업의 난이도이므로 기본값을 Opus 5로
두었습니다.

## 종료 코드

| 코드 | 의미 |
|---|---|
| 0 | 전량 성공 |
| 1 | 부분 실패 (일부 이미지의 OCR 또는 업로드 실패, 시트는 기록됨). `--retry-failed`로 재시도 |
| 2 | 계통 실패 (권한·설정 오류로 중단) |

## 문제 해결

**`Drive 업로드 권한 오류 (403 insufficientPermissions)`**
Drive 폴더를 서비스 계정 이메일에 편집자로 공유했는지 확인하세요. 조직 정책이
링크 공유를 차단하는 경우 `=IMAGE()` 렌더링이 불가능합니다.

**시트의 사진 칸이 깨져 보임**
Drive가 썸네일을 생성하는 데 시간이 걸릴 수 있습니다. 잠시 후 새로고침하세요.
계속 깨진다면 업로드된 파일의 공유 설정이 "링크가 있는 모든 사용자"인지 확인하세요.

**`ocr.google.spreadsheet-id 가 비어 있습니다`**
`src/main/resources/application.yml`에 스프레드시트 ID를 넣지 않았습니다.

**`서비스 계정 키 파일을 찾을 수 없습니다`**
`credentials/service-account.json` 경로를 확인하세요.

**이미지 장변 초과 오류**
이 프로그램은 판독 품질을 위해 이미지를 자동 축소하지 않습니다. 장변 2576px 이하로
줄여서 다시 넣어 주세요.

## 테스트

```bash
./gradlew test
```

외부 API를 호출하지 않습니다. Anthropic·Drive·Sheets는 모두 대역으로 대체됩니다.

## 구조

| 패키지 | 책임 |
|---|---|
| `image` | 스캔·정렬·매직바이트 MIME 판별·채번 |
| `claude` | 프롬프트, 크기 검증, Messages API 호출 |
| `store` | OCR 결과 JSON 저장·읽기 |
| `drive` | 업로드·링크 공유·썸네일 URL |
| `sheets` | 셀 좌표·수식 조립, 시트 생성 2단계 |
| `domain` | 도메인 타입, 분석 내용 조립, 실패 조합 처리 |
| `report` | 요약 출력, 종료 코드 |
| `runner` | 전체 배선 |

설계 근거는 `docs/superpowers/specs/2026-09-03-archive-ocr-design.md`에 있습니다.
````

- [ ] **Step 2: 전체 빌드 검증**

Run: `./gradlew clean build`
Expected: BUILD SUCCESSFUL, 모든 테스트 통과

- [ ] **Step 3: 실제 실행 (자격증명이 준비된 경우에만)**

Run: `./gradlew bootRun`

확인 항목:
- 콘솔에 `이미지 10장을 처리합니다` 출력
- 종료 시 `처리 완료: 10건 중 10건 성공`과 스프레드시트 URL 출력
- `output/raw/img_01.json` ~ `img_10.json` 생성
- 시트에 `목록` + `1-1`~`1-10` 총 11개 시트
- 목록 제목 클릭 시 상세 시트로 이동, `◀ 목록으로`로 복귀
- 상세 시트 사진 칸에 이미지가 렌더링됨 (깨진 아이콘 아님)
- `분석(내용)` 칸에 전사와 `※ 특이사항:` 블록이 모두 있음

이어서 재실행 검증:

Run: `./gradlew bootRun --args='--skip-ocr'`
Expected: Claude 호출 없이 시트가 다시 만들어짐. 결과 동일.

부분 실패 재시도 검증:

```bash
rm output/raw/img_03.json          # 3번이 실패했던 상황을 만든다
./gradlew bootRun --args='--retry-failed'
```

Expected: 로그에 `img_03.jpg: 저장된 결과가 없어 다시 인식합니다` 1건과
나머지 9건의 `저장된 결과를 재사용합니다`가 찍히고, Claude 호출은 1회만 발생.

- [ ] **Step 4: Commit**

```bash
git add -A
git commit -m "docs: README 추가 (설정 절차, 실행 방법, 트러블슈팅)"
```

---

## 자체 점검 결과

**spec 커버리지**

| spec 절 | 담당 태스크 |
|---|---|
| §3.1 목록 시트 | Task 10, 11 |
| §3.2 상세 시트 배치 | Task 10, 11 |
| §3.3 출력 규칙 | Task 6 (프롬프트), Task 2 (스키마 지시문) |
| §5.1 채번·MIME | Task 3 |
| §5.2 OCR 호출 | Task 7 |
| §5.3 분석 조립 | Task 4 |
| §5.4 결과 저장 | Task 8 |
| §5.4.1 Outcome·조립 | Task 2, 5 |
| §5.5 Drive | Task 9 |
| §5.6 Sheets 2단계 | Task 11 |
| §6.1 실패 조합 4가지 | Task 5 |
| §6.2 개별/계통 실패 | Task 9 |
| §6.3 재시도 | Task 7, 9 |
| §6.4 종료 코드 | Task 12 |
| §6.5 순차 실행 | Task 13 |
| §7 설정·CLI | Task 1, 13 |
| §8 테스트 | 전 태스크 + Task 14 |
| §9 제출물 | Task 15 |

**알려진 조정 지점 (구현 중 컴파일러가 알려줄 것)**

1. `ClaudeOcrClient.ocr()` 에서 **구조화 응답을 꺼내는 부분만** SDK 반환 타입에 맞춰야
   한다. 요청 측(파라미터 빌드, 이미지 블록, `ContentBlockParam.isImage()/isText()`)은
   실제 컴파일로 검증된 형태이므로 바꾸지 않는다. Task 7의 "컴파일 조정 지침" 참조.
2. `GoogleSheetsGateway`의 `@SneakyThrows`가 checked exception을 삼키는 것이
   불편하면 인터페이스에 `throws IOException`을 추가하고 호출부를 맞춘다.
