package com.yourorg.payops.repository;

import com.yourorg.payops.entity.Role;
import com.yourorg.payops.model.*;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface RoleRepository extends JpaRepository<Role, Long> {
    Optional<Role> findByName(RoleName name);
}
