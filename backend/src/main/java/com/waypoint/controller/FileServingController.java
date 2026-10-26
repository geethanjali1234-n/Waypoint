package com.waypoint.controller;

import com.waypoint.service.FileStorageService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;

/**
 * Serves profile pictures/company logos that live in shared GridFS storage,
 * under the same "/uploads/**" prefix legacy local files are served from
 * (see FileStorageConfig). Uploads made before the move to GridFS keep
 * working unmodified via the static resource handler.
 */
@RestController
@RequiredArgsConstructor
public class FileServingController {

    private final FileStorageService fileStorageService;

    @GetMapping("/uploads/gridfs/{objectId}/{fileName:.+}")
    public ResponseEntity<byte[]> getFile(@PathVariable String objectId, @PathVariable String fileName) {
        FileStorageService.StoredFile file = fileStorageService.load("gridfs/" + objectId + "/" + fileName);

        return ResponseEntity.ok()
                .contentType(file.mediaType())
                .contentLength(file.content().length)
                .body(file.content());
    }
}
