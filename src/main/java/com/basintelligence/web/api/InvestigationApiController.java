package com.basintelligence.web.api;

import java.util.List;

import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import com.basintelligence.investigation.IncidentReportService;
import com.basintelligence.investigation.InvestigationModels.IncidentReport;
import com.basintelligence.investigation.InvestigationModels.InvestigationView;
import com.basintelligence.investigation.InvestigationModels.Recommendation;
import com.basintelligence.investigation.InvestigationService;
import com.basintelligence.knowledge.ManualService;
import com.basintelligence.machine.MachineService;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

@RestController
@RequestMapping("/api")
@Validated
@Tag(name = "Investigations")
public class InvestigationApiController {

    public record InvestigateRequest(@Size(max = 1000) String question) {
    }

    public record ReviewRequest(@Size(max = 1000) String comment) {
    }

    private final MachineService machines;
    private final InvestigationService investigations;
    private final IncidentReportService reports;
    private final ManualService manuals;

    public InvestigationApiController(MachineService machines, InvestigationService investigations,
            IncidentReportService reports, ManualService manuals) {
        this.machines = machines;
        this.investigations = investigations;
        this.reports = reports;
        this.manuals = manuals;
    }

    @PostMapping("/machines/{code}/investigations")
    @com.basintelligence.web.RateLimited(com.basintelligence.web.RateLimited.Kind.INVESTIGATION)
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("hasRole('ENGINEER')")
    @Operation(summary = "Run a full diagnostic investigation on a machine")
    public InvestigationView investigate(@PathVariable String code, @jakarta.validation.Valid @RequestBody(required = false) InvestigateRequest body,
            Authentication auth) {
        return investigations.investigate(machines.getByCode(code).id(), body == null ? null : body.question(), auth.getName());
    }

    @GetMapping("/investigations/{id}")
    public InvestigationView get(@PathVariable long id) {
        return investigations.view(id);
    }

    @PostMapping("/recommendations/{id}/approve")
    @PreAuthorize("hasRole('ENGINEER')")
    @Operation(summary = "Approve a proposed maintenance recommendation (human in the loop)")
    public Recommendation approve(@PathVariable long id, @jakarta.validation.Valid @RequestBody(required = false) ReviewRequest body, Authentication auth) {
        return investigations.review(id, true, body == null ? null : body.comment(), auth.getName());
    }

    @PostMapping("/recommendations/{id}/reject")
    @PreAuthorize("hasRole('ENGINEER')")
    public Recommendation reject(@PathVariable long id, @jakarta.validation.Valid @RequestBody(required = false) ReviewRequest body, Authentication auth) {
        return investigations.review(id, false, body == null ? null : body.comment(), auth.getName());
    }

    @PostMapping("/investigations/{id}/report")
    @PreAuthorize("hasRole('ENGINEER')")
    @Operation(summary = "Issue the incident report (requires all recommendations reviewed)")
    public IncidentReport report(@PathVariable long id, Authentication auth) {
        return reports.generate(id, auth.getName());
    }

    @GetMapping("/manuals/search")
    @com.basintelligence.web.RateLimited(com.basintelligence.web.RateLimited.Kind.SEARCH)
    @Tag(name = "Manuals")
    @Operation(summary = "BM25 search over the maintenance manual library")
    public List<com.basintelligence.knowledge.ManualHit> search(@RequestParam @NotBlank @Size(max = 500) String q,
            @RequestParam(defaultValue = "5") @Min(1) @Max(20) int k) {
        return manuals.search(q, null, k);
    }
}
