package com.yourorg.fakebank.dto;

public class BankPaymentResponse {
    private String requestId;
    private String paymentReference;
    private String status;
    private String bankReference;
    private String responseCode;
    private String responseMessage;

    public BankPaymentResponse() {}

    public BankPaymentResponse(String requestId, String paymentReference, String status, String bankReference, String responseCode, String responseMessage) {
        this.requestId = requestId;
        this.paymentReference = paymentReference;
        this.status = status;
        this.bankReference = bankReference;
        this.responseCode = responseCode;
        this.responseMessage = responseMessage;
    }

    public String getRequestId() { return requestId; }
    public void setRequestId(String requestId) { this.requestId = requestId; }

    public String getPaymentReference() { return paymentReference; }
    public void setPaymentReference(String paymentReference) { this.paymentReference = paymentReference; }

    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }

    public String getBankReference() { return bankReference; }
    public void setBankReference(String bankReference) { this.bankReference = bankReference; }

    public String getResponseCode() { return responseCode; }
    public void setResponseCode(String responseCode) { this.responseCode = responseCode; }

    public String getResponseMessage() { return responseMessage; }
    public void setResponseMessage(String responseMessage) { this.responseMessage = responseMessage; }
}
