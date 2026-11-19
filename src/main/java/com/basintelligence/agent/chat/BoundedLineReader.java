package com.basintelligence.agent.chat;

import java.io.IOException;
import java.io.Reader;

/**
 * Reads lines without trusting their length: a line longer than {@code maxLine} characters is skipped instead of
 * being held in memory. Plain class.
 */
final class BoundedLineReader {

    private final Reader in;
    private final int maxLine;
    private long charsRead;

    BoundedLineReader(Reader in, int maxLine) {
        this.in = in;
        this.maxLine = maxLine;
    }

    /** The next line that fits, without its line break; null at the end of the stream. */
    String next() throws IOException {
        StringBuilder line = new StringBuilder();
        boolean tooLong = false;
        int c;
        while ((c = in.read()) != -1) {
            charsRead++;
            if (c == '\n') {
                if (!tooLong) {
                    return line.toString();
                }
                line.setLength(0);
                tooLong = false;
                continue;
            }
            if (tooLong) {
                continue;
            }
            if (line.length() >= maxLine) {
                tooLong = true;
                line.setLength(0);
                continue;
            }
            line.append((char) c);
        }
        return line.isEmpty() || tooLong ? null : line.toString();
    }

    /** Every character read so far, including skipped lines. */
    long charsRead() {
        return charsRead;
    }
}
