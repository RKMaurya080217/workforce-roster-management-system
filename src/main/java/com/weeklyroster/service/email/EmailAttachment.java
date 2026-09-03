package com.weeklyroster.service.email;

import java.util.Base64;

/**
 * Immutable email attachment container supporting binary payload conversion to Base64 for REST APIs.
 */
public class EmailAttachment {
    private final String filename;
    private final byte[] data;
    private final String contentType;

    public EmailAttachment(String filename, byte[] data, String contentType) {
        this.filename = filename;
        this.data = data != null ? data : new byte[0];
        this.contentType = contentType != null ? contentType : "application/octet-stream";
    }

    public String getFilename() {
        return filename;
    }

    public byte[] getData() {
        return data;
    }

    public String getContentType() {
        return contentType;
    }

    public String getBase64Content() {
        return Base64.getEncoder().encodeToString(data);
    }
}
