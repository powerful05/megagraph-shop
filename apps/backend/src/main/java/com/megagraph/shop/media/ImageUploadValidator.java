package com.megagraph.shop.media;

import com.megagraph.shop.shared.ValidationException;
import java.io.IOException;
import java.util.List;
import java.util.Locale;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.multipart.MultipartFile;

@Component
public class ImageUploadValidator {

    private final long maxBytes;
    private final List<String> allowedContentTypes;

    public ImageUploadValidator(
            @Value("${app.media.max-bytes}") long maxBytes,
            @Value("${app.media.allowed-content-types}") List<String> allowedContentTypes
    ) {
        this.maxBytes = maxBytes;
        this.allowedContentTypes = allowedContentTypes.stream()
                .map(value -> value.toLowerCase(Locale.ROOT))
                .toList();
    }

    public void validate(MultipartFile file) {
        if (file == null || file.isEmpty()) {
            throw new ValidationException("IMAGE_REQUIRED", "请选择一张商品图片");
        }
        if (file.getSize() > maxBytes) {
            throw new ValidationException("IMAGE_TOO_LARGE", "图片不能超过 10MB");
        }
        String contentType = file.getContentType() == null ? "" : file.getContentType().toLowerCase(Locale.ROOT);
        if (!allowedContentTypes.contains(contentType)) {
            throw new ValidationException("IMAGE_TYPE_NOT_ALLOWED", "只支持 JPEG、PNG 或 WebP 图片");
        }
        try {
            byte[] header;
            try(var stream=file.getInputStream()){header=stream.readNBytes(12);}
            if (!matchesSignature(contentType, header)) {
                throw new ValidationException("IMAGE_SIGNATURE_INVALID", "图片内容与文件类型不一致");
            }
            try(var stream=file.getInputStream();var input=javax.imageio.ImageIO.createImageInputStream(stream)) {
                var readers=javax.imageio.ImageIO.getImageReaders(input);
                if(!readers.hasNext()) throw new ValidationException("IMAGE_CONTENT_INVALID","图片无法解码");
                var reader=readers.next();
                try {
                    reader.setInput(input,true,true);
                    int width=reader.getWidth(0),height=reader.getHeight(0);
                    if(width<1 || height<1 || (long)width*height>32_000_000) throw new ValidationException("IMAGE_DIMENSIONS_INVALID","图片像素数量不支持");
                    if(reader.read(0)==null) throw new ValidationException("IMAGE_CONTENT_INVALID","图片无法解码");
                } finally { reader.dispose(); }
            }
        } catch (IOException exception) {
            throw new ValidationException("IMAGE_READ_FAILED", "无法读取上传图片");
        }
    }

    private boolean matchesSignature(String contentType, byte[] bytes) {
        return switch (contentType) {
            case "image/jpeg" -> bytes.length >= 3
                    && unsigned(bytes[0]) == 0xFF
                    && unsigned(bytes[1]) == 0xD8
                    && unsigned(bytes[2]) == 0xFF;
            case "image/png" -> bytes.length >= 8
                    && unsigned(bytes[0]) == 0x89
                    && bytes[1] == 0x50
                    && bytes[2] == 0x4E
                    && bytes[3] == 0x47;
            case "image/webp" -> bytes.length >= 12
                    && bytes[0] == 'R' && bytes[1] == 'I' && bytes[2] == 'F' && bytes[3] == 'F'
                    && bytes[8] == 'W' && bytes[9] == 'E' && bytes[10] == 'B' && bytes[11] == 'P';
            default -> false;
        };
    }

    private int unsigned(byte value) {
        return value & 0xFF;
    }
}

