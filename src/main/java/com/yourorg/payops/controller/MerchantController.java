package com.yourorg.payops.controller;

import com.yourorg.payops.entity.Merchant;
import com.yourorg.payops.entity.SwitchDatabase;
import com.yourorg.payops.exception.ConflictException;
import com.yourorg.payops.exception.ResourceNotFoundException;
import com.yourorg.payops.model.MerchantStatus;
import com.yourorg.payops.repository.MerchantRepository;
import com.yourorg.payops.repository.SwitchDatabaseRepository;
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
@RequestMapping("/api/merchants")
public class MerchantController {
    private final MerchantRepository merchants;
    private final SwitchDatabaseRepository switches;

    public MerchantController(MerchantRepository merchants, SwitchDatabaseRepository switches) {
        this.merchants = merchants;
        this.switches = switches;
    }

    @GetMapping
    public List<Merchant> list() {
        return merchants.findAll();
    }

    @GetMapping("/{id}")
    public Merchant get(@PathVariable Long id) {
        return find(id);
    }

    @PostMapping
    public ResponseEntity<Merchant> create(@Valid @RequestBody Merchant body) {
        if (merchants.findByCode(body.getCode()).isPresent()) {
            throw new ConflictException("Merchant code already exists");
        }
        body.setId(null);
        body.setCreatedAt(null);
        body.setCreatedBy(null);
        body.setUpdatedAt(null);
        body.setDefaultSwitch(resolveSwitch(body.getDefaultSwitch()));
        Merchant saved = merchants.save(body);
        URI location = ServletUriComponentsBuilder.fromCurrentRequest().path("/{id}")
                .buildAndExpand(saved.getId()).toUri();
        return ResponseEntity.created(location).body(saved);
    }

    @PutMapping("/{id}")
    public Merchant replace(@PathVariable Long id, @Valid @RequestBody Merchant body) {
        Merchant current = find(id);
        merchants.findByCode(body.getCode()).filter(existing -> !existing.getId().equals(id))
                .ifPresent(existing -> { throw new ConflictException("Merchant code already exists"); });
        body.setId(id);
        body.setCreatedAt(current.getCreatedAt());
        body.setCreatedBy(current.getCreatedBy());
        body.setUpdatedAt(current.getUpdatedAt());
        body.setDefaultSwitch(resolveSwitch(body.getDefaultSwitch()));
        return merchants.save(body);
    }

    @PatchMapping("/{id}/status")
    public Merchant changeStatus(@PathVariable Long id, @RequestBody MerchantStatus status) {
        Merchant current = find(id);
        if (status == null) {
            throw new com.yourorg.payops.exception.BusinessRuleException("Status is required");
        }
        current.setStatus(status);
        return merchants.save(current);
    }

    private Merchant find(Long id) {
        return merchants.findById(id).orElseThrow(() -> new ResourceNotFoundException("Merchant", id));
    }

    private SwitchDatabase resolveSwitch(SwitchDatabase supplied) {
        if (supplied == null) return null;
        if (supplied.getId() == null) throw new ResourceNotFoundException("SwitchDatabase", null);
        return switches.findById(supplied.getId())
                .orElseThrow(() -> new ResourceNotFoundException("SwitchDatabase", supplied.getId()));
    }
}
