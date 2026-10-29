package com.yourorg.payops.controller;

import com.yourorg.payops.entity.Merchant;
import com.yourorg.payops.entity.Payment;
import com.yourorg.payops.entity.SwitchDatabase;
import com.yourorg.payops.exception.GlobalExceptionHandler;
import com.yourorg.payops.model.MerchantStatus;
import com.yourorg.payops.model.SwitchStatus;
import com.yourorg.payops.repository.MerchantRepository;
import com.yourorg.payops.repository.PaymentEventRepository;
import com.yourorg.payops.repository.PaymentRepository;
import com.yourorg.payops.repository.SwitchDatabaseRepository;
import jakarta.validation.Validation;
import java.util.List;
import java.util.Optional;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.validation.beanvalidation.LocalValidatorFactoryBean;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class PaymentControllerWebTest {
    private PaymentRepository payments;
    private MerchantRepository merchants;
    private SwitchDatabaseRepository switches;
    private PaymentEventRepository events;
    private MockMvc mvc;
    private Merchant merchant;
    private SwitchDatabase route;

    @BeforeEach
    void setUp() {
        payments = mock(PaymentRepository.class);
        merchants = mock(MerchantRepository.class);
        switches = mock(SwitchDatabaseRepository.class);
        events = mock(PaymentEventRepository.class);
        var validator = Validation.buildDefaultValidatorFactory().getValidator();
        mvc = MockMvcBuilders.standaloneSetup(new PaymentController(payments, merchants, switches, events, validator))
                .setControllerAdvice(new GlobalExceptionHandler()).build();

        merchant = new Merchant();
        merchant.setId(4L);
        merchant.setStatus(MerchantStatus.ACTIVE);
        route = new SwitchDatabase();
        route.setId(9L);
        route.setStatus(SwitchStatus.ACTIVE);
    }

    @Test
    void listsPaymentsWithoutFilters() throws Exception {
        stubSearch();

        mvc.perform(get("/api/payments"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$").isArray())
                .andExpect(jsonPath("$").isEmpty());

        verify(payments).findAll(org.mockito.ArgumentMatchers.<Specification<Payment>>any(), any(Sort.class));
    }

    @Test
    void listsPaymentsWithStatusFilter() throws Exception {
        stubSearch();
        mvc.perform(get("/api/payments").param("status", "SETTLED"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$").isArray());
        verify(payments).findAll(org.mockito.ArgumentMatchers.<Specification<Payment>>any(), any(Sort.class));
    }

    @Test
    void listsPaymentsWithMerchantFilter() throws Exception {
        stubSearch();
        mvc.perform(get("/api/payments").param("merchantId", "4"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$").isArray());
        verify(payments).findAll(org.mockito.ArgumentMatchers.<Specification<Payment>>any(), any(Sort.class));
    }

    @Test
    void listsPaymentsWithDateFilters() throws Exception {
        stubSearch();
        mvc.perform(get("/api/payments")
                        .param("from", "2026-01-01T00:00:00")
                        .param("to", "2026-01-31T23:59:59"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$").isArray());
        verify(payments).findAll(org.mockito.ArgumentMatchers.<Specification<Payment>>any(), any(Sort.class));
    }

    private void stubSearch() {
        when(payments.findAll(org.mockito.ArgumentMatchers.<Specification<Payment>>any(), any(Sort.class)))
                .thenReturn(List.of());
    }

    @Test
    void createsPaymentAndReturnsCreatedLocation() throws Exception {
        when(merchants.findById(4L)).thenReturn(Optional.of(merchant));
        when(switches.findByStatusOrderByPriorityAsc(SwitchStatus.ACTIVE)).thenReturn(List.of(route));
        when(payments.findByReference("pay-web-1")).thenReturn(Optional.empty());
        when(payments.save(any(Payment.class))).thenAnswer(invocation -> {
            Payment saved = invocation.getArgument(0);
            saved.setId(17L);
            return saved;
        });

        mvc.perform(post("/api/payments").contentType(MediaType.APPLICATION_JSON).content(validRequest()))
                .andExpect(status().isCreated())
                .andExpect(header().string("Location", org.hamcrest.Matchers.endsWith("/api/payments/17")))
                .andExpect(jsonPath("$.status").value("INITIATED"));
    }

    @Test
    void invalidPaymentBodyReturnsBadRequest() throws Exception {
        when(merchants.findById(4L)).thenReturn(Optional.of(merchant));
        when(switches.findByStatusOrderByPriorityAsc(SwitchStatus.ACTIVE)).thenReturn(List.of(route));
        mvc.perform(post("/api/payments").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"reference\":\"pay-web-1\",\"merchant\":{\"id\":4}}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.status").value(400));
    }

    @Test
    void unknownMerchantReturnsNotFound() throws Exception {
        when(merchants.findById(404L)).thenReturn(Optional.empty());

        mvc.perform(post("/api/payments").contentType(MediaType.APPLICATION_JSON)
                        .content(validRequest().replace("\"id\":4", "\"id\":404")))
                .andExpect(status().isNotFound());
    }

    @Test
    void duplicateReferenceReturnsConflict() throws Exception {
        when(merchants.findById(4L)).thenReturn(Optional.of(merchant));
        when(switches.findByStatusOrderByPriorityAsc(SwitchStatus.ACTIVE)).thenReturn(List.of(route));
        when(payments.findByReference("pay-web-1")).thenReturn(Optional.of(new Payment()));

        mvc.perform(post("/api/payments").contentType(MediaType.APPLICATION_JSON).content(validRequest()))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.status").value(409));
    }

    @Test
    void inactiveMerchantReturnsBusinessRuleStatus() throws Exception {
        merchant.setStatus(MerchantStatus.SUSPENDED);
        when(merchants.findById(4L)).thenReturn(Optional.of(merchant));

        mvc.perform(post("/api/payments").contentType(MediaType.APPLICATION_JSON).content(validRequest()))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.status").value(422));
    }

    @Test
    void missingPaymentReadReturnsNotFound() throws Exception {
        when(payments.findById(99L)).thenReturn(Optional.empty());

        mvc.perform(get("/api/payments/99")).andExpect(status().isNotFound());
    }

    private String validRequest() {
        return """
                {"reference":"pay-web-1","merchant":{"id":4},"amount":125.50,"currency":"INR","method":"UPI"}
                """;
    }
}
