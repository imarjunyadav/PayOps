package com.yourorg.payops.controller;

import com.yourorg.payops.entity.Merchant;
import com.yourorg.payops.entity.Payment;
import com.yourorg.payops.entity.PaymentEvent;
import com.yourorg.payops.entity.SwitchDatabase;
import com.yourorg.payops.exception.BusinessRuleException;
import com.yourorg.payops.exception.ConflictException;
import com.yourorg.payops.exception.ResourceNotFoundException;
import com.yourorg.payops.model.PaymentStatus;
import com.yourorg.payops.model.SwitchStatus;
import com.yourorg.payops.repository.MerchantRepository;
import com.yourorg.payops.repository.PaymentEventRepository;
import com.yourorg.payops.repository.PaymentRepository;
import com.yourorg.payops.repository.SwitchDatabaseRepository;
import jakarta.validation.ConstraintViolation;
import jakarta.validation.ConstraintViolationException;
import jakarta.validation.Validator;
import java.math.BigDecimal;
import java.net.URI;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import jakarta.persistence.criteria.Predicate;
import java.util.Set;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.http.ResponseEntity;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import com.yourorg.payops.security.PayOpsUserDetails;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.servlet.support.ServletUriComponentsBuilder;

@RestController
@RequestMapping("/api/payments")
public class PaymentController {
    private final PaymentRepository payments;
    private final MerchantRepository merchants;
    private final SwitchDatabaseRepository switches;
    private final PaymentEventRepository events;
    private final Validator validator;
    private final com.yourorg.payops.service.PaymentService paymentService;

    public PaymentController(PaymentRepository payments, MerchantRepository merchants,
                             SwitchDatabaseRepository switches, PaymentEventRepository events,
                             Validator validator) {
        this(payments, merchants, switches, events, validator, null);
    }

    @org.springframework.beans.factory.annotation.Autowired
    public PaymentController(PaymentRepository payments, MerchantRepository merchants,
                             SwitchDatabaseRepository switches, PaymentEventRepository events,
                             Validator validator, com.yourorg.payops.service.PaymentService paymentService) {
        this.payments = payments;
        this.merchants = merchants;
        this.switches = switches;
        this.events = events;
        this.validator = validator;
        this.paymentService = paymentService;
    }

    private PayOpsUserDetails getCurrentPrincipal() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth != null && auth.getPrincipal() instanceof PayOpsUserDetails details) {
            return details;
        }
        return null;
    }

    private void enforceMerchantAccess(Payment payment) {
        PayOpsUserDetails principal = getCurrentPrincipal();
        if (principal != null && principal.isMerchant() && !principal.isAdmin()) {
            Long userMerchantId = principal.getMerchantId();
            if (userMerchantId == null || payment.getMerchant() == null || !payment.getMerchant().getId().equals(userMerchantId)) {
                throw new AccessDeniedException("Access denied: cannot access payment belonging to another merchant");
            }
        }
    }

    @GetMapping
    public List<Payment> list(@RequestParam(required = false) PaymentStatus status,
                              @RequestParam(required = false) Long merchantId,
                              @RequestParam(required = false) LocalDateTime from,
                              @RequestParam(required = false) LocalDateTime to) {
        if (from != null && to != null && from.isAfter(to)) {
            throw new BusinessRuleException("from must be before or equal to to");
        }

        PayOpsUserDetails principal = getCurrentPrincipal();
        Long effectiveMerchantId = merchantId;
        if (principal != null && principal.isMerchant() && !principal.isAdmin()) {
            Long userMerchantId = principal.getMerchantId();
            if (userMerchantId == null) {
                throw new AccessDeniedException("Authenticated merchant user is not associated with any merchant ID");
            }
            if (merchantId != null && !merchantId.equals(userMerchantId)) {
                throw new AccessDeniedException("Merchants cannot query payments of other merchants");
            }
            effectiveMerchantId = userMerchantId;
        }

        final Long queryMerchantId = effectiveMerchantId;
        Specification<Payment> filters = (root, query, builder) -> {
            List<Predicate> predicates = new ArrayList<>();
            if (status != null) predicates.add(builder.equal(root.get("status"), status));
            if (queryMerchantId != null) predicates.add(builder.equal(root.get("merchant").get("id"), queryMerchantId));
            if (from != null) predicates.add(builder.greaterThanOrEqualTo(root.get("createdAt"), from));
            if (to != null) predicates.add(builder.lessThanOrEqualTo(root.get("createdAt"), to));
            return builder.and(predicates.toArray(Predicate[]::new));
        };
        return payments.findAll(filters, Sort.by(Sort.Direction.DESC, "createdAt"));
    }

    @GetMapping("/{id}")
    public Payment get(@PathVariable Long id) {
        Payment payment = payments.findById(id).orElseThrow(() -> new ResourceNotFoundException("Payment", id));
        enforceMerchantAccess(payment);
        return payment;
    }

    @GetMapping("/by-reference/{reference}")
    public Payment getByReference(@PathVariable String reference) {
        Payment payment = payments.findByReference(reference)
                .orElseThrow(() -> new ResourceNotFoundException("Payment", reference));
        enforceMerchantAccess(payment);
        return payment;
    }

    /** Creates/processes a payment. Delegates to PaymentService when available. */
    @PostMapping
    public ResponseEntity<Payment> create(@org.springframework.web.bind.annotation.RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKeyHeader,
                                          @RequestBody Payment body) {
        PayOpsUserDetails principal = getCurrentPrincipal();
        if (principal != null && principal.isMerchant() && !principal.isAdmin()) {
            Long userMerchantId = principal.getMerchantId();
            if (userMerchantId == null) {
                throw new AccessDeniedException("Authenticated merchant user is not associated with any merchant ID");
            }
            if (body.getMerchant() != null && body.getMerchant().getId() != null && !body.getMerchant().getId().equals(userMerchantId)) {
                throw new AccessDeniedException("Merchants can only initiate payments for their own merchant account");
            }
            // Securely derive merchant identity from authenticated principal
            Merchant merchant = merchants.findById(userMerchantId)
                    .orElseThrow(() -> new AccessDeniedException("Associated merchant not found"));
            body.setMerchant(merchant);
        }

        if (paymentService != null) {
            Payment processed = paymentService.processPayment(idempotencyKeyHeader, body);
            URI location = ServletUriComponentsBuilder.fromCurrentRequest().path("/{id}")
                    .buildAndExpand(processed.getId()).toUri();
            return ResponseEntity.created(location).body(processed);
        }
        return legacyCreate(body);
    }

    public ResponseEntity<Payment> create(Payment body) {
        return create(null, body);
    }

    @Transactional
    public ResponseEntity<Payment> legacyCreate(Payment body) {
        if (body.getMerchant() == null || body.getMerchant().getId() == null) {
            reject("merchant", "must identify an existing merchant");
        }
        Merchant merchant = merchants.findById(body.getMerchant().getId())
                .orElseThrow(() -> new ResourceNotFoundException("Merchant", body.getMerchant().getId()));
        if (merchant.getStatus() != com.yourorg.payops.model.MerchantStatus.ACTIVE) {
            throw new BusinessRuleException("Payments can only be initiated for active merchants");
        }
        if (body.getStatus() != null && body.getStatus() != PaymentStatus.INITIATED) {
            throw new BusinessRuleException("New payments must start in INITIATED status");
        }
        if (body.getRefundedAmount() != null && body.getRefundedAmount().compareTo(BigDecimal.ZERO) != 0) {
            throw new BusinessRuleException("A new payment cannot have a refunded amount");
        }

        SwitchDatabase route = resolveActiveRoute(merchant);
        body.setMerchant(merchant);
        body.setSwitchDatabase(route);
        body.setId(null);
        body.setStatus(PaymentStatus.INITIATED);
        body.setRefundedAmount(BigDecimal.ZERO);
        body.setSwitchReference(null);
        body.setFailureReason(null);
        body.setCreatedAt(null);
        body.setCreatedBy(null);
        body.setUpdatedAt(null);
        validate(body);

        if (payments.findByReference(body.getReference()).isPresent()) {
            throw new ConflictException("Payment reference already exists");
        }
        Payment saved = payments.save(body);
        PaymentEvent initialEvent = new PaymentEvent();
        initialEvent.setPayment(saved);
        initialEvent.setEventType(PaymentStatus.INITIATED);
        initialEvent.setMessage("Payment initiated");
        events.save(initialEvent);

        URI location = ServletUriComponentsBuilder.fromCurrentRequest().path("/{id}")
                .buildAndExpand(saved.getId()).toUri();
        return ResponseEntity.created(location).body(saved);
    }

    private SwitchDatabase resolveActiveRoute(Merchant merchant) {
        SwitchDatabase preferred = merchant.getDefaultSwitch();
        if (preferred != null && preferred.getStatus() == SwitchStatus.ACTIVE) {
            return switches.findById(preferred.getId())
                    .orElseThrow(() -> new ResourceNotFoundException("SwitchDatabase", preferred.getId()));
        }
        return switches.findByStatusOrderByPriorityAsc(SwitchStatus.ACTIVE).stream().findFirst()
                .orElseThrow(() -> new BusinessRuleException("No active switch route is available"));
    }

    private void validate(Payment payment) {
        Set<ConstraintViolation<Payment>> violations = validator.validate(payment);
        if (violations.isEmpty()) return;
        throw new ConstraintViolationException(violations);
    }

    private void reject(String field, String message) {
        throw new ConstraintViolationException(field + " " + message, java.util.Set.of());
    }
}
