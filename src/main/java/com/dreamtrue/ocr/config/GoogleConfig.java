package com.dreamtrue.ocr.config;

import com.google.api.client.auth.oauth2.Credential;
import com.google.api.client.extensions.java6.auth.oauth2.AuthorizationCodeInstalledApp;
import com.google.api.client.extensions.jetty.auth.oauth2.LocalServerReceiver;
import com.google.api.client.googleapis.auth.oauth2.GoogleAuthorizationCodeFlow;
import com.google.api.client.googleapis.auth.oauth2.GoogleClientSecrets;
import com.google.api.client.googleapis.javanet.GoogleNetHttpTransport;
import com.google.api.client.http.HttpRequestInitializer;
import com.google.api.client.http.HttpTransport;
import com.google.api.client.json.JsonFactory;
import com.google.api.client.json.gson.GsonFactory;
import com.google.api.client.util.store.FileDataStoreFactory;
import com.google.api.services.drive.Drive;
import com.google.api.services.drive.DriveScopes;
import com.google.api.services.sheets.v4.Sheets;
import com.google.api.services.sheets.v4.SheetsScopes;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.io.IOException;
import java.io.Reader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.GeneralSecurityException;
import java.util.List;

/**
 * 사용자 계정 OAuth 로 인증한다.
 *
 * <p>서비스 계정은 개인 Drive 에 파일을 소유할 수 없다(403 storageQuotaExceeded).
 * 공유 드라이브가 있는 Workspace 환경에서만 동작하므로, 개인 Google 계정에서도
 * 실행되도록 사용자 계정 OAuth 를 쓴다. 파일은 사용자 본인 소유로 본인 용량에
 * 생성되고, 스프레드시트·폴더를 별도로 공유할 필요도 없어진다.
 */
@Slf4j
@Configuration
public class GoogleConfig {

    private static final String APP_NAME = "archive-ocr";
    private static final int TIMEOUT_MS = 30_000;
    private static final int CALLBACK_PORT = 8888;

    /** Drive 전체 권한이 필요하다. 사용자가 미리 만들어 둔 폴더를 조회해야 하는데
     *  drive.file 스코프로는 이 앱이 만든 파일만 보인다. */
    private static final List<String> SCOPES = List.of(DriveScopes.DRIVE, SheetsScopes.SPREADSHEETS);

    @Bean
    public Credential googleCredential(OcrProperties properties) throws IOException, GeneralSecurityException {
        Path clientPath = Path.of(properties.google().oauthClientPath());
        requireClientFile(clientPath);

        JsonFactory jsonFactory = GsonFactory.getDefaultInstance();
        HttpTransport transport = GoogleNetHttpTransport.newTrustedTransport();

        GoogleClientSecrets secrets;
        try (Reader reader = Files.newBufferedReader(clientPath)) {
            secrets = GoogleClientSecrets.load(jsonFactory, reader);
        }

        GoogleAuthorizationCodeFlow flow = new GoogleAuthorizationCodeFlow.Builder(
                transport, jsonFactory, secrets, SCOPES)
                .setDataStoreFactory(new FileDataStoreFactory(
                        Path.of(properties.google().tokenStorePath()).toFile()))
                .setAccessType("offline")
                .build();

        log.info("Google 계정 인증을 확인합니다. 최초 1회는 브라우저에서 동의가 필요합니다.");
        LocalServerReceiver receiver = new LocalServerReceiver.Builder().setPort(CALLBACK_PORT).build();
        Credential credential = new AuthorizationCodeInstalledApp(flow, receiver).authorize("user");
        log.info("Google 인증 완료");
        return credential;
    }

    @Bean
    public Drive drive(Credential credential) throws IOException, GeneralSecurityException {
        return new Drive.Builder(GoogleNetHttpTransport.newTrustedTransport(),
                GsonFactory.getDefaultInstance(), withTimeouts(credential))
                .setApplicationName(APP_NAME)
                .build();
    }

    @Bean
    public Sheets sheets(Credential credential) throws IOException, GeneralSecurityException {
        return new Sheets.Builder(GoogleNetHttpTransport.newTrustedTransport(),
                GsonFactory.getDefaultInstance(), withTimeouts(credential))
                .setApplicationName(APP_NAME)
                .build();
    }

    /** 기본 타임아웃은 무한에 가까워, 응답이 늦으면 배치가 조용히 멈춘 것처럼 보인다. */
    private HttpRequestInitializer withTimeouts(Credential credential) {
        return request -> {
            credential.initialize(request);
            request.setConnectTimeout(TIMEOUT_MS);
            request.setReadTimeout(TIMEOUT_MS);
        };
    }

    static void requireClientFile(Path path) throws IOException {
        if (!Files.exists(path)) {
            throw new IOException("""
                    OAuth 클라이언트 파일을 찾을 수 없습니다: %s
                      GCP 콘솔 → API 및 서비스 → 사용자 인증 정보 →
                      사용자 인증 정보 만들기 → OAuth 클라이언트 ID → 애플리케이션 유형 '데스크톱 앱'
                      으로 만든 뒤 JSON 을 내려받아 위 경로에 저장하세요."""
                    .formatted(path.toAbsolutePath()));
        }
    }

    public static String requireSpreadsheetId(String spreadsheetId) {
        if (spreadsheetId == null || spreadsheetId.isBlank()) {
            throw new IllegalStateException(
                    "ocr.google.spreadsheet-id 가 비어 있습니다. application-local.yml 을 확인하세요.");
        }
        return spreadsheetId;
    }
}
