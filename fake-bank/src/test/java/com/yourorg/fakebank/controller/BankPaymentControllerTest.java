package com.yourorg.fakebank.controller;

import com.yourorg.fakebank.dto.BankPaymentRequest;
import com.yourorg.fakebank.dto.BankPaymentResponse;
import com.yourorg.fakebank.exception.BankIdempotencyConflictException;
import com.yourorg.fakebank.service.BankPaymentService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.util.Optional;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

class BankPaymentControllerTest {

    private BankPaymentService service;
    private MockMvc mvc;

    @BeforeEach
    void setUp() {
        service = mock(BankPaymentService.class);
        mvc = MockMvcBuilders.standaloneSetup(new BankPaymentController(service)).build();
    }

    @Test
    void authorizeEndpointReturnsOkWithApprovedStatus() throws Exception {
        when(service.authorize(any(BankPaymentRequest.class)))
                .thenReturn(new BankPaymentResponse("REQ-1", "PAY-1", "APPROVED", "BNK-100", "00", "Approved"));

        String payload = """
                {"requestId":"REQ-1","paymentReference":"PAY-1","cardNumber":"4111111111111111","amount":100.00,"currency":"INR"}
                """;

        mvc.perform(post("/api/bank/payments")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(payload))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("APPROVED"))
                .andExpect(jsonPath("$.bankReference").value("BNK-100"))
                .andExpect(jsonPath("$.cardNumber").doesNotExist()); // Never expose card data
    }

    @Test
    void authorizeReturns409OnIdempotencyConflict() throws Exception {
        when(service.authorize(any(BankPaymentRequest.class)))
                .thenThrow(new BankIdempotencyConflictException("Payload mismatch for duplicate requestId: amount differs"));

        String payload = """
                {"requestId":"REQ-1","paymentReference":"PAY-1","cardNumber":"4111111111111111","amount":200.00,"currency":"INR"}
                """;

        mvc.perform(post("/api/bank/payments")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(payload))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.status").value("CONFLICT"))
                .andExpect(jsonPath("$.responseCode").value("409"))
                .andExpect(jsonPath("$.responseMessage").value("Payload mismatch for duplicate requestId: amount differs"));
    }

    @Test
    void getStatusReturnsExistingTransaction() throws Exception {
        when(service.getStatus("REQ-1"))
                .thenReturn(Optional.of(new BankPaymentResponse("REQ-1", "PAY-1", "APPROVED", "BNK-100", "00", "Approved")));

        mvc.perform(get("/api/bank/payments/REQ-1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("APPROVED"))
                .andExpect(jsonPath("$.bankReference").value("BNK-100"));
    }

    @Test
    void getStatusNotFoundReturns404() throws Exception {
        when(service.getStatus("REQ-UNKNOWN")).thenReturn(Optional.empty());

        mvc.perform(get("/api/bank/payments/REQ-UNKNOWN"))
                .andExpect(status().isNotFound());
    }
}
