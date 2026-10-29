package com.yourorg.payops.controller;

import com.yourorg.payops.entity.PaymentEvent;
import com.yourorg.payops.exception.GlobalExceptionHandler;
import com.yourorg.payops.repository.PaymentEventRepository;
import com.yourorg.payops.repository.PaymentRepository;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class PaymentEventControllerWebTest {
    private PaymentRepository payments;
    private PaymentEventRepository events;
    private MockMvc mvc;

    @BeforeEach
    void setUp() {
        payments = mock(PaymentRepository.class);
        events = mock(PaymentEventRepository.class);
        mvc = MockMvcBuilders.standaloneSetup(new PaymentEventController(payments, events))
                .setControllerAdvice(new GlobalExceptionHandler()).build();
    }

    @Test
    void returnsOrderedEventHistoryForExistingPayment() throws Exception {
        // Controller now calls findById (not existsById) to fetch the Payment for ownership check.
        // SecurityContext is null in standaloneSetup, so merchant check is safely skipped.
        com.yourorg.payops.entity.Payment payment = new com.yourorg.payops.entity.Payment();
        payment.setId(7L);
        PaymentEvent event = new PaymentEvent();
        event.setId(1L);
        when(payments.findById(7L)).thenReturn(Optional.of(payment));
        when(events.findByPaymentIdOrderByOccurredAtAsc(7L)).thenReturn(List.of(event));

        mvc.perform(get("/api/payments/7/events"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].id").value(1));
    }

    @Test
    void missingPaymentReturnsNotFound() throws Exception {
        when(payments.findById(7L)).thenReturn(Optional.empty());

        mvc.perform(get("/api/payments/7/events"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.status").value(404));
    }
}
