package com.yourorg.payops.security;

import com.yourorg.payops.entity.User;
import com.yourorg.payops.repository.UserRepository;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Loads user details from UserRepository for authentication.
 */
@Service
public class PayOpsUserDetailsService implements UserDetailsService {

    private final UserRepository userRepository;

    public PayOpsUserDetailsService(UserRepository userRepository) {
        this.userRepository = userRepository;
    }

    @Override
    @Transactional(readOnly = true)
    public UserDetails loadUserByUsername(String username) throws UsernameNotFoundException {
        User user = userRepository.findByUsername(username)
                .orElseThrow(() -> new UsernameNotFoundException("User not found: " + username));
        return new PayOpsUserDetails(user);
    }
}
