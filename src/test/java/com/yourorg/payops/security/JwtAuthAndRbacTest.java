package com.yourorg.payops.security;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.yourorg.payops.dto.LoginRequest;
import com.yourorg.payops.entity.*;
import com.yourorg.payops.model.*;
import com.yourorg.payops.repository.*;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import org.springframework.http.MediaType;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.FilterChainProxy;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

import java.math.BigDecimal;
import java.util.Set;

import org.springframework.test.context.ActiveProfiles;
import static org.hamcrest.Matchers.*;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/**
 * Stage 4 Integration Tests: JWT Authentication and RBAC.
 * Uses full Spring Boot context + Spring Security filter chain via MockMvc.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.MOCK)
@ActiveProfiles("test")
@TestPropertySource(properties = {
        "spring.datasource.url=jdbc:h2:mem:jwt_test;DB_CLOSE_DELAY=-1;MODE=PostgreSQL",
        "spring.datasource.driver-class-name=org.h2.Driver",
        "spring.datasource.username=sa",
        "spring.datasource.password=",
        "spring.jpa.hibernate.ddl-auto=create-drop",
        "payops.jwt.secret=payops-super-secure-test-key-that-is-at-least-256-bits-long!",
        "payops.jwt.expiration-ms=3600000",
        "payops.recovery.enabled=false"
})
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class JwtAuthAndRbacTest {

    @Autowired
    private WebApplicationContext context;

    @Autowired
    private FilterChainProxy filterChainProxy;

    private MockMvc mvc;

    /** Instantiated directly — Jackson ObjectMapper is not auto-configured in WebEnvironment.MOCK without MVC slice. */
    private final ObjectMapper objectMapper = new ObjectMapper();

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private RoleRepository roleRepository;

    @Autowired
    private MerchantRepository merchantRepository;

    @Autowired
    private PaymentRepository paymentRepository;

    @Autowired
    private SwitchDatabaseRepository switchDatabaseRepository;

    @Autowired
    private PaymentEventRepository paymentEventRepository;

    @Autowired
    private PasswordEncoder passwordEncoder;

    @MockitoBean
    private com.yourorg.payops.client.bank.FakeBankClient bankClient;

    private static Long merchantPaymentId;
    private static Long merchant2PaymentId;
    private static Long merchant2Id;

    @BeforeEach
    void setUp() {
        mvc = MockMvcBuilders.webAppContextSetup(context)
                .addFilter(filterChainProxy)
                .build();

        // Clean up in FK-safe order
        paymentEventRepository.deleteAll();
        paymentRepository.deleteAll();
        userRepository.deleteAll();
        roleRepository.deleteAll();
        merchantRepository.deleteAll();
        switchDatabaseRepository.deleteAll();

        Role adminRole = getOrCreateRole(RoleName.ADMIN);
        Role merchantRole = getOrCreateRole(RoleName.MERCHANT);

        Merchant m1 = createMerchant("MERCH-01", "Merchant One");
        Merchant m2 = createMerchant("MERCH-02", "Merchant Two");
        merchant2Id = m2.getId();

        createUser("admin", "AdminPass1!", null, Set.of(adminRole));
        createUser("merchant1", "MerchPass1!", m1, Set.of(merchantRole));
        createUser("merchant2", "MerchPass2!", m2, Set.of(merchantRole));

        SwitchDatabase sw = new SwitchDatabase();
        sw.setCode("SW-JWT-TEST");
        sw.setName("JWT Test Switch");
        sw.setSwitchType(SwitchType.BANK);
        sw.setProtocol(SwitchProtocol.REST);
        sw.setStatus(SwitchStatus.ACTIVE);
        sw.setPriority(1);
        sw.setTimeoutMs(5000);
        switchDatabaseRepository.save(sw);

        Payment p1 = new Payment();
        p1.setReference("PAY-M1-JWT");
        p1.setMerchant(m1);
        p1.setSwitchDatabase(sw);
        p1.setAmount(new BigDecimal("100.00"));
        p1.setCurrency("INR");
        p1.setMethod(PaymentMethod.CARD);
        p1.setStatus(PaymentStatus.APPROVED);
        p1.setRefundedAmount(BigDecimal.ZERO);
        p1 = paymentRepository.save(p1);
        merchantPaymentId = p1.getId();

        Payment p2 = new Payment();
        p2.setReference("PAY-M2-JWT");
        p2.setMerchant(m2);
        p2.setSwitchDatabase(sw);
        p2.setAmount(new BigDecimal("200.00"));
        p2.setCurrency("INR");
        p2.setMethod(PaymentMethod.CARD);
        p2.setStatus(PaymentStatus.APPROVED);
        p2.setRefundedAmount(BigDecimal.ZERO);
        p2 = paymentRepository.save(p2);
        merchant2PaymentId = p2.getId();
    }

    // ─── Authentication Tests ─────────────────────────────────────────────────

    @Test
    @Order(1)
    void loginWithValidAdminCredentialsReturnsJwt() throws Exception {
        String token = loginAndGetToken("admin", "AdminPass1!");
        assertNotNull(token);
    }

    @Test
    @Order(2)
    void loginWithValidMerchantCredentialsReturnsJwt() throws Exception {
        String token = loginAndGetToken("merchant1", "MerchPass1!");
        assertNotNull(token);
    }

    @Test
    @Order(3)
    void loginWithWrongPasswordReturns401() throws Exception {
        LoginRequest req = new LoginRequest("admin", "wrong-password");
        mvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(req)))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @Order(4)
    void loginWithUnknownUserReturns401() throws Exception {
        LoginRequest req = new LoginRequest("nonexistent", "pass");
        mvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(req)))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @Order(5)
    void missingTokenReturns401OnProtectedEndpoint() throws Exception {
        mvc.perform(get("/api/payments"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @Order(6)
    void malformedTokenReturns401() throws Exception {
        mvc.perform(get("/api/payments")
                        .header("Authorization", "Bearer this.is.not.valid"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @Order(7)
    void invalidSignatureTokenReturns401() throws Exception {
        String fakeToken = TestJwtHelper.generateWithWrongKey("admin");
        mvc.perform(get("/api/payments")
                        .header("Authorization", "Bearer " + fakeToken))
                .andExpect(status().isUnauthorized());
    }

    // ─── ADMIN RBAC Tests ─────────────────────────────────────────────────────

    @Test
    @Order(10)
    void adminCanListAllPayments() throws Exception {
        String token = loginAndGetToken("admin", "AdminPass1!");
        mvc.perform(get("/api/payments")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(greaterThanOrEqualTo(2))));
    }

    @Test
    @Order(11)
    void adminCanGetMerchantOnePayment() throws Exception {
        String token = loginAndGetToken("admin", "AdminPass1!");
        mvc.perform(get("/api/payments/" + merchantPaymentId)
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.reference").value("PAY-M1-JWT"));
    }

    @Test
    @Order(12)
    void adminCanGetPaymentByReference() throws Exception {
        String token = loginAndGetToken("admin", "AdminPass1!");
        mvc.perform(get("/api/payments/by-reference/PAY-M1-JWT")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk());
    }

    @Test
    @Order(13)
    void adminCanAccessReconciliationEndpoints() throws Exception {
        String token = loginAndGetToken("admin", "AdminPass1!");
        mvc.perform(get("/api/reconciliation-runs")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk());
    }

    // ─── MERCHANT RBAC Tests ──────────────────────────────────────────────────

    @Test
    @Order(20)
    void merchantCanListOwnPayments() throws Exception {
        String token = loginAndGetToken("merchant1", "MerchPass1!");
        mvc.perform(get("/api/payments")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[*].reference", everyItem(is("PAY-M1-JWT"))));
    }

    @Test
    @Order(21)
    void merchantCanGetOwnPaymentById() throws Exception {
        String token = loginAndGetToken("merchant1", "MerchPass1!");
        mvc.perform(get("/api/payments/" + merchantPaymentId)
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.reference").value("PAY-M1-JWT"));
    }

    @Test
    @Order(22)
    void merchantCanGetOwnPaymentByReference() throws Exception {
        String token = loginAndGetToken("merchant1", "MerchPass1!");
        mvc.perform(get("/api/payments/by-reference/PAY-M1-JWT")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk());
    }

    @Test
    @Order(23)
    void merchantCanGetOwnPaymentEvents() throws Exception {
        String token = loginAndGetToken("merchant1", "MerchPass1!");
        mvc.perform(get("/api/payments/" + merchantPaymentId + "/events")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk());
    }

    @Test
    @Order(24)
    void merchantCannotGetOtherMerchantPaymentById() throws Exception {
        String token = loginAndGetToken("merchant1", "MerchPass1!");
        mvc.perform(get("/api/payments/" + merchant2PaymentId)
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isForbidden());
    }

    @Test
    @Order(25)
    void merchantCannotGetOtherMerchantPaymentByReference() throws Exception {
        String token = loginAndGetToken("merchant1", "MerchPass1!");
        mvc.perform(get("/api/payments/by-reference/PAY-M2-JWT")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isForbidden());
    }

    @Test
    @Order(26)
    void merchantCannotGetOtherMerchantPaymentEvents() throws Exception {
        String token = loginAndGetToken("merchant1", "MerchPass1!");
        mvc.perform(get("/api/payments/" + merchant2PaymentId + "/events")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isForbidden());
    }

    @Test
    @Order(27)
    void merchantListDoesNotLeakOtherMerchantsPayments() throws Exception {
        String token = loginAndGetToken("merchant1", "MerchPass1!");
        mvc.perform(get("/api/payments")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[*].reference", not(hasItem("PAY-M2-JWT"))));
    }

    @Test
    @Order(28)
    void merchantCannotQueryOtherMerchantIdViaFilter() throws Exception {
        String token = loginAndGetToken("merchant1", "MerchPass1!");
        mvc.perform(get("/api/payments?merchantId=" + merchant2Id)
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isForbidden());
    }

    @Test
    @Order(30)
    void merchantIsBlockedFromSettlementUpload() throws Exception {
        String token = loginAndGetToken("merchant1", "MerchPass1!");
        mvc.perform(multipart("/api/settlement-files/upload")
                        .file("file", "col1,col2\n".getBytes())
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isForbidden());
    }

    @Test
    @Order(31)
    void merchantIsBlockedFromReconciliationRuns() throws Exception {
        String token = loginAndGetToken("merchant1", "MerchPass1!");
        mvc.perform(get("/api/reconciliation-runs")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isForbidden());
    }

    @Test
    @Order(32)
    void merchantIsBlockedFromMasterDataEndpoints() throws Exception {
        String token = loginAndGetToken("merchant1", "MerchPass1!");
        mvc.perform(get("/api/merchants")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isForbidden());
    }

    // ─── Helper Methods ───────────────────────────────────────────────────────

    private String loginAndGetToken(String username, String password) throws Exception {
        LoginRequest req = new LoginRequest(username, password);
        MvcResult result = mvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(req)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.token").exists())
                .andReturn();
        return objectMapper.readTree(result.getResponse().getContentAsString()).get("token").asText();
    }

    private Role getOrCreateRole(RoleName name) {
        return roleRepository.findByName(name).orElseGet(() -> {
            Role r = new Role();
            r.setName(name);
            return roleRepository.save(r);
        });
    }

    private Merchant createMerchant(String code, String name) {
        Merchant m = new Merchant();
        m.setCode(code);
        m.setName(name);
        m.setStatus(MerchantStatus.ACTIVE);
        return merchantRepository.save(m);
    }

    private void createUser(String username, String password, Merchant merchant, Set<Role> roles) {
        User u = new User();
        u.setUsername(username);
        u.setEmail(username + "@test.example.com");
        u.setPasswordHash(passwordEncoder.encode(password));
        u.setStatus(UserStatus.ACTIVE);
        u.setMerchant(merchant);
        u.setRoles(roles);
        userRepository.save(u);
    }
}
