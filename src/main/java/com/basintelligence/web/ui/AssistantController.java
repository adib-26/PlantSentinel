package com.basintelligence.web.ui;

import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.stream.Collectors;

import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

import com.basintelligence.agent.AgentAuditService;
import com.basintelligence.agent.BasMcpTools;
import com.basintelligence.common.Text;
import com.basintelligence.investigation.InvestigationService;
import com.basintelligence.machine.Machine;
import com.basintelligence.machine.MachineService;

/**
 * Screen 4, the Kiro assistant. Questions typed here run the built-in Java investigation pipeline (deterministic,
 * no language model). Questions asked in Kiro run the same tools over MCP and add an AI interpretation. Both kinds
 * appear in one history, each opening the investigation page with its evidence, trace and citations.
 */
@Controller
public class AssistantController {

    private final MachineService machines;
    private final InvestigationService investigations;
    private final AgentAuditService agentAudit;

    public AssistantController(MachineService machines, InvestigationService investigations, AgentAuditService agentAudit) {
        this.machines = machines;
        this.investigations = investigations;
        this.agentAudit = agentAudit;
    }

    @GetMapping("/assistant")
    public String assistant(Model model) {
        List<Machine> all = machines.list();
        model.addAttribute("machines", all);
        model.addAttribute("machineCodes", all.stream().collect(Collectors.toMap(Machine::id, Machine::code)));
        model.addAttribute("history", investigations.recent(30));
        model.addAttribute("agentName", BasMcpTools.AGENT);
        model.addAttribute("sessions", agentAudit.recentSessions(5));
        model.addAttribute("examples", List.of(
                "Why is CNC-01 showing a high failure risk?",
                "Is COMP-03 overheating, and what should we check first?",
                "Is PUMP-02 healthy?"));
        return "assistant";
    }

    @PostMapping("/assistant/ask")
    @com.basintelligence.web.RateLimited(com.basintelligence.web.RateLimited.Kind.INVESTIGATION)
    @PreAuthorize("hasRole('ENGINEER')")
    public String ask(@RequestParam String question, @RequestParam(required = false) String machineCode,
            Authentication auth, RedirectAttributes redirect) {
        if (question == null || question.isBlank()) {
            throw new IllegalArgumentException("Type a question");
        }
        String q = Text.truncate(question.strip(), 1000);
        Machine machine = (machineCode != null && !machineCode.isBlank()) ? machines.getByCode(machineCode.strip())
                : mentioned(q).orElseThrow(() -> new IllegalArgumentException(
                        "Mention a machine code in the question (for example CNC-01) or pick a machine"));
        long id = investigations.investigate(machine.id(), q, auth.getName()).investigation().id();
        redirect.addFlashAttribute("flash", "Investigation INV-" + id + " for " + machine.code() + " is ready for review.");
        return "redirect:/investigations/" + id;
    }

    /** The first registered machine code that appears in the question as a whole word. */
    Optional<Machine> mentioned(String question) {
        String upper = question.toUpperCase(Locale.ROOT);
        return machines.list().stream()
                .filter(m -> upper.matches("(?s).*(^|[^A-Z0-9-])" + java.util.regex.Pattern.quote(m.code()) + "([^A-Z0-9-]|$).*"))
                .findFirst();
    }
}
