package com.yourorg.payops.controller;

import com.yourorg.payops.entity.Merchant;
import com.yourorg.payops.entity.Payment;
import com.yourorg.payops.entity.SwitchDatabase;
import com.yourorg.payops.exception.BusinessRuleException;
import com.yourorg.payops.exception.ConflictException;
import com.yourorg.payops.exception.ResourceNotFoundException;
import com.yourorg.payops.model.MerchantStatus;
import com.yourorg.payops.model.PaymentMethod;
import com.yourorg.payops.model.PaymentStatus;
import com.yourorg.payops.model.SwitchStatus;
import com.yourorg.payops.repository.MerchantRepository;
import com.yourorg.payops.repository.PaymentEventRepository;
import com.yourorg.payops.repository.PaymentRepository;
import com.yourorg.payops.repository.SwitchDatabaseRepository;
import jakarta.validation.Validation;
import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;
import jakarta.validation.ConstraintViolationException;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class PaymentControllerTest {
    private PaymentRepository payments;
    private MerchantRepository merchants;
    private SwitchDatabaseRepository switches;
    private PaymentEventRepository events;
    private PaymentController controller;
    private Merchant activeMerchant;
    private SwitchDatabase activeSwitch;

    @BeforeEach
    void setUp() {
        payments = mock(PaymentRepository.class);
        merchants = mock(MerchantRepository.class);
        switches = mock(SwitchDatabaseRepository.class);
        events = mock(PaymentEventRepository.class);
        var validator = Validation.buildDefaultValidatorFactory().getValidator();
        controller = new PaymentController(payments, merchants, switches, events, validator);

        activeMerchant = new Merchant();
        activeMerchant.setId(4L);
        activeMerchant.setStatus(MerchantStatus.ACTIVE);
        activeSwitch = new SwitchDatabase();
        activeSwitch.setId(9L);
        activeSwitch.setStatus(SwitchStatus.ACTIVE);
        activeMerchant.setDefaultSwitch(activeSwitch);

        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/api/payments");
        request.setServerName("localhost");
        request.setServerPort(8080);
        RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(request));
    }

    @AfterEach
    void clearRequest() {
        RequestContextHolder.resetRequestAttributes();
    }

    @Test
    void createsInitiatedPaymentAndInitialEvent() throws Exception {
        Payment request = validPayment();
        when(merchants.findById(4L)).thenReturn(Optional.of(activeMerchant));
        when(switches.findById(9L)).thenReturn(Optional.of(activeSwitch));
        when(payments.findByReference("pay-001")).thenReturn(Optional.empty());
        when(payments.save(any(Payment.class))).thenAnswer(invocation -> {
            Payment saved = invocation.getArgument(0);
            saved.setId(17L);
            return saved;
        });

        var response = controller.create(request);

        assertEquals(201, response.getStatusCode().value());
        assertEquals(PaymentStatus.INITIATED, response.getBody().getStatus());
        assertEquals(activeSwitch, response.getBody().getSwitchDatabase());
        verify(events).save(any());
    }

    @Test
    void rejectsInvalidAmountAsValidationFailure() {
        Payment request = validPayment();
        request.setAmount(BigDecimal.ZERO);
        when(merchants.findById(4L)).thenReturn(Optional.of(activeMerchant));
        when(switches.findById(9L)).thenReturn(Optional.of(activeSwitch));

        assertThrows(ConstraintViolationException.class, () -> controller.create(request));
    }

    @Test
    void reportsMissingMerchantReferenceAsValidationFailure() {
        Payment request = validPayment();
        request.setMerchant(null);

        assertThrows(ConstraintViolationException.class, () -> controller.create(request));
    }

    @Test
    void reportsDuplicateReferenceAsConflict() {
        Payment request = validPayment();
        when(merchants.findById(4L)).thenReturn(Optional.of(activeMerchant));
        when(switches.findById(9L)).thenReturn(Optional.of(activeSwitch));
        when(payments.findByReference("pay-001")).thenReturn(Optional.of(new Payment()));

        assertThrows(ConflictException.class, () -> controller.create(request));
    }

    @Test
    void rejectsPaymentForInactiveMerchantAsBusinessRule() {
        Payment request = validPayment();
        activeMerchant.setStatus(MerchantStatus.SUSPENDED);
        when(merchants.findById(4L)).thenReturn(Optional.of(activeMerchant));

        assertThrows(BusinessRuleException.class, () -> controller.create(request));
    }

    @Test
    void reportsMissingPaymentOnRead() {
        when(payments.findById(99L)).thenReturn(Optional.empty());

        assertThrows(ResourceNotFoundException.class, () -> controller.get(99L));
    }

    @Test
    void rejectsInvertedSearchDateRange() {
        var from = java.time.LocalDateTime.parse("2026-02-02T00:00:00");
        var to = java.time.LocalDateTime.parse("2026-02-01T00:00:00");

        assertThrows(BusinessRuleException.class, () -> controller.list(null, null, from, to));
    }

    private Payment validPayment() {
        Payment payment = new Payment();
        payment.setReference("pay-001");
        payment.setMerchant(activeMerchant);
        payment.setAmount(new BigDecimal("125.5000"));
        payment.setCurrency("INR");
        payment.setMethod(PaymentMethod.UPI);
        return payment;
    }
}
