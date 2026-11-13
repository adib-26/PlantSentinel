package com.basintelligence.scenarios;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.httpBasic;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.io.ByteArrayOutputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.Base64;

import javax.imageio.ImageIO;

import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockMultipartHttpServletRequestBuilder;

import com.basintelligence.machine.MachineService;
import com.basintelligence.machine.MachineType;

/** Documents without text, oversized and unsupported images, through MockMvc and a real HTTP server. */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureMockMvc
@ActiveProfiles("test")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class UploadValidationIntegrationTest {

    @LocalServerPort int port;
    @Autowired MockMvc mvc;
    @Autowired MachineService machines;

    @BeforeAll
    void machine() {
        machines.register(new MachineService.RegisterMachine("UPL-01", "Upload", MachineType.CNC_MILL, null), "test");
    }

    @Test
    void documentWithNoExtractableTextIsRejectedAndNothingIsStored() throws Exception {
        byte[] blankPdf;
        try (PDDocument doc = new PDDocument(); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            doc.addPage(new PDPage()); // a scanned manual without OCR looks like this: pages, no text layer
            doc.save(out);
            blankPdf = out.toByteArray();
        }
        String page = manual("scan.pdf", blankPdf).andExpect(status().isUnprocessableEntity())
                .andReturn().getResponse().getContentAsString();
        assertThat(page).contains("No extractable text", "OCR");
        manual("empty.txt", new byte[0]).andExpect(status().isUnprocessableEntity());
        manual("tool.exe", "MZ".getBytes()).andExpect(status().isUnprocessableEntity());
    }

    @Test
    void documentWhoseContentDoesNotMatchItsExtensionIsRejected() throws Exception {
        // a text file renamed to .pdf
        String page = manual("manual.pdf", "Plain text pretending to be a PDF".getBytes())
                .andExpect(status().isUnprocessableEntity()).andReturn().getResponse().getContentAsString();
        assertThat(page).contains("does not match its .pdf extension");
        // an executable renamed to .txt
        manual("notes.txt", new byte[] {'M', 'Z', (byte) 0x90, 0, 3, 0, 0, 0}).andExpect(status().isUnprocessableEntity());
        // a ZIP archive that is not a Word document, renamed to .docx
        ByteArrayOutputStream zip = new ByteArrayOutputStream();
        try (var out = new java.util.zip.ZipOutputStream(zip)) {
            out.putNextEntry(new java.util.zip.ZipEntry("payload.sh"));
            out.write("#!/bin/sh\necho hi\n".getBytes());
            out.closeEntry();
        }
        manual("manual.docx", zip.toByteArray()).andExpect(status().isUnprocessableEntity());
    }

    @Test
    void imageDeclaringHugeDimensionsIsRejectedBeforeDecoding() throws Exception {
        image("wide.png", png(9000, 40)).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.detail").value(org.hamcrest.Matchers.containsString("between 32")));
        // a PNG name on GIF bytes is judged by content
        image("dot.png", Base64.getDecoder().decode("R0lGODlhAQABAIAAAAAAAP///yH5BAEAAAAALAAAAAABAAEAAAIBRAA7"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.detail").value(org.hamcrest.Matchers.containsString("not a readable")));
    }

    @Test
    void unsupportedOrTinyImagesAreRejected() throws Exception {
        image("notes.txt", "this is not an image".getBytes()).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.detail").value(org.hamcrest.Matchers.containsString("not a readable")));
        // 1x1 GIF: a real image, but GIF is not a supported format
        image("dot.gif", Base64.getDecoder().decode("R0lGODlhAQABAIAAAAAAAP///yH5BAEAAAAALAAAAAABAAEAAAIBRAA7"))
                .andExpect(status().isBadRequest());
        image("tiny.png", png(8, 8)).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.detail").value(org.hamcrest.Matchers.containsString("between 32")));
    }

    @Test
    void imageOverTenMegabytesIsRejectedByTheServer() throws Exception {
        byte[] big = new byte[11 * 1024 * 1024];
        String boundary = "----bas" + System.nanoTime();
        ByteArrayOutputStream body = new ByteArrayOutputStream();
        body.write(("--" + boundary + "\r\nContent-Disposition: form-data; name=\"component\"\r\n\r\nhousing\r\n--" + boundary
                + "\r\nContent-Disposition: form-data; name=\"image\"; filename=\"big.png\"\r\nContent-Type: image/png\r\n\r\n").getBytes());
        body.write(big);
        body.write(("\r\n--" + boundary + "--\r\n").getBytes());
        HttpResponse<String> response = HttpClient.newHttpClient().send(HttpRequest.newBuilder(
                        URI.create("http://localhost:" + port + "/api/machines/UPL-01/inspections"))
                .header("Authorization", "Basic " + Base64.getEncoder().encodeToString("engineer:engineer-pw".getBytes()))
                .header("Content-Type", "multipart/form-data; boundary=" + boundary)
                .POST(HttpRequest.BodyPublishers.ofByteArray(body.toByteArray())).build(), HttpResponse.BodyHandlers.ofString());
        assertThat(response.statusCode()).isEqualTo(413);
        assertThat(response.body()).contains("Maximum upload size exceeded");
    }

    private org.springframework.test.web.servlet.ResultActions manual(String name, byte[] bytes) throws Exception {
        MockMultipartHttpServletRequestBuilder req = multipart("/manuals").file(new MockMultipartFile("file", name, null, bytes));
        return mvc.perform(req.with(user("engineer").roles("ENGINEER", "VIEWER")).with(csrf()));
    }

    private org.springframework.test.web.servlet.ResultActions image(String name, byte[] bytes) throws Exception {
        return mvc.perform(multipart("/api/machines/UPL-01/inspections").file(new MockMultipartFile("image", name, null, bytes))
                .param("component", "housing").with(httpBasic("engineer", "engineer-pw")));
    }

    private static byte[] png(int w, int h) throws Exception {
        var img = new java.awt.image.BufferedImage(w, h, java.awt.image.BufferedImage.TYPE_BYTE_GRAY);
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ImageIO.write(img, "png", out);
        return out.toByteArray();
    }
}
