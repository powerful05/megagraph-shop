package com.megagraph.shop.media;

import java.io.IOException;
import java.io.InputStream;
import org.springframework.core.io.Resource;

public interface MediaStorage {
    StoredMedia store(Long merchantId, String originalFilename, String contentType, InputStream inputStream)
            throws IOException;

    Resource load(String storageKey);
    default void delete(String storageKey) throws IOException { throw new IOException("Deletion unsupported"); }
    default java.util.List<String> keysOlderThan(java.time.Instant cutoff) throws IOException { return java.util.List.of(); }
}

