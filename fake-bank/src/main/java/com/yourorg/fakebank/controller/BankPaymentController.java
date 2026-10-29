package com.yourorg.fakebank.controller;

import com.yourorg.fakebank.dto.BankPaymentRequest;
import com.yourorg.fakebank.dto.BankPaymentResponse;
import com.yourorg.fakebank.exception.BankIdempotencyConflictException;
import com.yourorg.fakebank.service.BankPaymentService;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/bank/payments")
public class BankPaymentController {

    private final BankPaymentService paymentService;

    public BankPaymentController(BankPaymentService paymentService) {
        this.paymentService = paymentService;
    }

    @PostMapping
    public ResponseEntity<BankPaymentResponse> authorize(@Valid @RequestBody BankPaymentRequest request) {
        BankPaymentResponse response = paymentService.authorize(request);
        return ResponseEntity.ok(response);
    }

    @GetMapping("/{requestId}")
    public ResponseEntity<BankPaymentResponse> getStatus(@PathVariable String requestId) {
        return paymentService.getStatus(requestId)
                .map(ResponseEntity::ok)
                .orElseGet(() -> ResponseEntity.notFound().build());
    }

    @ExceptionHandler(BankIdempotencyConflictException.class)
    public ResponseEntity<BankPaymentResponse> handleIdempotencyConflict(BankIdempotencyConflictException ex) {
        return ResponseEntity.status(HttpStatus.CONFLICT).body(
                new BankPaymentResponse(null, null, "CONFLICT", null, "409", ex.getMessage())
        );
    }
}
