package com.megagraph.shop.media;

import com.megagraph.shop.shared.NotFoundException;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Locale;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.FileSystemResource;
import org.springframework.core.io.Resource;
import org.springframework.stereotype.Component;

@Component
public class LocalMediaStorage implements MediaStorage {

    private final Path root;

    public LocalMediaStorage(@Value("${app.media.upload-dir}") String uploadDir) {
        this.root = Path.of(uploadDir).toAbsolutePath().normalize();
    }

    @Override
    public StoredMedia store(
            Long merchantId,
            String originalFilename,
            String contentType,
            InputStream inputStream
    ) throws IOException {
        String extension = extensionFor(contentType);
        String key = "merchant/" + merchantId + "/" + UUID.randomUUID() + extension;
        Path target = root.resolve(key).normalize();
        if (!target.startsWith(root)) {
            throw new IllegalArgumentException("Invalid media key");
        }
        Files.createDirectories(target.getParent());
        long bytes;
        try { bytes = Files.copy(inputStream, target); }
        catch(IOException e) { Files.deleteIfExists(target); throw e; }
        return new StoredMedia(key, contentType.toLowerCase(Locale.ROOT), bytes);
    }

    @Override
    public Resource load(String storageKey) {
        Path target = root.resolve(storageKey).normalize();
        if (!target.startsWith(root) || !Files.isRegularFile(target)) {
            throw new NotFoundException("MEDIA_NOT_FOUND", "图片不存在");
        }
        return new FileSystemResource(target);
    }

    @Override
    public void delete(String key) throws IOException {
        Path target=root.resolve(key).normalize();
        if(!target.startsWith(root) || !key.startsWith("merchant/")) throw new IOException("Invalid media key");
        Files.deleteIfExists(target);
    }
    @Override
    public java.util.List<String> keysOlderThan(java.time.Instant cutoff) throws IOException {
        if(!Files.exists(root.resolve("merchant"))) return java.util.List.of();
        try(var files=Files.walk(root.resolve("merchant"))) {
            return files.filter(Files::isRegularFile).filter(path -> {
                try { return Files.getLastModifiedTime(path).toInstant().isBefore(cutoff); }
                catch(IOException e) { return false; }
            }).map(path -> root.relativize(path).toString().replace('\\','/')).toList();
        }
    }
    private String extensionFor(String contentType) {
        return switch (contentType.toLowerCase(Locale.ROOT)) {
            case "image/jpeg" -> ".jpg";
            case "image/png" -> ".png";
            case "image/webp" -> ".webp";
            default -> ".bin";
        };
    }
}

