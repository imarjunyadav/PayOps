package com.yourorg.payops.repository;

import com.yourorg.payops.entity.SwitchDatabase;
import com.yourorg.payops.model.*;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface SwitchDatabaseRepository extends JpaRepository<SwitchDatabase, Long> {
    Optional<SwitchDatabase> findByCode(String code);
    List<SwitchDatabase> findByStatusOrderByPriorityAsc(SwitchStatus status);
}
