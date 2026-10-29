package com.retryguard_ai.controller;

import com.retryguard_ai.dto.InvestigationResponse;
import com.retryguard_ai.dto.InvestigationResponse.AttemptEvidence;
import com.retryguard_ai.dto.InvestigationResponse.EventEvidence;
import com.retryguard_ai.dto.InvestigationResponse.EvidenceSnapshot;
import com.retryguard_ai.exception.GlobalExceptionHandler;
import com.retryguard_ai.exception.MerchantOrderNotFoundException;
import com.retryguard_ai.service.InvestigationService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

import static org.mockito.Mockito.when;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@ExtendWith(MockitoExtension.class)
class InvestigationControllerTest {

    @Mock private InvestigationService investigationService;
    @InjectMocks private InvestigationController investigationController;
    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders.standaloneSetup(investigationController)
                .setControllerAdvice(new GlobalExceptionHandler())
                .build();
    }

    @Test
    void postInvestigationReturnsDecisionAndSanitizedEvidence() throws Exception {
        UUID orderId = UUID.randomUUID();
        OffsetDateTime now = OffsetDateTime.parse("2026-10-17T10:00:00Z");
        EventEvidence event = new EventEvidence(
                UUID.randomUUID(),
                "PAYMENT.CAPTURE.DENIED",
                "PAYPAL_SANDBOX",
                "PROVIDER",
                "VERIFIED",
                now,
                now,
                now,
                "PROVIDER_CREATE_TIME",
                "FRESH",
                true);
        InvestigationResponse response = new InvestigationResponse(
                UUID.randomUUID(),
                orderId,
                "RETRY_ELIGIBLE",
                "ALL_ATTEMPTS_FRESHLY_DENIED",
                "1.0",
                "Terminal denial evidence is fresh.",
                new EvidenceSnapshot("FAILED", now, 15,
                        List.of(new AttemptEvidence(UUID.randomUUID(), "PAYPAL_SANDBOX",
                                "FAILED", List.of(event)))),
                now);
        when(investigationService.investigate(orderId)).thenReturn(response);

        mockMvc.perform(post("/api/orders/{orderId}/investigations", orderId))
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.decision").value("RETRY_ELIGIBLE"))
                .andExpect(jsonPath("$.rulesVersion").value("1.0"))
                .andExpect(jsonPath("$.evidence.freshnessThresholdMinutes").value(15))
                .andExpect(jsonPath("$.evidence.attempts[0].events[0].trustedPayPalEvidence")
                        .value(true))
                .andExpect(jsonPath("$.evidence.attempts[0].events[0].payload").doesNotExist())
                .andExpect(jsonPath("$.evidence.attempts[0].providerPaymentReference")
                        .doesNotExist());
    }

    @Test
    void postInvestigationReturnsNotFoundForUnknownOrder() throws Exception {
        UUID orderId = UUID.randomUUID();
        when(investigationService.investigate(orderId))
                .thenThrow(new MerchantOrderNotFoundException(orderId));

        mockMvc.perform(post("/api/orders/{orderId}/investigations", orderId))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.title").value("Merchant order not found"));
    }

    @Test
    void getOrderInvestigationsReturnsPersistedRunsNewestFirst() throws Exception {
        UUID orderId = UUID.randomUUID();
        InvestigationResponse response = new InvestigationResponse(
                UUID.randomUUID(), orderId, "WAIT", "NO_PAYMENT_ATTEMPTS", "1.0",
                "No attempts.", new EvidenceSnapshot("CREATED", OffsetDateTime.now(), 15, List.of()),
                List.of(), OffsetDateTime.now());
        when(investigationService.getInvestigationsForOrder(orderId)).thenReturn(List.of(response));

        mockMvc.perform(get("/api/orders/{orderId}/investigations", orderId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].decision").value("WAIT"))
                .andExpect(jsonPath("$[0].findings").isArray());
        verify(investigationService).getInvestigationsForOrder(orderId);
    }

    @Test
    void getOrderInvestigationsUsesProblemDetailsForUnknownOrder() throws Exception {
        UUID orderId = UUID.randomUUID();
        when(investigationService.getInvestigationsForOrder(orderId))
                .thenThrow(new MerchantOrderNotFoundException(orderId));

        mockMvc.perform(get("/api/orders/{orderId}/investigations", orderId))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.title").value("Merchant order not found"));
    }
}
