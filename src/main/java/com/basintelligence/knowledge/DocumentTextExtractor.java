package com.basintelligence.knowledge;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;

import org.apache.tika.exception.TikaException;
import org.apache.tika.metadata.Metadata;
import org.apache.tika.metadata.TikaCoreProperties;
import org.apache.tika.parser.AutoDetectParser;
import org.apache.tika.parser.ParseContext;
import org.apache.tika.sax.BodyContentHandler;
import org.apache.tika.sax.ContentHandlerDecorator;
import org.xml.sax.Attributes;
import org.springframework.stereotype.Component;
import org.xml.sax.SAXException;

/**
 * Apache Tika text extraction for PDF, DOCX, Markdown and plain text manuals. For PDFs, Tika wraps each page in
 * {@code <div class="page">}; a form feed is written at every page start so the chunker can record page numbers.
 */
@Component
public class DocumentTextExtractor {

    /** Upper bound on extracted characters, guarding against decompression bombs. */
    private static final int MAX_CHARS = 2_000_000;

    /** Marks the start of a PDF page in the extracted text. */
    public static final char PAGE_BREAK = '\f';

    private final AutoDetectParser parser = new AutoDetectParser();

    public record Extracted(String text, String contentType) {
    }

    public Extracted extract(byte[] bytes, String filename) {
        Metadata metadata = new Metadata();
        metadata.set(TikaCoreProperties.RESOURCE_NAME_KEY, filename);
        BodyContentHandler handler = new BodyContentHandler(MAX_CHARS);
        ContentHandlerDecorator pages = new ContentHandlerDecorator(handler) {
            @Override
            public void startElement(String uri, String localName, String name, Attributes atts)
                    throws org.xml.sax.SAXException {
                if ("div".equals(localName) && "page".equals(atts.getValue("class"))) {
                    super.characters(new char[] {PAGE_BREAK}, 0, 1);
                }
                super.startElement(uri, localName, name, atts);
            }
        };
        try (InputStream in = new ByteArrayInputStream(bytes)) {
            parser.parse(in, pages, metadata, new ParseContext());
        } catch (IOException | SAXException | TikaException e) {
            throw new IllegalArgumentException("Could not extract text from " + filename + ": " + e.getMessage(), e);
        }
        String text = handler.toString().replace("\r\n", "\n");
        if (text.replace(String.valueOf(PAGE_BREAK), "").isBlank()) {
            throw new IllegalArgumentException("No extractable text in " + filename + " (scanned PDFs need OCR first)");
        }
        return new Extracted(text, metadata.get(Metadata.CONTENT_TYPE));
    }
}
