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
        String advice = e.getStatusCode() == 404
                ? "폴더 ID가 올바른지 확인하세요. 폴더가 존재하고 URL에서 ID를 올바르게 복사했는지 확인하세요."
                : "해당 폴더를 서비스 계정 이메일에 '편집자'로 공유했는지 확인하세요.\n" +
                  "조직 정책이 링크 공유를 차단하는 경우 =IMAGE() 렌더링이 불가능합니다.";

        return """
                Drive 업로드 권한 오류 (%d %s)
                  폴더 ID : %s
                  확인 : %s
                  OCR 결과는 output/raw/ 에 보존되었습니다. 권한 수정 후 --skip-ocr 로 재실행하세요."""
                .formatted(e.getStatusCode(), e.getStatusMessage(), folderId, advice);
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
