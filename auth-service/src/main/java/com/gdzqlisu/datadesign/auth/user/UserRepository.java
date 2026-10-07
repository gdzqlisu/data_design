package com.gdzqlisu.datadesign.auth.user;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

public interface UserRepository extends JpaRepository<User, Long> {

    List<User> findAllByStatusOrderByCreatedAtAsc(UserStatus status);

    Optional<User> findByDisplayName(String displayName);

    @Query("""
           SELECT u FROM User u
           JOIN UserIdentity i ON i.userId = u.id
           WHERE i.provider = :provider AND i.providerUserId = :providerUserId
           """)
    Optional<User> findByProviderAndProviderUserId(@Param("provider") AuthProvider provider,
                                                   @Param("providerUserId") String providerUserId);
}
