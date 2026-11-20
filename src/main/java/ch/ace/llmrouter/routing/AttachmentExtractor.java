package ch.ace.llmrouter.routing;

import jakarta.inject.Singleton;
import org.apache.tika.Tika;
import org.apache.tika.exception.TikaException;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Set;

/** Extracts plain text from PDF, DOCX and TXT attachments with Apache Tika. */
@Singleton
public class AttachmentExtractor {

    static final Set<String> ALLOWED_TYPES = Set.of(
            "application/pdf",
            "application/vnd.openxmlformats-officedocument.wordprocessingml.document",
            "text/plain");

    private final Tika tika = new Tika();

    public String extract(Path file, String fileName) {
        try {
            String type;
            try (InputStream in = new java.io.BufferedInputStream(Files.newInputStream(file))) {
                type = tika.detect(in, fileName);
            }
            String baseType = type.contains(";") ? type.substring(0, type.indexOf(';')).trim() : type;
            if (!ALLOWED_TYPES.contains(baseType)) {
                throw new UnsupportedAttachmentException(
                        "Unsupported attachment type " + baseType + " (" + fileName + "). Allowed: PDF, DOCX, TXT");
            }
            tika.setMaxStringLength(-1);
            try (InputStream in = Files.newInputStream(file)) {
                return tika.parseToString(in).strip();
            }
        } catch (IOException | TikaException e) {
            throw new UnsupportedAttachmentException("Cannot read attachment " + fileName + ": " + e.getMessage());
        }
    }

    public static class UnsupportedAttachmentException extends RuntimeException {
        public UnsupportedAttachmentException(String message) {
            super(message);
        }
    }
}
