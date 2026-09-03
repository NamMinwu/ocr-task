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
