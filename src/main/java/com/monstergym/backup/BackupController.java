package com.monstergym.backup;

import com.monstergym.backup.BackupService.BackupFile;
import com.monstergym.backup.BackupService.BackupList;
import com.monstergym.backup.BackupService.RestoreResult;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpSession;
import org.springframework.core.io.FileSystemResource;
import org.springframework.core.io.Resource;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Path;

@RestController
@RequestMapping("/api/backups")
@PreAuthorize("hasRole('ADMINISTRADOR')")
public class BackupController {

    private final BackupService service;

    public BackupController(BackupService service) {
        this.service = service;
    }

    @GetMapping
    public BackupList list() {
        return service.list();
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public BackupFile create() {
        return service.backupToday();
    }

    @GetMapping("/{name}")
    public ResponseEntity<Resource> download(@PathVariable String name) {
        Path file = service.resolve(name);
        return ResponseEntity.ok()
                .contentType(MediaType.APPLICATION_OCTET_STREAM)
                .header(HttpHeaders.CONTENT_DISPOSITION, ContentDisposition.attachment().filename(name).build().toString())
                .body(new FileSystemResource(file));
    }

    /**
     * Reemplaza todos los datos con el archivo subido (multipart: file y confirm = RESTAURAR).
     * Las sesiones pueden apuntar a empleados que ya no existen: la de quien restaura se cierra.
     */
    @PostMapping(path = "/restore", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public RestoreResult restore(@RequestPart("file") MultipartFile file,
                                 @RequestParam(required = false) String confirm,
                                 HttpServletRequest request) throws IOException {
        RestoreResult result;
        try (InputStream in = file.getInputStream()) {
            result = service.restore(in, file.getOriginalFilename(), confirm);
        }
        HttpSession session = request.getSession(false);
        if (session != null) session.invalidate();
        SecurityContextHolder.clearContext();
        return result;
    }
}
