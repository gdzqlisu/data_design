package com.gdzqlisu.datadesign.auth.user;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface UserIdentityRepository extends JpaRepository<UserIdentity, Long> {

    List<UserIdentity> findAllByUserIdOrderByCreatedAtAsc(Long userId);

    Optional<UserIdentity> findByProviderAndProviderUserId(AuthProvider provider, String providerUserId);
}
