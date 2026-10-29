package com.yourorg.payops.controller;

import com.yourorg.payops.entity.SwitchDatabase;
import com.yourorg.payops.exception.ConflictException;
import com.yourorg.payops.exception.ResourceNotFoundException;
import com.yourorg.payops.model.SwitchStatus;
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
@RequestMapping("/api/switches")
public class SwitchDatabaseController {
    private final SwitchDatabaseRepository switches;

    public SwitchDatabaseController(SwitchDatabaseRepository switches) {
        this.switches = switches;
    }

    @GetMapping
    public List<SwitchDatabase> list() {
        return switches.findAll();
    }

    @GetMapping("/{id}")
    public SwitchDatabase get(@PathVariable Long id) {
        return find(id);
    }

    @PostMapping
    public ResponseEntity<SwitchDatabase> create(@Valid @RequestBody SwitchDatabase body) {
        if (switches.findByCode(body.getCode()).isPresent()) {
            throw new ConflictException("Switch code already exists");
        }
        body.setId(null);
        body.setCreatedAt(null);
        body.setCreatedBy(null);
        body.setUpdatedAt(null);
        SwitchDatabase saved = switches.save(body);
        URI location = ServletUriComponentsBuilder.fromCurrentRequest().path("/{id}")
                .buildAndExpand(saved.getId()).toUri();
        return ResponseEntity.created(location).body(saved);
    }

    @PutMapping("/{id}")
    public SwitchDatabase replace(@PathVariable Long id, @Valid @RequestBody SwitchDatabase body) {
        SwitchDatabase current = find(id);
        switches.findByCode(body.getCode()).filter(existing -> !existing.getId().equals(id))
                .ifPresent(existing -> { throw new ConflictException("Switch code already exists"); });
        body.setId(current.getId());
        body.setCreatedAt(current.getCreatedAt());
        body.setCreatedBy(current.getCreatedBy());
        body.setUpdatedAt(current.getUpdatedAt());
        return switches.save(body);
    }

    @PatchMapping("/{id}/status")
    public SwitchDatabase changeStatus(@PathVariable Long id, @RequestBody SwitchStatus status) {
        SwitchDatabase current = find(id);
        if (status == null) {
            throw new com.yourorg.payops.exception.BusinessRuleException("Status is required");
        }
        current.setStatus(status);
        return switches.save(current);
    }

    private SwitchDatabase find(Long id) {
        return switches.findById(id).orElseThrow(() -> new ResourceNotFoundException("SwitchDatabase", id));
    }
}
