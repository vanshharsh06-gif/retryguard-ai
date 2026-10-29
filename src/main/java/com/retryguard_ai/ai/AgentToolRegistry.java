package com.retryguard_ai.ai;

import com.retryguard_ai.dto.InvestigationResponse;
import com.retryguard_ai.entity.MerchantOrder;
import com.retryguard_ai.entity.PaymentAttempt;
import com.retryguard_ai.entity.PaymentEvent;
import com.retryguard_ai.paypal.PaypalClient;
import com.retryguard_ai.paypal.PaypalClient.PaypalOrderDiagnostic;
import com.retryguard_ai.repository.InvestigationRepository;
import com.retryguard_ai.repository.MerchantOrderRepository;
import com.retryguard_ai.repository.PaymentAttemptRepository;
import com.retryguard_ai.repository.PaymentEventRepository;
import com.retryguard_ai.service.InvestigationService;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Component
public class AgentToolRegistry {

    private final MerchantOrderRepository orderRepository;
    private final PaymentAttemptRepository attemptRepository;
    private final PaymentEventRepository eventRepository;
    private final InvestigationRepository investigationRepository;
    private final InvestigationService investigationService;
    private final PaypalClient paypalClient;
    private final ObjectMapper objectMapper;

    public AgentToolRegistry(
            MerchantOrderRepository orderRepository,
            PaymentAttemptRepository attemptRepository,
            PaymentEventRepository eventRepository,
            InvestigationRepository investigationRepository,
            InvestigationService investigationService,
            PaypalClient paypalClient,
            ObjectMapper objectMapper) {
        this.orderRepository = orderRepository;
        this.attemptRepository = attemptRepository;
        this.eventRepository = eventRepository;
        this.investigationRepository = investigationRepository;
        this.investigationService = investigationService;
        this.paypalClient = paypalClient;
        this.objectMapper = objectMapper;
    }

    public List<GroqAgentProvider.ToolDefinition> getTools(String orderId) {
        return List.of(
                getOrderSummaryTool(orderId),
                getPaymentAttemptsTool(orderId),
                getVerifiedWebhookSummaryTool(orderId),
                getLatestInvestigationTool(orderId),
                verifyPayPalCaptureTool(orderId),
                finalRecommendationTool()
        );
    }

    private GroqAgentProvider.ToolDefinition getOrderSummaryTool(String orderId) {
        ObjectNode params = objectMapper.createObjectNode();
        params.put("type", "object");
        ObjectNode properties = objectMapper.createObjectNode();
        ObjectNode orderIdParam = objectMapper.createObjectNode();
        orderIdParam.put("type", "string");
        orderIdParam.put("description", "The order ID to look up");
        properties.set("orderId", orderIdParam);
        params.set("properties", properties);
        ArrayNode required = objectMapper.createArrayNode();
        required.add("orderId");
        params.set("required", required);

        return new GroqAgentProvider.ToolDefinition(
                "get_order_summary",
                "Get local order summary including ID, reference, status, amount, currency, and timestamps",
                params,
                args -> {
                    @SuppressWarnings("unchecked")
                    Map<String, Object> map = (Map<String, Object>) args;
                    String id = (String) map.get("orderId");
                    if (!id.equals(orderId)) {
                        throw new IllegalArgumentException("Order ID mismatch");
                    }
                    return orderRepository.findById(UUID.fromString(id))
                            .map(this::toOrderSummary)
                            .orElse(Map.of("error", "Order not found"));
                }
        );
    }

    private Map<String, Object> toOrderSummary(MerchantOrder order) {
        Map<String, Object> map = new java.util.HashMap<>();
        map.put("id", order.getId() != null ? order.getId().toString() : null);
        map.put("merchantOrderReference", order.getMerchantOrderReference());
        map.put("orderStatus", order.getOrderStatus() != null ? order.getOrderStatus().name() : null);
        map.put("amount", order.getAmount() != null ? order.getAmount().toPlainString() : null);
        map.put("currency", order.getCurrency());
        map.put("createdAt", order.getCreatedAt() != null ? order.getCreatedAt().toString() : null);
        map.put("updatedAt", order.getUpdatedAt() != null ? order.getUpdatedAt().toString() : null);
        return map;
    }

    private GroqAgentProvider.ToolDefinition getPaymentAttemptsTool(String orderId) {
        ObjectNode params = objectMapper.createObjectNode();
        params.put("type", "object");
        ObjectNode properties = objectMapper.createObjectNode();
        ObjectNode orderIdParam = objectMapper.createObjectNode();
        orderIdParam.put("type", "string");
        orderIdParam.put("description", "The order ID to look up attempts for");
        properties.set("orderId", orderIdParam);
        params.set("properties", properties);
        ArrayNode required = objectMapper.createArrayNode();
        required.add("orderId");
        params.set("required", required);

        return new GroqAgentProvider.ToolDefinition(
                "get_payment_attempts",
                "Get all payment attempts for an order with status and provider identifiers (sensitive values redacted)",
                params,
                args -> {
                    @SuppressWarnings("unchecked")
                    Map<String, Object> map = (Map<String, Object>) args;
                    String id = (String) map.get("orderId");
                    if (!id.equals(orderId)) {
                        throw new IllegalArgumentException("Order ID mismatch");
                    }
                    List<PaymentAttempt> attempts = attemptRepository.findByOrder_Id(UUID.fromString(id));
                    return attempts.stream().map(this::toAttemptSummary).toList();
                }
        );
    }

    private Map<String, Object> toAttemptSummary(PaymentAttempt attempt) {
        Map<String, Object> map = new java.util.HashMap<>();
        map.put("id", attempt.getId() != null ? attempt.getId().toString() : null);
        map.put("provider", attempt.getProvider());
        map.put("providerPaymentReference", maskReference(attempt.getProviderPaymentReference()));
        map.put("providerCaptureReference", maskReference(attempt.getProviderCaptureReference()));
        map.put("providerStatus", attempt.getProviderStatus());
        map.put("amount", attempt.getAmount() != null ? attempt.getAmount().toPlainString() : null);
        map.put("currency", attempt.getCurrency());
        map.put("paymentStatus", attempt.getPaymentStatus() != null ? attempt.getPaymentStatus().name() : null);
        map.put("failureCode", attempt.getFailureCode());
        map.put("createdAt", attempt.getCreatedAt() != null ? attempt.getCreatedAt().toString() : null);
        map.put("updatedAt", attempt.getUpdatedAt() != null ? attempt.getUpdatedAt().toString() : null);
        return map;
    }

    private String maskReference(String ref) {
        if (ref == null || ref.length() <= 8) {
            return ref;
        }
        return ref.substring(0, 4) + "****" + ref.substring(ref.length() - 4);
    }

    private GroqAgentProvider.ToolDefinition getVerifiedWebhookSummaryTool(String orderId) {
        ObjectNode params = objectMapper.createObjectNode();
        params.put("type", "object");
        ObjectNode properties = objectMapper.createObjectNode();
        ObjectNode orderIdParam = objectMapper.createObjectNode();
        orderIdParam.put("type", "string");
        orderIdParam.put("description", "The order ID to look up verified webhooks for");
        properties.set("orderId", orderIdParam);
        params.set("properties", properties);
        ArrayNode required = objectMapper.createArrayNode();
        required.add("orderId");
        params.set("required", required);

        return new GroqAgentProvider.ToolDefinition(
                "get_verified_webhook_summary",
                "Get verified webhook events for an order (event type, provider, verification/processing labels, received time). Never returns raw payload.",
                params,
                args -> {
                    @SuppressWarnings("unchecked")
                    Map<String, Object> map = (Map<String, Object>) args;
                    String id = (String) map.get("orderId");
                    if (!id.equals(orderId)) {
                        throw new IllegalArgumentException("Order ID mismatch");
                    }
                    List<PaymentAttempt> attempts = attemptRepository.findByOrder_Id(UUID.fromString(id));
                    List<UUID> attemptIds = attempts.stream().map(PaymentAttempt::getId).toList();
                    if (attemptIds.isEmpty()) {
                        return List.of();
                    }
                    List<PaymentEvent> events = eventRepository.findByPaymentAttempt_IdInOrderByReceivedAtAsc(attemptIds);
                    return events.stream()
                            .filter(e -> e.getSourceType() == PaymentEvent.SourceType.PROVIDER
                                    && e.getVerificationStatus() == PaymentEvent.VerificationStatus.VERIFIED)
                            .map(this::toWebhookSummary)
                            .toList();
                }
        );
    }

    private Map<String, Object> toWebhookSummary(PaymentEvent event) {
        Map<String, Object> map = new java.util.HashMap<>();
        map.put("id", event.getId() != null ? event.getId().toString() : null);
        map.put("eventType", event.getEventType());
        map.put("provider", event.getProvider());
        map.put("sourceType", event.getSourceType() != null ? event.getSourceType().name() : null);
        map.put("verificationStatus", event.getVerificationStatus() != null ? event.getVerificationStatus().name() : null);
        map.put("processingStatus", event.getProcessingStatus() != null ? event.getProcessingStatus().name() : null);
        map.put("receivedAt", event.getReceivedAt() != null ? event.getReceivedAt().toString() : null);
        map.put("processedAt", event.getProcessedAt() != null ? event.getProcessedAt().toString() : null);
        return map;
    }

    private GroqAgentProvider.ToolDefinition getLatestInvestigationTool(String orderId) {
        ObjectNode params = objectMapper.createObjectNode();
        params.put("type", "object");
        ObjectNode properties = objectMapper.createObjectNode();
        ObjectNode orderIdParam = objectMapper.createObjectNode();
        orderIdParam.put("type", "string");
        orderIdParam.put("description", "The order ID to look up investigation for");
        properties.set("orderId", orderIdParam);
        params.set("properties", properties);
        ArrayNode required = objectMapper.createArrayNode();
        required.add("orderId");
        params.set("required", required);

        return new GroqAgentProvider.ToolDefinition(
                "get_latest_investigation",
                "Get the latest deterministic investigation for an order (decision, reason, rules version, findings)",
                params,
                args -> {
                    @SuppressWarnings("unchecked")
                    Map<String, Object> map = (Map<String, Object>) args;
                    String id = (String) map.get("orderId");
                    if (!id.equals(orderId)) {
                        throw new IllegalArgumentException("Order ID mismatch");
                    }
                    return investigationRepository.findFirstByOrder_IdOrderByCreatedAtDescIdDesc(UUID.fromString(id))
                            .map(inv -> investigationService.getInvestigation(inv.getId()))
                            .map(this::toInvestigationSummary)
                            .orElse(Map.of("error", "No investigation found"));
                }
        );
    }

    private Map<String, Object> toInvestigationSummary(InvestigationResponse inv) {
        Map<String, Object> map = new java.util.HashMap<>();
        map.put("id", inv.id() != null ? inv.id().toString() : null);
        map.put("decision", inv.decision());
        map.put("reason", inv.reason());
        map.put("rulesVersion", inv.rulesVersion());
        map.put("createdAt", inv.createdAt() != null ? inv.createdAt().toString() : null);
        
        if (inv.findings() != null) {
            map.put("findings", inv.findings().stream().map(f -> {
                Map<String, Object> finding = new java.util.HashMap<>();
                finding.put("code", f.code() != null ? f.code().name() : null);
                finding.put("title", f.title());
                finding.put("severity", f.severity() != null ? f.severity().name() : null);
                finding.put("rootCauseCategory", f.rootCauseCategory() != null ? f.rootCauseCategory().name() : null);
                finding.put("rootCauseUncertain", f.rootCauseUncertain());
                return finding;
            }).toList());
        } else {
            map.put("findings", List.of());
        }
        return map;
    }

    private GroqAgentProvider.ToolDefinition verifyPayPalCaptureTool(String orderId) {
        ObjectNode params = objectMapper.createObjectNode();
        params.put("type", "object");
        ObjectNode properties = objectMapper.createObjectNode();
        ObjectNode attemptIdParam = objectMapper.createObjectNode();
        attemptIdParam.put("type", "string");
        attemptIdParam.put("description", "The payment attempt ID to verify");
        properties.set("attemptId", attemptIdParam);
        params.set("properties", properties);
        ArrayNode required = objectMapper.createArrayNode();
        required.add("attemptId");
        params.set("required", required);

        return new GroqAgentProvider.ToolDefinition(
                "verify_paypal_capture",
                "Read-only provider status check for a PayPal Sandbox order/capture. Returns verified status, order/capture references, amount, and currency. Requires existing PayPal order ID on the attempt.",
                params,
                args -> {
                    @SuppressWarnings("unchecked")
                    Map<String, Object> map = (Map<String, Object>) args;
                    String attemptIdStr = (String) map.get("attemptId");
                    UUID attemptId = UUID.fromString(attemptIdStr);
                    
                    PaymentAttempt attempt = attemptRepository.findById(attemptId)
                            .orElseThrow(() -> new IllegalArgumentException("Attempt not found"));
                    
                    if (!orderId.equals(attempt.getOrder().getId().toString())) {
                        throw new IllegalArgumentException("Attempt does not belong to the investigated order");
                    }
                    
                    String providerOrderId = attempt.getProviderPaymentReference();
                    if (providerOrderId == null || providerOrderId.isBlank()) {
                        return Map.of("error", "No PayPal order ID on attempt; cannot verify");
                    }

                    PaypalOrderDiagnostic diagnostic = paypalClient.diagnoseOrder(providerOrderId);
                    return toDiagnosticSummary(diagnostic, attempt);
                }
        );
    }

    private Map<String, Object> toDiagnosticSummary(PaypalOrderDiagnostic diagnostic, PaymentAttempt attempt) {
        Map<String, Object> result = new java.util.HashMap<>();
        result.put("httpStatus", diagnostic.httpStatus());
        result.put("paypalOrderStatus", diagnostic.status() != null ? diagnostic.status() : "UNKNOWN");
        result.put("paypalOrderIntent", diagnostic.intent() != null ? diagnostic.intent() : "UNKNOWN");
        
        if (diagnostic.purchaseUnits() != null && !diagnostic.purchaseUnits().isEmpty()) {
            result.put("purchaseUnits", diagnostic.purchaseUnits().stream()
                    .map(u -> {
                        Map<String, Object> pu = new java.util.HashMap<>();
                        pu.put("currencyCode", u.currencyCode() != null ? u.currencyCode() : "UNKNOWN");
                        pu.put("value", u.value() != null ? u.value() : "0.00");
                        return pu;
                    })
                    .toList());
        } else {
            result.put("purchaseUnits", List.of());
        }
        
        if (diagnostic.approvalLink() != null) {
            result.put("approvalLink", Map.of(
                    "rel", diagnostic.approvalLink().rel(),
                    "scheme", diagnostic.approvalLink().scheme(),
                    "host", diagnostic.approvalLink().host(),
                    "path", diagnostic.approvalLink().path(),
                    "expectedSandboxCheckoutHost", diagnostic.approvalLink().expectedSandboxCheckoutHost()
            ));
        }
        
        result.put("localAttemptStatus", attempt.getPaymentStatus() != null ? attempt.getPaymentStatus().name() : null);
        result.put("localProviderStatus", attempt.getProviderStatus() != null ? attempt.getProviderStatus() : null);
        result.put("localProviderCaptureReference", maskReference(attempt.getProviderCaptureReference()));
        result.put("amountMatch", attempt.getAmount() != null ? attempt.getAmount().toPlainString() : null);
        result.put("currencyMatch", attempt.getCurrency() != null ? attempt.getCurrency() : null);

        if (diagnostic.errorName() != null) {
            result.put("errorName", diagnostic.errorName());
            result.put("errorMessage", diagnostic.errorMessage());
            result.put("debugId", diagnostic.debugId());
        }

        return result;
    }

    private GroqAgentProvider.ToolDefinition finalRecommendationTool() {
        ObjectNode params = objectMapper.createObjectNode();
        params.put("type", "object");
        ObjectNode properties = objectMapper.createObjectNode();
        
        ObjectNode recommendationParam = objectMapper.createObjectNode();
        recommendationParam.put("type", "string");
        ArrayNode enumValues = objectMapper.createArrayNode();
        enumValues.add("RECONCILE_LOCAL_ORDER");
        enumValues.add("WAIT_FOR_WEBHOOK");
        enumValues.add("ESCALATE");
        recommendationParam.set("enum", enumValues);
        recommendationParam.put("description", "Final recommendation based on gathered evidence");
        properties.set("recommendation", recommendationParam);
        
        ObjectNode reasonParam = objectMapper.createObjectNode();
        reasonParam.put("type", "string");
        reasonParam.put("description", "Brief reason for the recommendation");
        properties.set("reason", reasonParam);
        
        params.set("properties", properties);
        ArrayNode required = objectMapper.createArrayNode();
        required.add("recommendation");
        required.add("reason");
        params.set("required", required);

        return new GroqAgentProvider.ToolDefinition(
                "final_recommendation",
                "Submit final recommendation after gathering sufficient evidence. Must be one of: RECONCILE_LOCAL_ORDER, WAIT_FOR_WEBHOOK, ESCALATE.",
                params,
                args -> {
                    @SuppressWarnings("unchecked")
                    Map<String, Object> map = (Map<String, Object>) args;
                    String recommendation = (String) map.get("recommendation");
                    String reason = (String) map.get("reason");
                    if (recommendation == null || recommendation.isBlank()) {
                        throw new IllegalArgumentException("Recommendation is required");
                    }
                    if (!List.of("RECONCILE_LOCAL_ORDER", "WAIT_FOR_WEBHOOK", "ESCALATE").contains(recommendation)) {
                        throw new IllegalArgumentException("Invalid recommendation: " + recommendation);
                    }
                    return Map.of("recommendation", recommendation, "reason", reason != null ? reason : "");
                }
        );
    }
}