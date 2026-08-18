package com.enterprisehub.rag.ingest;

import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.text.PDFTextStripper;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.Locale;

/**
 * Turns an uploaded file's raw bytes into plain text for ParagraphChunker.
 * PDF and plain text are the two formats the task asks for -- dispatch is by
 * filename extension (the only signal IngestionService's multipart upload
 * actually has; no separate content-type field on the request), not
 * sniffing file magic bytes, since a plain string check is enough for two
 * known formats and doesn't need a new dependency (Apache Tika) just for
 * type detection.
 */
public class DocumentTextExtractor {

    public String extract(byte[] content, String filename) {
        if (filename != null && filename.toLowerCase(Locale.ROOT).endsWith(".pdf")) {
            return extractPdf(content);
        }
        return new String(content, StandardCharsets.UTF_8);
    }

    private String extractPdf(byte[] content) {
        try (PDDocument document = Loader.loadPDF(content)) {
            return new PDFTextStripper().getText(document);
        } catch (IOException e) {
            throw new UncheckedIOException("Failed to extract text from PDF", e);
        }
    }
}
