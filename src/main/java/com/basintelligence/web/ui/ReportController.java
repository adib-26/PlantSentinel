package com.basintelligence.web.ui;

import java.util.Locale;
import java.util.Optional;

import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

import com.basintelligence.common.Text;
import com.basintelligence.investigation.InvestigationService;
import com.basintelligence.machine.Machine;
import com.basintelligence.machine.MachineService;

/**
 * Screen 4, Report. "Generate report" runs the built-in Java investigation pipeline on one machine (deterministic, no
 * language model): the question picks the machine and adds words to the manual search, but is not interpreted.
 * Questions in the engineer's own words go to the Kiro assistant.
 */
@Controller
public class ReportController {

    private final MachineService machines;
    private final InvestigationService investigations;

    public ReportController(MachineService machines, InvestigationService investigations) {
        this.machines = machines;
        this.investigations = investigations;
    }

    @GetMapping("/reports")
    public String reports(Model model) {
        model.addAttribute("machines", machines.list());
        return "reports";
    }

    @PostMapping("/reports")
    @com.basintelligence.web.RateLimited(com.basintelligence.web.RateLimited.Kind.INVESTIGATION)
    @PreAuthorize("hasRole('ENGINEER')")
    public String generate(@RequestParam String question, @RequestParam(required = false) String machineCode,
            Authentication auth, RedirectAttributes redirect) {
        if (question == null || question.isBlank()) {
            throw new IllegalArgumentException("Type what the report is about");
        }
        String q = Text.truncate(question.strip(), 1000);
        Machine machine = (machineCode != null && !machineCode.isBlank()) ? machines.getByCode(machineCode.strip())
                : mentioned(q).orElseThrow(() -> new IllegalArgumentException(
                        "Mention a machine code (for example CNC-01) or pick a machine"));
        long id = investigations.investigate(machine.id(), q, auth.getName()).investigation().id();
        redirect.addFlashAttribute("flash", "Report INV-" + id + " for " + machine.code() + " is ready for review.");
        return "redirect:/investigations/" + id;
    }

    /** The first registered machine code that appears in the text as a whole word. */
    Optional<Machine> mentioned(String question) {
        String upper = question.toUpperCase(Locale.ROOT);
        return machines.list().stream()
                .filter(m -> upper.matches("(?s).*(^|[^A-Z0-9-])" + java.util.regex.Pattern.quote(m.code()) + "([^A-Z0-9-]|$).*"))
                .findFirst();
    }
}
