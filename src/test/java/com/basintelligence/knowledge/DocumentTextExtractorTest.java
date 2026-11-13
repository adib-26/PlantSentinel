package com.basintelligence.knowledge;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.ByteArrayOutputStream;

import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.apache.pdfbox.pdmodel.font.Standard14Fonts;
import org.junit.jupiter.api.Test;

import com.basintelligence.machine.MachineType;

/** Real Tika + PDFBox round trip: a two-page PDF manual yields chunks and citations with page numbers. */
class DocumentTextExtractorTest {

    @Test
    void pdfPagesAreCarriedIntoChunkCitations() throws Exception {
        byte[] pdf = pdf("3. SPINDLE BEARINGS", "Rising vibration at the drive end indicates bearing wear.",
                "4. COOLING", "Clean the chiller condenser fins monthly.");

        var extracted = new DocumentTextExtractor().extract(pdf, "manual.pdf");
        var chunks = new TextChunker(900).chunk(extracted.text());

        assertThat(extracted.contentType()).contains("pdf");
        assertThat(chunks).hasSize(2);
        assertThat(chunks.get(0).section()).isEqualTo("3. SPINDLE BEARINGS");
        assertThat(chunks.get(0).pageStart()).isEqualTo(1);
        assertThat(chunks.get(1).section()).isEqualTo("4. COOLING");
        assertThat(chunks.get(1).pageStart()).isEqualTo(2);
        var hit = new ManualHit(1, "CNC Manual", chunks.get(1).section(), chunks.get(1).content(), MachineType.CNC_MILL, 1.0,
                chunks.get(1).pageStart(), chunks.get(1).pageEnd());
        assertThat(hit.citation()).isEqualTo("CNC Manual › 4. COOLING, p. 2");
    }

    private static byte[] pdf(String h1, String p1, String h2, String p2) throws Exception {
        try (PDDocument doc = new PDDocument(); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            for (String[] page : new String[][] {{h1, p1}, {h2, p2}}) {
                PDPage pdPage = new PDPage();
                doc.addPage(pdPage);
                try (PDPageContentStream cs = new PDPageContentStream(doc, pdPage)) {
                    cs.beginText();
                    cs.setFont(new PDType1Font(Standard14Fonts.FontName.HELVETICA), 12);
                    cs.newLineAtOffset(72, 700);
                    cs.showText(page[0]);
                    cs.newLineAtOffset(0, -40);
                    cs.showText(page[1]);
                    cs.endText();
                }
            }
            doc.save(out);
            return out.toByteArray();
        }
    }
}
