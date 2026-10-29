package com.yourorg.payops.controller;

import com.yourorg.payops.entity.Account;
import com.yourorg.payops.entity.Merchant;
import com.yourorg.payops.entity.SwitchDatabase;
import com.yourorg.payops.exception.GlobalExceptionHandler;
import com.yourorg.payops.repository.AccountRepository;
import com.yourorg.payops.repository.MerchantRepository;
import com.yourorg.payops.repository.SwitchDatabaseRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.validation.beanvalidation.LocalValidatorFactoryBean;

import java.util.Optional;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class CoreMasterControllersTest {
    private SwitchDatabaseRepository switches;
    private MerchantRepository merchants;
    private AccountRepository accounts;
    private MockMvc switchMvc;
    private MockMvc merchantMvc;
    private MockMvc accountMvc;

    @BeforeEach
    void setUp() {
        switches = mock(SwitchDatabaseRepository.class);
        merchants = mock(MerchantRepository.class);
        accounts = mock(AccountRepository.class);
        LocalValidatorFactoryBean validator = new LocalValidatorFactoryBean();
        validator.afterPropertiesSet();
        GlobalExceptionHandler advice = new GlobalExceptionHandler();

        switchMvc = MockMvcBuilders.standaloneSetup(new SwitchDatabaseController(switches))
                .setControllerAdvice(advice).setValidator(validator).build();
        merchantMvc = MockMvcBuilders.standaloneSetup(new MerchantController(merchants, switches))
                .setControllerAdvice(advice).setValidator(validator).build();
        accountMvc = MockMvcBuilders.standaloneSetup(new AccountController(accounts, merchants))
                .setControllerAdvice(advice).setValidator(validator).build();
    }

    @Test
    void switchApiValidatesCreatesAndReportsDuplicateAndMissingRows() throws Exception {
        when(switches.findByCode("SAMPLE")).thenReturn(Optional.empty());
        when(switches.save(any(SwitchDatabase.class))).thenAnswer(invocation -> {
            SwitchDatabase value = invocation.getArgument(0);
            value.setId(1L);
            return value;
        });

        switchMvc.perform(post("/api/switches").contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isBadRequest());
        switchMvc.perform(post("/api/switches").contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"code":"SAMPLE","name":"Sample Switch","switchType":"BANK","protocol":"REST"}
                                """))
                .andExpect(status().isCreated()).andExpect(jsonPath("$.id").value(1));
        when(switches.findByCode("DUP")).thenReturn(Optional.of(new SwitchDatabase()));
        switchMvc.perform(post("/api/switches").contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"code":"DUP","name":"Duplicate","switchType":"BANK","protocol":"REST"}
                                """))
                .andExpect(status().isConflict());
        when(switches.findById(999L)).thenReturn(Optional.empty());
        switchMvc.perform(get("/api/switches/999")).andExpect(status().isNotFound());
        switchMvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch("/api/switches/1/status")
                        .contentType(MediaType.APPLICATION_JSON).content("null"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void merchantApiValidatesCreatesAndReportsDuplicate() throws Exception {
        when(merchants.findByCode("SHOP")).thenReturn(Optional.empty());
        when(merchants.save(any(Merchant.class))).thenAnswer(invocation -> {
            Merchant value = invocation.getArgument(0);
            value.setId(2L);
            return value;
        });

        merchantMvc.perform(post("/api/merchants").contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isBadRequest());
        merchantMvc.perform(post("/api/merchants").contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"code":"SHOP","name":"Example Shop","currency":"INR","status":"ACTIVE"}
                                """))
                .andExpect(status().isCreated()).andExpect(jsonPath("$.id").value(2));
        when(merchants.findByCode("DUP")).thenReturn(Optional.of(new Merchant()));
        merchantMvc.perform(post("/api/merchants").contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"code":"DUP","name":"Duplicate","currency":"INR","status":"ACTIVE"}
                                """))
                .andExpect(status().isConflict());
        when(merchants.findById(999L)).thenReturn(Optional.empty());
        merchantMvc.perform(get("/api/merchants/999")).andExpect(status().isNotFound());
    }

    @Test
    void accountApiValidatesCreatesAndReportsMissingRows() throws Exception {
        when(accounts.findByCode("1100")).thenReturn(Optional.empty());
        when(accounts.save(any(Account.class))).thenAnswer(invocation -> {
            Account value = invocation.getArgument(0);
            value.setId(3L);
            return value;
        });

        accountMvc.perform(post("/api/accounts").contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isBadRequest());
        accountMvc.perform(post("/api/accounts").contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"code":"1100","name":"Acquirer Receivable","accountType":"ASSET","currency":"INR","active":true}
                                """))
                .andExpect(status().isCreated()).andExpect(jsonPath("$.id").value(3));
        when(accounts.findById(999L)).thenReturn(Optional.empty());
        accountMvc.perform(get("/api/accounts/999")).andExpect(status().isNotFound());
        when(accounts.findByCode("DUP")).thenReturn(Optional.of(new Account()));
        accountMvc.perform(post("/api/accounts").contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"code":"DUP","name":"Duplicate","accountType":"ASSET","currency":"INR","active":true}
                                """))
                .andExpect(status().isConflict());
    }
}
