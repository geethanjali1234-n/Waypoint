package com.waypoint.controller;

import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;

/** Builds the file response for a document request download or inline view. */
final class DocumentDownloads {
    private DocumentDownloads() {
    }

    static ResponseEntity<byte[]> of(String fileName, String contentType, byte[] content, boolean download) {
        String safeName = fileName.replace("\\", "_").replace("/", "_").replace("\"", "_")
                .replace("\r", "_").replace("\n", "_");
        boolean inlineSafe = !MediaType.APPLICATION_OCTET_STREAM_VALUE.equals(contentType);
        String disposition = (download || !inlineSafe ? "attachment" : "inline") + "; filename=\"" + safeName + "\"";
        return ResponseEntity.ok().header(HttpHeaders.CONTENT_DISPOSITION, disposition)
                .header("X-Content-Type-Options", "nosniff")
                .contentType(MediaType.parseMediaType(contentType)).contentLength(content.length)
                .body(content);
    }
}
