package com.retryguard_ai.controller;

import com.retryguard_ai.dto.InvestigationHistoryResponse;
import com.retryguard_ai.dto.InvestigationResponse;
import com.retryguard_ai.dto.InvestigationResponse.EvidenceSnapshot;
import com.retryguard_ai.exception.GlobalExceptionHandler;
import com.retryguard_ai.exception.InvestigationNotFoundException;
import com.retryguard_ai.service.InvestigationService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@ExtendWith(MockitoExtension.class)
class InvestigationQueryControllerTest {

    @Mock private InvestigationService investigationService;
    @InjectMocks private InvestigationQueryController controller;
    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders.standaloneSetup(controller)
                .setControllerAdvice(new GlobalExceptionHandler())
                .build();
    }

    @Test
    void retrievesInvestigationWithFindings() throws Exception {
        UUID id = UUID.randomUUID();
        when(investigationService.getInvestigation(id)).thenReturn(
                new InvestigationResponse(id, UUID.randomUUID(), "WAIT", "NO_PAYMENT_ATTEMPTS",
                        "1.0", "No attempts.", new EvidenceSnapshot(
                        "CREATED", OffsetDateTime.now(), 15, List.of()), List.of(),
                        OffsetDateTime.now()));

        mockMvc.perform(get("/api/investigations/{id}", id))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(id.toString()))
                .andExpect(jsonPath("$.findings").isArray());
    }

    @Test
    void historyIsReturnedAsAnOrderedStableDtoAndMissingInvestigationIs404() throws Exception {
        UUID id = UUID.randomUUID();
        OffsetDateTime first = OffsetDateTime.parse("2026-10-23T10:00:00Z");
        when(investigationService.getInvestigationHistory(id)).thenReturn(List.of(
                new InvestigationHistoryResponse(UUID.randomUUID(), id, "CREATED", null,
                        "WAIT", "FIRST", first),
                new InvestigationHistoryResponse(UUID.randomUUID(), id, "CREATED", "WAIT",
                        "RECONCILE", "SECOND", first.plusMinutes(1))));

        mockMvc.perform(get("/api/investigations/{id}/history", id))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].reason").value("FIRST"))
                .andExpect(jsonPath("$[1].reason").value("SECOND"));

        UUID missing = UUID.randomUUID();
        when(investigationService.getInvestigation(missing))
                .thenThrow(new InvestigationNotFoundException(missing));
        mockMvc.perform(get("/api/investigations/{id}", missing))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.title").value("Investigation not found"));

        when(investigationService.getInvestigationHistory(missing))
                .thenThrow(new InvestigationNotFoundException(missing));
        mockMvc.perform(get("/api/investigations/{id}/history", missing))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.title").value("Investigation not found"));
    }
}
