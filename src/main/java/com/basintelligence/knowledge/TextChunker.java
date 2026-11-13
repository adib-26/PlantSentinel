package com.basintelligence.knowledge;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Structure-aware chunking. Markdown headings (and short ALL-CAPS or numbered heading lines, which is how
 * headings usually survive PDF extraction) start a new section. Paragraphs are packed into chunks of up to
 * {@code maxChars}. Each chunk records its section heading and, for PDFs, the page range it came from, so retrieved
 * evidence can be cited as "document › section, p. 4".
 */
public final class TextChunker {

    private static final Pattern MARKDOWN_HEADING = Pattern.compile("^#{1,6}\\s+(.+)$");
    private static final Pattern NUMBERED_HEADING = Pattern.compile("^(\\d+(\\.\\d+)*)\\.?\\s+[A-Z][^.!?]{2,80}$");

    /** {@code pageStart} and {@code pageEnd} are null when the source has no pages (DOCX, Markdown, text). */
    public record Chunk(int index, String section, String content, Integer pageStart, Integer pageEnd) {

        public Chunk(int index, String section, String content) {
            this(index, section, content, null, null);
        }
    }

    private final int maxChars;

    public TextChunker(int maxChars) {
        this.maxChars = maxChars;
    }

    public List<Chunk> chunk(String text) {
        List<Chunk> chunks = new ArrayList<>();
        Builder current = new Builder();
        String section = null;
        String[] pages = text.split(String.valueOf(DocumentTextExtractor.PAGE_BREAK), -1);
        boolean paged = pages.length > 1;
        for (int p = 0; p < pages.length; p++) {
            Integer page = paged ? Math.max(1, p) : null; // text before the first page marker counts as page 1
            for (String block : pages[p].split("\\n\\s*\\n")) {
                String paragraph = block.strip();
                if (paragraph.isEmpty()) {
                    continue;
                }
                String firstLine = paragraph.lines().findFirst().orElse("");
                String heading = heading(firstLine);
                if (heading != null) {
                    current.flushInto(chunks, section);
                    section = heading;
                    paragraph = paragraph.substring(firstLine.length()).strip();
                    if (paragraph.isEmpty()) {
                        continue;
                    }
                }
                for (String piece : split(paragraph)) {
                    if (current.length() > 0 && current.length() + piece.length() + 2 > maxChars) {
                        current.flushInto(chunks, section);
                    }
                    current.append(piece, page);
                }
            }
        }
        current.flushInto(chunks, section);
        return chunks;
    }

    private static String heading(String line) {
        Matcher md = MARKDOWN_HEADING.matcher(line.strip());
        if (md.matches()) {
            return md.group(1).strip();
        }
        String s = line.strip();
        if (NUMBERED_HEADING.matcher(s).matches()) {
            return s;
        }
        if (s.length() >= 4 && s.length() <= 60 && s.equals(s.toUpperCase()) && s.chars().anyMatch(Character::isLetter)
                && !s.endsWith(".")) {
            return s;
        }
        return null;
    }

    /** Splits an over-long paragraph on sentence boundaries. */
    private List<String> split(String paragraph) {
        if (paragraph.length() <= maxChars) {
            return List.of(paragraph);
        }
        List<String> parts = new ArrayList<>();
        StringBuilder sb = new StringBuilder();
        for (String sentence : paragraph.split("(?<=[.!?])\\s+")) {
            if (sb.length() > 0 && sb.length() + sentence.length() + 1 > maxChars) {
                parts.add(sb.toString());
                sb.setLength(0);
            }
            if (sentence.length() > maxChars) {
                for (int i = 0; i < sentence.length(); i += maxChars) {
                    parts.add(sentence.substring(i, Math.min(sentence.length(), i + maxChars)));
                }
                continue;
            }
            if (sb.length() > 0) {
                sb.append(' ');
            }
            sb.append(sentence);
        }
        if (sb.length() > 0) {
            parts.add(sb.toString());
        }
        return parts;
    }

    private static final class Builder {
        private final StringBuilder text = new StringBuilder();
        private Integer firstPage;
        private Integer lastPage;

        int length() {
            return text.length();
        }

        void append(String piece, Integer page) {
            if (text.length() > 0) {
                text.append("\n\n");
            }
            text.append(piece);
            if (page != null) {
                firstPage = firstPage == null ? page : Math.min(firstPage, page);
                lastPage = lastPage == null ? page : Math.max(lastPage, page);
            }
        }

        void flushInto(List<Chunk> chunks, String section) {
            if (text.length() > 0) {
                chunks.add(new Chunk(chunks.size(), section, text.toString(), firstPage, lastPage));
                text.setLength(0);
                firstPage = null;
                lastPage = null;
            }
        }
    }
}
