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
