package com.retryguard_ai.paypal;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

@SpringBootTest
@EnabledIfSystemProperty(named = "retryguard.paypal.order-diagnostic", matches = "true")
class PaypalOrderDiagnosticTest {

    private static final String ORDER_ID = "17Y00533C42982630";

    @Autowired
    private PaypalClient paypalClient;

    @Test
    void reportsExistingOrderDetails() {
        PaypalClient.PaypalOrderDiagnostic result = paypalClient.diagnoseOrder(ORDER_ID);

        System.out.print(formatDiagnostic(result));
    }

    static String formatDiagnostic(PaypalClient.PaypalOrderDiagnostic result) {
        StringBuilder output = new StringBuilder()
                .append("HTTP status=").append(result.httpStatus()).append(System.lineSeparator());
        if (result.httpStatus() >= 400) {
            return output.append("PayPal error name=").append(result.errorName())
                    .append(" message=").append(result.errorMessage())
                    .append(" debug_id=").append(result.debugId())
                    .append(System.lineSeparator())
                    .toString();
        }

        output.append("Order status=").append(result.status())
                .append(System.lineSeparator())
                .append("Intent=").append(result.intent())
                .append(System.lineSeparator());
        result.purchaseUnits().forEach(unit -> output
                .append("Purchase unit currency_code=").append(unit.currencyCode())
                .append(" value=").append(unit.value())
                .append(System.lineSeparator()));

        PaypalClient.PaypalOrderApprovalLink link = result.approvalLink();
        if (link == null) {
            return output.append("Approval link: MISSING")
                    .append(System.lineSeparator())
                    .toString();
        }

        output.append("Approval link rel=").append(link.rel())
                .append(" scheme=").append(link.scheme())
                .append(" host=").append(link.host())
                .append(" path=").append(link.path())
                .append(System.lineSeparator())
                .append("Expected PayPal Sandbox checkout host=")
                .append(link.expectedSandboxCheckoutHost() ? "YES" : "NO")
                .append(System.lineSeparator());
        if (link.host() == null) {
            output.append("WARNING: approval URL could not be parsed.")
                    .append(System.lineSeparator());
        } else if (!link.expectedSandboxCheckoutHost()) {
            output.append("WARNING: approval URL uses an unexpected host.")
                    .append(System.lineSeparator());
        }
        if (link.scheme() != null && !"https".equalsIgnoreCase(link.scheme())) {
            output.append("WARNING: approval URL does not use HTTPS.")
                    .append(System.lineSeparator());
        }
        return output.toString();
    }
}
