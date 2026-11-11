package com.retryguard_ai.service;

import com.retryguard_ai.dto.AutopsyFinding;
import com.retryguard_ai.dto.InvestigationExplanationResponse;
import com.retryguard_ai.dto.InvestigationExplanationResponse.Source;
import com.retryguard_ai.entity.Investigation;
import com.retryguard_ai.exception.InvestigationNotFoundException;
import com.retryguard_ai.repository.InvestigationRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.core.JacksonException;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;

import java.util.List;
import java.util.Objects;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * Produces a deterministic, human-readable explanation of an already-final
 * investigation. The explanation is derived solely from the investigation's
 * own decision, reason, rules version, and recorded findings. It never changes
 * the deterministic decision and never authorizes a payment action.
 *
 * <p>This service previously delegated to an external AI provider (Gemini) with
 * a deterministic fallback. The external integration has been removed; the
 * deterministic explanation is now the single, authoritative output.
 */
@Service
public class InvestigationExplanationService {

    private final InvestigationRepository investigationRepository;
    private final InvestigationExplanationPersistenceService persistenceService;
    private final ObjectMapper objectMapper;

    public InvestigationExplanationService(
            InvestigationRepository investigationRepository,
            InvestigationExplanationPersistenceService persistenceService,
            ObjectMapper objectMapper) {
        this.investigationRepository = investigationRepository;
        this.persistenceService = persistenceService;
        this.objectMapper = objectMapper;
    }

    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    public InvestigationExplanationResponse explain(UUID investigationId) {
        Investigation investigation = investigationRepository.findById(investigationId)
                .orElseThrow(() -> new InvestigationNotFoundException(investigationId));

        String explanation = buildExplanation(investigation);
        persistenceService.saveExplanation(investigationId, explanation);

        return new InvestigationExplanationResponse(
                investigationId,
                investigation.getDecision().name(),
                investigation.getDecisionReason(),
                investigation.getRulesVersion(),
                explanation,
                Source.DETERMINISTIC);
    }

    private String buildExplanation(Investigation investigation) {
        String decision = investigation.getDecision().name();
        String reason = safeReason(investigation.getDecisionReason());
        String rulesVersion = safeRulesVersion(investigation.getRulesVersion());

        StringBuilder text = new StringBuilder();
        text.append("The deterministic investigation returned ")
                .append(decision)
                .append(" (")
                .append(reason)
                .append(") under rules version ")
                .append(rulesVersion)
                .append(". ");
        text.append(findingsSentence(investigation));
        text.append("This explanation is based only on the recorded evidence and "
                + "does not authorize a payment action.");
        return text.toString();
    }

    private String findingsSentence(Investigation investigation) {
        List<AutopsyFinding> findings = parseFindings(investigation);
        if (findings.isEmpty()) {
            return "No evidence-based findings were recorded. ";
        }
        String summary = findings.stream()
                .filter(Objects::nonNull)
                .map(this::describeFinding)
                .collect(Collectors.joining("; "));
        if (summary.isBlank()) {
            return "No evidence-based findings were recorded. ";
        }
        return "Evidence-based findings: " + summary + ". ";
    }

    private String describeFinding(AutopsyFinding finding) {
        String code = finding.code() == null ? "UNKNOWN" : finding.code().name();
        String severity = finding.severity() == null ? "UNKNOWN" : finding.severity().name();
        String descriptor = code + " (" + severity + ")";
        if (finding.rootCauseUncertain()) {
            descriptor += " [root cause uncertain]";
        }
        return descriptor;
    }

    private List<AutopsyFinding> parseFindings(Investigation investigation) {
        String findingsJson = investigation.getFindings();
        if (findingsJson == null || findingsJson.isBlank()) {
            return List.of();
        }
        try {
            List<AutopsyFinding> findings = objectMapper.readValue(
                    findingsJson, new TypeReference<>() { });
            return findings == null ? List.of() : findings;
        } catch (JacksonException exception) {
            // Stored findings could not be parsed; fall back to a decision-only
            // explanation rather than failing the request.
            return List.of();
        }
    }

    private String safeReason(String value) {
        if (value != null && value.matches("[A-Z0-9_]{1,60}")) {
            return value;
        }
        return "UNRECOGNIZED_REASON";
    }

    private String safeRulesVersion(String value) {
        if (value != null && value.matches("[A-Za-z0-9._-]{1,30}")) {
            return value;
        }
        return "UNRECOGNIZED";
    }
}
