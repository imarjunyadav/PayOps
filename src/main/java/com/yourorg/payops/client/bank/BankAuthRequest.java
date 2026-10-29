package com.yourorg.payops.client.bank;

import java.math.BigDecimal;

public class BankAuthRequest {
    private String requestId;
    private String paymentReference;
    private String cardNumber;
    private BigDecimal amount;
    private String currency;

    public BankAuthRequest() {}

    public BankAuthRequest(String requestId, String paymentReference, String cardNumber, BigDecimal amount, String currency) {
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
