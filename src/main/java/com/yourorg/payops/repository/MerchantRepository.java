package com.yourorg.payops.repository;

import com.yourorg.payops.entity.Merchant;
import com.yourorg.payops.model.*;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface MerchantRepository extends JpaRepository<Merchant, Long> {
    Optional<Merchant> findByCode(String code);
}
