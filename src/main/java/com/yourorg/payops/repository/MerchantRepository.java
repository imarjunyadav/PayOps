package com.yourorg.payops.repository;

import com.yourorg.payops.entity.Merchant;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface MerchantRepository extends JpaRepository<Merchant, Long> {
    Optional<Merchant> findByCode(String code);
}
