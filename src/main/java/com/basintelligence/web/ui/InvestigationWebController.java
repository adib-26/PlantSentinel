package com.basintelligence.web.ui;

import java.nio.charset.StandardCharsets;

import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

import com.basintelligence.common.NotFoundException;
import com.basintelligence.common.Text;
import com.basintelligence.investigation.IncidentReportService;
import com.basintelligence.investigation.InvestigationModels.IncidentReport;
import com.basintelligence.investigation.InvestigationModels.Priority;
import com.basintelligence.investigation.InvestigationService;
import com.basintelligence.machine.MachineService;

@Controller
public class InvestigationWebController {

    private final InvestigationService investigations;
    private final IncidentReportService reports;
    private final MachineService machines;
    private final com.basintelligence.agent.AgentAuditService agentAudit;
    private final com.basintelligence.risk.RiskService risk;
    private final com.basintelligence.knowledge.ManualService manuals;
    private final Formatters fmt;

    public InvestigationWebController(InvestigationService investigations, IncidentReportService reports,
            MachineService machines, com.basintelligence.agent.AgentAuditService agentAudit,
            com.basintelligence.risk.RiskService risk, com.basintelligence.knowledge.ManualService manuals, Formatters fmt) {
        this.fmt = fmt;
        this.investigations = investigations;
        this.reports = reports;
        this.machines = machines;
        this.agentAudit = agentAudit;
        this.risk = risk;
        this.manuals = manuals;
    }

    @GetMapping("/investigations")
    public String list(Model model) {
        model.addAttribute("investigations", investigations.recent(100));
        model.addAttribute("machineCodes", machines.list().stream()
                .collect(java.util.stream.Collectors.toMap(m -> m.id(), m -> m.code())));
        return "investigations";
    }

    @GetMapping("/investigations/{id}")
    public String detail(@PathVariable long id, Model model) {
        var view = investigations.view(id);
        var inv = view.investigation();
        String code = view.machine().code();
        java.util.Map<String, String> links = new java.util.LinkedHashMap<>();
        java.util.Map<com.basintelligence.investigation.EvidenceClass, java.util.List<com.basintelligence.investigation.InvestigationModels.Evidence>> byClass =
                new java.util.EnumMap<>(com.basintelligence.investigation.EvidenceClass.class);
        java.util.List<String> unverified = new java.util.ArrayList<>();
        for (var e : view.evidence()) {
            byClass.computeIfAbsent(e.kind().evidenceClass(), k -> new java.util.ArrayList<>()).add(e);
            links.put(e.refLabel(), switch (e.kind()) {
                case MANUAL -> e.documentId() == null ? "/manuals" : "/manuals/" + e.documentId() + "#chunk-" + e.documentChunkId();
                case RISK, MULTIVARIATE -> "/analytics?machine=" + code;
                case INSPECTION -> "/machines/" + code + "#inspections";
                case AGENT_NOTE -> "#trace";
                default -> "/machines/" + code;
            });
            if (e.source() != null && e.source().contains("unverified citations: ")) {
                unverified.addAll(java.util.List.of(e.source().substring(e.source().indexOf("unverified citations: ") + 22).split(",\\s*")));
            }
        }
        java.util.List<Long> draftCalls = view.recommendations().stream()
                .map(com.basintelligence.investigation.InvestigationModels.Recommendation::proposedViaToolCall)
                .filter(java.util.Objects::nonNull).toList();
        model.addAttribute("trace", inv.createdViaToolCall() == null ? java.util.List.of()
                : agentAudit.traceFor(inv.createdViaToolCall(), draftCalls));
        model.addAttribute("mcpNames", DashboardController.mcpNames());
        model.addAttribute("view", view);
        // Citation links are rendered with th:utext; the HTML is built (and escaped) here because Thymeleaf restricts
        // expressions in unescaped output.
        java.util.Map<String, String> cited = new java.util.HashMap<>();
        cited.put("explanation", fmt.cite(inv.explanation()));
        view.evidence().forEach(e -> cited.put(e.refLabel(), fmt.cite(e.detail())));
        view.recommendations().forEach(r -> cited.put("rec-" + r.id(), fmt.cite(r.rationale())));
        model.addAttribute("cited", cited);
        model.addAttribute("byClass", byClass);
        model.addAttribute("links", links);
        model.addAttribute("unverified", unverified);
        model.addAttribute("limitations", limitations(byClass.keySet(), view));
        model.addAttribute("priorities", Priority.values());
        return "investigation";
    }

    private java.util.List<String> limitations(java.util.Set<com.basintelligence.investigation.EvidenceClass> classes,
            com.basintelligence.investigation.InvestigationModels.InvestigationView view) {
        java.util.List<String> out = new java.util.ArrayList<>();
        out.add("The diagnosis confidence is a rule score from fault-signature rules, not a calibrated probability.");
        boolean risk = view.evidence().stream().anyMatch(e -> e.kind().name().equals("RISK"));
        if (risk) {
            out.add("Failure risk comes from " + this.risk.modelInfo().algorithm() + " trained on "
                    + this.risk.modelInfo().trainingData() + " (hold-out AUC "
                    + com.basintelligence.common.Text.num(this.risk.modelInfo().holdoutAuc(), 3) + "), not on this plant's history.");
        }
        if (view.evidence().stream().anyMatch(e -> e.kind().name().equals("MULTIVARIATE"))) {
            out.add("The Isolation Forest learns 'normal' from the earlier part of the same window, so a fault present "
                    + "throughout the window can look normal.");
        }
        if (view.evidence().stream().anyMatch(e -> e.kind().name().equals("INSPECTION"))) {
            out.add("Vision results come from a fixed-weight baseline model; confirm defects with a non-destructive test.");
        }
        if (view.evidence().stream().anyMatch(e -> e.kind().name().equals("MANUAL"))) {
            out.add("Manual passages were found by " + manuals.retrieverName() + " (keyword search), which can miss sections "
                    + "that use different words.");
        }
        if (classes.contains(com.basintelligence.investigation.EvidenceClass.AI_INTERPRETATION)) {
            out.add("The AI interpretation was written by " + com.basintelligence.agent.BasMcpTools.AGENT
                    + " and is not verified until an engineer reviews it.");
        }
        return out;
    }

    @PostMapping("/recommendations/{id}/review")
    @PreAuthorize("hasRole('ENGINEER')")
    public String review(@PathVariable long id, @RequestParam boolean approve, @RequestParam(required = false) String comment,
            @RequestParam long investigationId, @RequestParam(required = false) String returnTo, Authentication auth,
            RedirectAttributes redirect) {
        investigations.review(id, approve, comment == null ? null : Text.truncate(comment.strip(), 1000), auth.getName());
        redirect.addFlashAttribute("flash", "Recommendation " + (approve ? "approved" : "rejected")
                + (approve ? "; a planned work order was created." : "."));
        return "redirect:" + (returnTo == null ? "/investigations/" + investigationId : MaintenanceWebController.safeReturn(returnTo));
    }

    @PostMapping("/investigations/{id}/recommendations")
    @PreAuthorize("hasRole('ENGINEER')")
    public String propose(@PathVariable long id, @RequestParam String action, @RequestParam String rationale,
            @RequestParam Priority priority, Authentication auth, RedirectAttributes redirect) {
        if (action.isBlank() || rationale.isBlank()) {
            throw new IllegalArgumentException("Action and rationale are required");
        }
        investigations.propose(id, action, Text.truncate(rationale, 4000), priority, auth.getName());
        redirect.addFlashAttribute("flash", "Recommendation proposed. Another engineer must review it.");
        return "redirect:/investigations/" + id;
    }

    @PostMapping("/investigations/{id}/report")
    @PreAuthorize("hasRole('ENGINEER')")
    public String report(@PathVariable long id, Authentication auth, RedirectAttributes redirect) {
        reports.generate(id, auth.getName());
        redirect.addFlashAttribute("flash", "Incident report issued and investigation closed.");
        return "redirect:/investigations/" + id + "#report";
    }

    @GetMapping("/investigations/{id}/report.md")
    public ResponseEntity<byte[]> download(@PathVariable long id) {
        IncidentReport report = investigations.view(id).report()
                .orElseThrow(() -> new NotFoundException("No report issued for investigation " + id));
        return ResponseEntity.ok()
                .contentType(new MediaType("text", "markdown", StandardCharsets.UTF_8))
                .header(HttpHeaders.CONTENT_DISPOSITION,
                        ContentDisposition.attachment().filename("INV-" + id + "-incident-report.md").build().toString())
                .body(report.bodyMarkdown().getBytes(StandardCharsets.UTF_8));
    }
}
