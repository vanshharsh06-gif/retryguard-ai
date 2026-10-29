package com.retryguard_ai.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
public class CreatePaymentAttemptRequest {

    @NotBlank
    @Size(max = 30)
    @Pattern(regexp = "PAYPAL_SANDBOX")
    private String provider;

    // Manual getters/setters for Lombok compatibility
    public String getProvider() { return provider; }
    public void setProvider(String provider) { this.provider = provider; }
}