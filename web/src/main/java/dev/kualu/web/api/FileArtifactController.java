package dev.kualu.web.api;

import dev.kualu.web.files.FileArtifactStore;
import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.io.FileSystemResource;
import org.springframework.core.io.Resource;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.util.UriUtils;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Streams tool-generated Office files: {@code GET /api/v1/files/<relative-name>}.
 *
 * <p>Authentication is whatever {@code /api/v1/**} already requires (JWT via
 * {@code Authorization} header or {@code ?token=}, same as the ASR handshake),
 * so links returned by the office-mcp tools work as-is in the chat UI. The
 * response is always an attachment with the original file name.
 */
@RestController
@RequestMapping("/api/v1/files")
public class FileArtifactController {

    private static final Logger log = LoggerFactory.getLogger(FileArtifactController.class);

    private static final String PREFIX = "/api/v1/files/";
    private static final MediaType OCTET_STREAM = MediaType.parseMediaType("application/octet-stream");

    private final FileArtifactStore store;

    public FileArtifactController(FileArtifactStore store) {
        this.store = store;
    }

    @GetMapping("/**")
    public ResponseEntity<Resource> download(HttpServletRequest request) {
        String uri = request.getRequestURI();
        if (!uri.startsWith(PREFIX) || uri.length() == PREFIX.length()) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "not found");
        }
        String relative = UriUtils.decode(uri.substring(PREFIX.length()), StandardCharsets.UTF_8);
        Path file;
        try {
            file = store.resolve(relative);
        } catch (IllegalArgumentException e) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, e.getMessage());
        }
        if (!Files.isRegularFile(file)) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "not found");
        }
        String fileName = file.getFileName().toString();
        ContentDisposition disposition = ContentDisposition.attachment()
                .filename(fileName, StandardCharsets.UTF_8)
                .build();
        try {
            return ResponseEntity.ok()
                    .contentType(contentType(file))
                    .contentLength(Files.size(file))
                    .header(HttpHeaders.CONTENT_DISPOSITION, disposition.toString())
                    .body(new FileSystemResource(file));
        } catch (java.io.IOException e) {
            log.warn("file download failed for {}: {}", fileName, e.toString());
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "not found");
        }
    }

    private static MediaType contentType(Path file) {
        try {
            String probed = Files.probeContentType(file);
            return probed == null ? OCTET_STREAM : MediaType.parseMediaType(probed);
        } catch (Exception e) {
            return OCTET_STREAM;
        }
    }
}
