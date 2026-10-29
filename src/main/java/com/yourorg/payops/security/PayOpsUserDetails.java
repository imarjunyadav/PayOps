package com.yourorg.payops.security;

import com.yourorg.payops.entity.Role;
import com.yourorg.payops.entity.User;
import com.yourorg.payops.model.UserStatus;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.userdetails.UserDetails;

import java.util.Collection;
import java.util.List;
import java.util.stream.Collectors;

/**
 * Spring Security UserDetails adapter for PayOps User entity.
 * Exposes user ID, merchant ID (for MERCHANT role accounts), and roles.
 */
public class PayOpsUserDetails implements UserDetails {

    private final Long id;
    private final String username;
    private final String password;
    private final Long merchantId;
    private final boolean enabled;
    private final List<GrantedAuthority> authorities;

    public PayOpsUserDetails(User user) {
        this.id = user.getId();
        this.username = user.getUsername();
        this.password = user.getPasswordHash();
        this.merchantId = (user.getMerchant() != null) ? user.getMerchant().getId() : null;
        this.enabled = (user.getStatus() == UserStatus.ACTIVE);
        this.authorities = user.getRoles().stream()
                .map(Role::getName)
                .map(name -> new SimpleGrantedAuthority("ROLE_" + name.name()))
                .collect(Collectors.toList());
    }

    public PayOpsUserDetails(Long id, String username, String password, Long merchantId,
                             boolean enabled, List<GrantedAuthority> authorities) {
        this.id = id;
        this.username = username;
        this.password = password;
        this.merchantId = merchantId;
        this.enabled = enabled;
        this.authorities = authorities;
    }

    public Long getId() {
        return id;
    }

    public Long getMerchantId() {
        return merchantId;
    }

    public boolean hasRole(String roleName) {
        String target = roleName.startsWith("ROLE_") ? roleName : "ROLE_" + roleName;
        return authorities.stream().anyMatch(a -> a.getAuthority().equalsIgnoreCase(target));
    }

    public boolean isAdmin() {
        return hasRole("ADMIN");
    }

    public boolean isMerchant() {
        return hasRole("MERCHANT");
    }

    @Override
    public Collection<? extends GrantedAuthority> getAuthorities() {
        return authorities;
    }

    @Override
    public String getPassword() {
        return password;
    }

    @Override
    public String getUsername() {
        return username;
    }

    @Override
    public boolean isAccountNonExpired() {
        return true;
    }

    @Override
    public boolean isAccountNonLocked() {
        return enabled;
    }

    @Override
    public boolean isCredentialsNonExpired() {
        return true;
    }

    @Override
    public boolean isEnabled() {
        return enabled;
    }
}
