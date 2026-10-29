package com.yourorg.payops.controller;

import com.yourorg.payops.entity.Account;
import com.yourorg.payops.entity.Merchant;
import com.yourorg.payops.exception.ConflictException;
import com.yourorg.payops.exception.ResourceNotFoundException;
import com.yourorg.payops.repository.AccountRepository;
import com.yourorg.payops.repository.MerchantRepository;
import jakarta.validation.Valid;
import java.net.URI;
import java.util.List;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.support.ServletUriComponentsBuilder;

@RestController
@RequestMapping("/api/accounts")
public class AccountController {
    private final AccountRepository accounts;
    private final MerchantRepository merchants;

    public AccountController(AccountRepository accounts, MerchantRepository merchants) {
        this.accounts = accounts;
        this.merchants = merchants;
    }

    @GetMapping
    public List<Account> list() {
        return accounts.findAll();
    }

    @GetMapping("/{id}")
    public Account get(@PathVariable Long id) {
        return find(id);
    }

    @PostMapping
    public ResponseEntity<Account> create(@Valid @RequestBody Account body) {
        if (accounts.findByCode(body.getCode()).isPresent()) {
            throw new ConflictException("Account code already exists");
        }
        body.setId(null);
        body.setCreatedAt(null);
        body.setCreatedBy(null);
        body.setUpdatedAt(null);
        body.setMerchant(resolveMerchant(body.getMerchant()));
        Account saved = accounts.save(body);
        URI location = ServletUriComponentsBuilder.fromCurrentRequest().path("/{id}")
                .buildAndExpand(saved.getId()).toUri();
        return ResponseEntity.created(location).body(saved);
    }

    @PutMapping("/{id}")
    public Account replace(@PathVariable Long id, @Valid @RequestBody Account body) {
        Account current = find(id);
        accounts.findByCode(body.getCode()).filter(existing -> !existing.getId().equals(id))
                .ifPresent(existing -> { throw new ConflictException("Account code already exists"); });
        body.setId(id);
        body.setCreatedAt(current.getCreatedAt());
        body.setCreatedBy(current.getCreatedBy());
        body.setUpdatedAt(current.getUpdatedAt());
        body.setMerchant(resolveMerchant(body.getMerchant()));
        return accounts.save(body);
    }

    @PatchMapping("/{id}/active")
    public Account changeActive(@PathVariable Long id, @RequestBody Boolean active) {
        Account current = find(id);
        if (active == null) {
            throw new com.yourorg.payops.exception.BusinessRuleException("Active flag is required");
        }
        current.setActive(active);
        return accounts.save(current);
    }

    private Account find(Long id) {
        return accounts.findById(id).orElseThrow(() -> new ResourceNotFoundException("Account", id));
    }

    private Merchant resolveMerchant(Merchant supplied) {
        if (supplied == null) return null;
        if (supplied.getId() == null) throw new ResourceNotFoundException("Merchant", null);
        return merchants.findById(supplied.getId())
                .orElseThrow(() -> new ResourceNotFoundException("Merchant", supplied.getId()));
    }
}
