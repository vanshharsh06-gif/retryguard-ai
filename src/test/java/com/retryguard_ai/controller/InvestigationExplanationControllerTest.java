package com.retryguard_ai.controller;

import com.retryguard_ai.dto.InvestigationExplanationResponse;
import com.retryguard_ai.exception.GlobalExceptionHandler;
import com.retryguard_ai.exception.InvestigationNotFoundException;
import com.retryguard_ai.service.InvestigationExplanationService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.util.UUID;

import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@ExtendWith(MockitoExtension.class)
class InvestigationExplanationControllerTest {

    @Mock private InvestigationExplanationService explanationService;
    @InjectMocks private InvestigationExplanationController controller;
    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders.standaloneSetup(controller)
                .setControllerAdvice(new GlobalExceptionHandler())
                .build();
    }

    @Test
    void returnsExplanationAndSourceWithoutChangingDecision() throws Exception {
        UUID investigationId = UUID.randomUUID();
        when(explanationService.explain(investigationId)).thenReturn(
                new InvestigationExplanationResponse(
                        investigationId, "WAIT", "INSUFFICIENT_EVIDENCE", "1.0",
                        "The evidence is incomplete.", InvestigationExplanationResponse.Source.GEMINI));

        mockMvc.perform(post("/api/investigations/{id}/explanation", investigationId))
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.decision").value("WAIT"))
                .andExpect(jsonPath("$.reason").value("INSUFFICIENT_EVIDENCE"))
                .andExpect(jsonPath("$.explanation").value("The evidence is incomplete."))
                .andExpect(jsonPath("$.source").value("GEMINI"));
    }

    @Test
    void unknownInvestigationReturnsNotFound() throws Exception {
        UUID investigationId = UUID.randomUUID();
        when(explanationService.explain(investigationId))
                .thenThrow(new InvestigationNotFoundException(investigationId));

        mockMvc.perform(post("/api/investigations/{id}/explanation", investigationId))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.title").value("Investigation not found"));
    }
}
