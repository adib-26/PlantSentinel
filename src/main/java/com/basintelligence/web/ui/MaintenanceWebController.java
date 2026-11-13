package com.basintelligence.web.ui;

import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

import com.basintelligence.common.AuditService;
import com.basintelligence.common.Text;
import com.basintelligence.investigation.InvestigationService;
import com.basintelligence.machine.Machine;
import com.basintelligence.machine.MachineService;
import com.basintelligence.maintenance.MaintenanceService;

/** Screen 5, maintenance management: approvals, recommendations, work orders, technicians, reports, audit trail. */
@Controller
public class MaintenanceWebController {

    private static final Set<String> TRAIL_PREFIXES = Set.of("RECOMMENDATION_", "MAINTENANCE_", "REPORT_",
            "INTERPRETATION_", "INVESTIGATION_");

    private final InvestigationService investigations;
    private final MaintenanceService maintenance;
    private final MachineService machines;
    private final AuditService audit;
    private final com.basintelligence.failure.FailureEventService failures;

    public MaintenanceWebController(InvestigationService investigations, MaintenanceService maintenance,
            MachineService machines, AuditService audit, com.basintelligence.failure.FailureEventService failures) {
        this.failures = failures;
        this.investigations = investigations;
        this.maintenance = maintenance;
        this.machines = machines;
        this.audit = audit;
    }

    @GetMapping("/maintenance")
    public String maintenance(Model model) {
        var pending = investigations.recommendationRows(true, 50);
        var recommendations = investigations.recommendationRows(false, 50);
        var workOrders = maintenance.recent(50);
        var reports = investigations.reports(30);
        model.addAttribute("pending", pending);
        model.addAttribute("recommendations", recommendations);
        model.addAttribute("workOrders", workOrders);
        model.addAttribute("technicians", maintenance.technicians());
        model.addAttribute("failureModes", com.basintelligence.failure.FailureEventService.FailureMode.values());
        model.addAttribute("machineCodes", machines.list().stream().collect(Collectors.toMap(Machine::id, Machine::code)));
        model.addAttribute("reports", reports);
        model.addAttribute("approvals", investigations.approvals(50));
        // The trail shows only entries about records the user can see (all of them for administrators).
        Set<String> visibleTargets = new java.util.HashSet<>();
        recommendations.forEach(r -> {
            visibleTargets.add("recommendation:" + r.recommendation().id());
            visibleTargets.add("investigation:" + r.investigationId());
        });
        workOrders.forEach(w -> visibleTargets.add("maintenance:" + w.id()));
        reports.forEach(r -> visibleTargets.add("investigation:" + r.investigationId()));
        boolean all = machines.visible().all();
        model.addAttribute("trail", audit.recent(500).stream()
                .filter(e -> TRAIL_PREFIXES.stream().anyMatch(p -> e.action().startsWith(p)))
                .filter(e -> all || visibleTargets.contains(e.target()))
                .limit(100).toList());
        return "maintenance";
    }

    @PostMapping("/maintenance/{id}/assign")
    @PreAuthorize("hasRole('ENGINEER')")
    public String assign(@PathVariable long id, @RequestParam String technician, Authentication auth,
            RedirectAttributes redirect) {
        maintenance.assign(id, technician.strip(), auth.getName());
        redirect.addFlashAttribute("flash", "Work order #" + id + " assigned to " + technician + ".");
        return "redirect:/maintenance#work-orders";
    }

    @PostMapping("/maintenance/{id}/complete")
    @PreAuthorize("hasAnyRole('ENGINEER', 'TECHNICIAN')")
    public String complete(@PathVariable long id, @RequestParam(required = false) String notes,
            @RequestParam(defaultValue = "/maintenance") String returnTo,
            @RequestParam(required = false) com.basintelligence.failure.FailureEventService.FailureMode confirmedFailure,
            Authentication auth, RedirectAttributes redirect) {
        boolean engineer = auth.getAuthorities().stream().anyMatch(a -> a.getAuthority().equals("ROLE_ENGINEER"));
        var record = maintenance.complete(id, notes == null ? null : Text.truncate(notes.strip(), 2000), auth.getName(), engineer);
        if (confirmedFailure != null) {
            failures.confirm(record.machineId(), confirmedFailure,
                    notes == null || notes.isBlank() ? "Confirmed during work order #" + id : notes, null, id, auth.getName());
        }
        redirect.addFlashAttribute("flash", "Work order #" + id + " recorded as completed"
                + (confirmedFailure != null ? "; " + confirmedFailure + " failure confirmed." : "."));
        return "redirect:" + safeReturn(returnTo);
    }

    /** Only same-site paths for known screens, to avoid an open redirect. */
    static String safeReturn(String returnTo) {
        return returnTo != null && returnTo.matches("/(maintenance|machines/[A-Za-z0-9-]+|investigations/\\d+)(#[a-z-]+)?")
                ? returnTo : "/maintenance";
    }

    static List<String> trailPrefixes() {
        return List.copyOf(TRAIL_PREFIXES);
    }
}
