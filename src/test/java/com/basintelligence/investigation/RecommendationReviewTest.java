package com.basintelligence.investigation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import com.basintelligence.common.AuditService;
import com.basintelligence.common.InvalidStateException;
import com.basintelligence.investigation.InvestigationModels.Investigation;
import com.basintelligence.investigation.InvestigationModels.Priority;
import com.basintelligence.investigation.InvestigationModels.Recommendation;
import com.basintelligence.investigation.InvestigationModels.RecommendationStatus;
import com.basintelligence.investigation.InvestigationModels.Status;
import com.basintelligence.knowledge.ManualService;
import com.basintelligence.maintenance.MaintenanceService;
import com.basintelligence.risk.PredictionService;
import com.basintelligence.machine.MachineService;
import com.basintelligence.risk.RiskService;
import com.basintelligence.telemetry.TelemetryService;
import com.basintelligence.vision.InspectionService;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;

/** Human-in-the-loop rules of the recommendation workflow, with persistence mocked out. */
@ExtendWith(MockitoExtension.class)
class RecommendationReviewTest {

    private static final java.util.UUID MACHINE = java.util.UUID.randomUUID();
    private static final Instant NOW = Instant.parse("2026-11-12T08:00:00Z");

    @Mock MachineService machines;
    @Mock TelemetryService telemetry;
    @Mock RiskService risk;
    @Mock InspectionService inspections;
    @Mock ManualService manuals;
    @Mock InvestigationRepository repository;
    @Mock AuditService audit;
    @Mock PredictionService predictions;
    @Mock MaintenanceService maintenance;

    private InvestigationService service;

    @BeforeEach
    void setUp() {
        service = new InvestigationService(machines, telemetry, risk, inspections, manuals, repository, audit, null,
                new SimpleMeterRegistry(), predictions, maintenance, Clock.fixed(NOW, ZoneOffset.UTC));
    }

    @Test
    void engineerApprovesSystemProposalAndInvestigationBecomesReviewed() {
        Recommendation proposed = rec(RecommendationStatus.PROPOSED, "bas-diagnostics");
        Recommendation approved = rec(RecommendationStatus.APPROVED, "bas-diagnostics");
        when(repository.findRecommendation(1)).thenReturn(Optional.of(proposed), Optional.of(approved));
        when(repository.findById(10)).thenReturn(Optional.of(investigation(Status.OPEN)));
        when(repository.review(eq(1L), eq(RecommendationStatus.APPROVED), eq("bas-diagnostics"), eq("engineer"), any(), eq(NOW)))
                .thenReturn(true);
        when(repository.findRecommendations(10)).thenReturn(List.of(approved));

        Recommendation result = service.review(1, true, "ok", "engineer");

        assertThat(result.status()).isEqualTo(RecommendationStatus.APPROVED);
        verify(repository).updateStatus(10, Status.REVIEWED, null);
        verify(maintenance).plan(MACHINE, 1, "Replace bearing", "HIGH", "engineer");
        verify(audit).record(eq("engineer"), eq("RECOMMENDATION_APPROVED"), eq("recommendation:1"), eq("ok"));
    }

    @Test
    void proposerCannotReviewOwnRecommendation() {
        when(repository.findRecommendation(1)).thenReturn(Optional.of(rec(RecommendationStatus.PROPOSED, "engineer")));
        when(repository.findById(10)).thenReturn(Optional.of(investigation(Status.OPEN)));

        assertThatThrownBy(() -> service.review(1, true, null, "engineer"))
                .isInstanceOf(InvalidStateException.class)
                .hasMessageContaining("cannot be reviewed by the person who proposed it");
        verify(repository, never()).review(anyLong(), any(), anyString(), anyString(), any(), any());
    }

    @Test
    void alreadyReviewedRecommendationIsRejected() {
        when(repository.findRecommendation(1)).thenReturn(Optional.of(rec(RecommendationStatus.APPROVED, "kiro-agent")));
        when(repository.findById(10)).thenReturn(Optional.of(investigation(Status.REVIEWED)));
        when(repository.review(anyLong(), any(), anyString(), anyString(), any(), any())).thenReturn(false);

        assertThatThrownBy(() -> service.review(1, false, null, "engineer"))
                .isInstanceOf(InvalidStateException.class)
                .hasMessageContaining("already been reviewed");
    }

    @Test
    void closedInvestigationAcceptsNoNewProposals() {
        when(repository.findById(10)).thenReturn(Optional.of(investigation(Status.CLOSED)));

        assertThatThrownBy(() -> service.propose(10, "Replace bearing", "E2", Priority.HIGH, "kiro-agent"))
                .isInstanceOf(InvalidStateException.class);
    }

    @Test
    void newProposalReopensAReviewedInvestigation() {
        when(repository.findById(10)).thenReturn(Optional.of(investigation(Status.REVIEWED)));
        when(repository.findEvidence(10)).thenReturn(List.of(evidence("E2"), evidence("E4")));
        when(repository.insertRecommendation(eq(10L), anyString(), anyString(), eq(Priority.HIGH), eq("kiro-agent"), eq(NOW), isNull()))
                .thenReturn(2L);
        when(repository.findRecommendation(2)).thenReturn(Optional.of(rec(RecommendationStatus.PROPOSED, "kiro-agent")));

        service.propose(10, "Reduce spindle speed", "E2, E4", Priority.HIGH, "kiro-agent");

        verify(repository).updateStatus(10, Status.OPEN, null);
    }

    @Test
    void agentProposalMustCiteExistingEvidence() {
        when(repository.findById(10)).thenReturn(Optional.of(investigation(Status.OPEN)));
        when(repository.findEvidence(10)).thenReturn(List.of(evidence("E1")));

        assertThatThrownBy(() -> service.propose(10, "Replace bearing", "Trust me", Priority.HIGH, "kiro-agent"))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("cite existing evidence");
        assertThatThrownBy(() -> service.propose(10, "Replace bearing", "See E9", Priority.HIGH, "kiro-agent"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void agentCannotFloodTheReviewQueue() {
        when(repository.findById(10)).thenReturn(Optional.of(investigation(Status.OPEN)));
        when(repository.findEvidence(10)).thenReturn(List.of(evidence("E1")));
        when(repository.findRecommendations(10)).thenReturn(List.of(rec(RecommendationStatus.PROPOSED, "kiro-agent"),
                rec(RecommendationStatus.PROPOSED, "kiro-agent"), rec(RecommendationStatus.PROPOSED, "kiro-agent")));

        assertThatThrownBy(() -> service.propose(10, "Another action", "E1", Priority.LOW, "kiro-agent"))
                .isInstanceOf(InvalidStateException.class).hasMessageContaining("open proposals");
    }

    private static InvestigationModels.Evidence evidence(String label) {
        return new InvestigationModels.Evidence(label, InvestigationModels.EvidenceKind.TELEMETRY, "t", "d", "s");
    }

    private static Recommendation rec(RecommendationStatus status, String proposer) {
        return new Recommendation(1, 10, "Replace bearing", "E2", Priority.HIGH, status, proposer, NOW, null, null, null, null);
    }

    private static Investigation investigation(Status status) {
        return new Investigation(10, MACHINE, null, status, 0.8, null, "Bearing wear", "...", "engineer", NOW, null, null);
    }
}
