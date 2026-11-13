package com.basintelligence.web.ui;

import java.io.IOException;
import java.util.List;

import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

import com.basintelligence.common.Text;
import com.basintelligence.knowledge.ManualService;
import com.basintelligence.machine.MachineType;

@Controller
public class ManualWebController {

    private final ManualService manuals;

    public ManualWebController(ManualService manuals) {
        this.manuals = manuals;
    }

    @GetMapping("/manuals")
    public String manuals(@RequestParam(required = false) String q, @RequestParam(required = false) MachineType type,
            Model model) {
        model.addAttribute("documents", manuals.documents());
        model.addAttribute("machineTypes", MachineType.values());
        model.addAttribute("q", q);
        model.addAttribute("type", type);
        model.addAttribute("hits", q == null || q.isBlank() ? List.of() : manuals.search(Text.truncate(q, 500), type, 8));
        model.addAttribute("chunkDocuments", manuals.chunkDocumentIds());
        model.addAttribute("retriever", manuals.retrieverName());
        model.addAttribute("citationCounts", manuals.citationCounts());
        return "manuals";
    }

    @GetMapping("/manuals/{id}")
    public String document(@PathVariable long id, Model model) {
        var citations = manuals.citations(id);
        model.addAttribute("document", manuals.document(id));
        model.addAttribute("sections", manuals.sections(id));
        model.addAttribute("citations", citations);
        model.addAttribute("citationsByChunk", citations.stream().collect(java.util.stream.Collectors.groupingBy(
                com.basintelligence.knowledge.ManualRepository.Citation::chunkId)));
        return "manual-detail";
    }

    @PostMapping("/manuals")
    @PreAuthorize("hasRole('ENGINEER')")
    public String upload(@RequestParam("file") MultipartFile file, @RequestParam(required = false) String title,
            @RequestParam(required = false) MachineType type, Authentication auth, RedirectAttributes redirect)
            throws IOException {
        if (file.isEmpty()) {
            throw new IllegalArgumentException("Upload a PDF, DOCX, DOC, TXT or MD file");
        }
        // Extension, file signature and detected document type are checked by ManualService.
        var doc = manuals.ingest(title == null ? null : Text.truncate(title, 255), file.getOriginalFilename(),
                file.getBytes(), type, auth.getName());
        redirect.addFlashAttribute("flash", "Indexed '" + doc.title() + "' (" + doc.chunkCount() + " chunks).");
        return "redirect:/manuals";
    }

    @PostMapping("/manuals/{id}/delete")
    @PreAuthorize("hasRole('ADMIN')")
    public String delete(@PathVariable long id, Authentication auth, RedirectAttributes redirect) {
        manuals.delete(id, auth.getName());
        redirect.addFlashAttribute("flash", "Manual removed from the library.");
        return "redirect:/manuals";
    }
}
