package com.railfeas.user;

import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface UserRepository extends JpaRepository<User, Long> {
    Optional<User> findByEmail(String email);

    Optional<User> findByProviderAndProviderUid(AuthProvider provider, String providerUid);

    boolean existsByEmail(String email);
}
