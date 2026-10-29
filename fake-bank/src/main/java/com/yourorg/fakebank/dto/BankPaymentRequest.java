package com.yourorg.fakebank.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import java.math.BigDecimal;

public class BankPaymentRequest {

    @NotBlank
    private String requestId;

    @NotBlank
    private String paymentReference;

    @NotBlank
    private String cardNumber;

    @NotNull
    @Positive
    private BigDecimal amount;

    @NotBlank
    private String currency = "INR";

    public BankPaymentRequest() {}

    public BankPaymentRequest(String requestId, String paymentReference, String cardNumber, BigDecimal amount, String currency) {
        this.requestId = requestId;
        this.paymentReference = paymentReference;
        this.cardNumber = cardNumber;
        this.amount = amount;
        this.currency = currency;
    }

    public String getRequestId() { return requestId; }
    public void setRequestId(String requestId) { this.requestId = requestId; }

    public String getPaymentReference() { return paymentReference; }
    public void setPaymentReference(String paymentReference) { this.paymentReference = paymentReference; }

    public String getCardNumber() { return cardNumber; }
    public void setCardNumber(String cardNumber) { this.cardNumber = cardNumber; }

    public BigDecimal getAmount() { return amount; }
    public void setAmount(BigDecimal amount) { this.amount = amount; }

    public String getCurrency() { return currency; }
    public void setCurrency(String currency) { this.currency = currency; }
}
