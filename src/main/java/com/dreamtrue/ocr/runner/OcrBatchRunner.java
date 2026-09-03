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
    public static List<ArchiveRecord> buildRecords(
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

    /** 저장된 결과를 재사용해도 되는가. */
    static boolean useStored(boolean skipOcr, boolean retryFailed, boolean storedPresent) {
        return storedPresent && (skipOcr || retryFailed);
    }

    /** 저장된 결과가 없을 때 API를 호출해도 되는가. */
    static boolean mayCallApi(boolean skipOcr) {
        return !skipOcr;
    }

    private Outcome<OcrResult> runOcr(SourceImage image, OcrResultStore store,
                                      boolean skipOcr, boolean retryFailed) {
        try {
            Optional<OcrResult> stored = store.read(image.fileName());
            boolean storedPresent = stored.isPresent();

            if (useStored(skipOcr, retryFailed, storedPresent)) {
                log.info("{}: 저장된 결과를 재사용합니다", image.fileName());
                return Outcome.ok(stored.get());
            }

            if (!mayCallApi(skipOcr)) {
                return Outcome.failed("--skip-ocr 이지만 저장된 결과가 없습니다: output/raw/");
            }

            if (storedPresent && retryFailed) {
                log.info("{}: 저장된 결과가 있어 스킵합니다", image.fileName());
                return Outcome.ok(stored.get());
            }

            // API 호출
            log.info("{}: 저장된 결과가 없어 다시 인식합니다", image.fileName());
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
