package com.basintelligence.knowledge;

import java.util.Locale;
import java.util.Map;
import java.util.Set;

import com.basintelligence.common.FileSignatures;
import com.basintelligence.common.FileSignatures.Type;
import com.basintelligence.common.Text;

/**
 * Upload rules for maintenance manuals. Three independent checks must agree before a file is parsed or stored:
 * the extension is on the allowlist, the leading bytes match that extension, and (after parsing) the type Tika
 * detected is a document type. A renamed executable, archive or script is refused.
 */
final class ManualFilePolicy {

    private static final Map<String, Type> SIGNATURE_BY_EXTENSION = Map.of(
            "pdf", Type.PDF, "docx", Type.ZIP, "doc", Type.OLE2, "txt", Type.TEXT, "md", Type.TEXT);

    private static final Set<String> DOCUMENT_TYPES = Set.of(
            "application/pdf",
            "application/vnd.openxmlformats-officedocument.wordprocessingml.document",
            "application/msword",
            "application/x-tika-msoffice",
            "text/plain",
            "text/markdown",
            "text/x-web-markdown");

    private ManualFilePolicy() {
    }

    /** @return the sanitised file name to store */
    static String checkUpload(String filename, byte[] bytes) {
        String safe = Text.safeFilename(filename);
        String ext = safe != null && safe.contains(".") ? safe.substring(safe.lastIndexOf('.') + 1).toLowerCase(Locale.ROOT) : "";
        Type expected = SIGNATURE_BY_EXTENSION.get(ext);
        if (expected == null) {
            throw new IllegalArgumentException("Upload a PDF, DOCX, DOC, TXT or MD file");
        }
        if (bytes == null || bytes.length == 0) {
            throw new IllegalArgumentException("The uploaded file is empty");
        }
        if (FileSignatures.detect(bytes) != expected) {
            throw new IllegalArgumentException("The file content does not match its ." + ext + " extension");
        }
        return safe;
    }

    static void checkDetectedType(String contentType) {
        String base = contentType == null ? "" : contentType.split(";")[0].strip().toLowerCase(Locale.ROOT);
        if (!DOCUMENT_TYPES.contains(base)) {
            throw new IllegalArgumentException("Unsupported document type: " + (base.isEmpty() ? "unknown" : base));
        }
    }
}
