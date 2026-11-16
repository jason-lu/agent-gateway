package dev.kualu.web.api;

import dev.kualu.web.files.ArtifactNames;
import dev.kualu.web.files.ArtifactStore;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.core.io.InputStreamResource;
import org.springframework.core.io.Resource;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.MediaTypeFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.util.UriUtils;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;

/**
 * Streams tool-generated Office files: {@code GET /api/v1/files/<relative-name>}.
 *
 * <p>Authentication is whatever {@code /api/v1/**} already requires (JWT via
 * {@code Authorization} header or {@code ?token=}, same as the ASR handshake),
 * so links returned by the office-mcp tools work as-is in the chat UI. The
 * response is always an attachment with the original file name. The bytes come
 * from the configured {@link ArtifactStore} (local disk or OSS proxy).
 */
@RestController
@RequestMapping("/api/v1/files")
public class FileArtifactController {

    private static final String PREFIX = "/api/v1/files/";
    private static final MediaType OCTET_STREAM = MediaType.parseMediaType("application/octet-stream");

    private final ArtifactStore store;

    public FileArtifactController(ArtifactStore store) {
        this.store = store;
    }

    @GetMapping("/**")
    public ResponseEntity<Resource> download(HttpServletRequest request) {
        String uri = request.getRequestURI();
        if (!uri.startsWith(PREFIX) || uri.length() == PREFIX.length()) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "not found");
        }
        String relative = UriUtils.decode(uri.substring(PREFIX.length()), StandardCharsets.UTF_8);
        String name;
        try {
            name = ArtifactNames.normalize(relative);
        } catch (IllegalArgumentException e) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, e.getMessage());
        }
        String fileName = ArtifactNames.fileName(name);
        long size;
        InputStream body;
        try {
            if (!store.exists(name)) {
                throw new ResponseStatusException(HttpStatus.NOT_FOUND, "not found");
            }
            size = store.size(name);
            body = store.open(name);
        } catch (IOException e) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "not found");
        }
        ContentDisposition disposition = ContentDisposition.attachment()
                .filename(fileName, StandardCharsets.UTF_8)
                .build();
        return ResponseEntity.ok()
                .contentType(MediaTypeFactory.getMediaType(fileName).orElse(OCTET_STREAM))
                .contentLength(size)
                .header(HttpHeaders.CONTENT_DISPOSITION, disposition.toString())
                .body(new InputStreamResource(body));
    }
}
