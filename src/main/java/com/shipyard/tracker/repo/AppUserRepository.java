package com.shipyard.tracker.repo;

import com.shipyard.tracker.domain.AppUser;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface AppUserRepository extends JpaRepository<AppUser, Long> {
    Optional<AppUser> findByUsername(String username);

    Optional<AppUser> findByEmail(String email);

    /** A person added by e-mail who has not signed in yet. */
    Optional<AppUser> findByEmailAndUsernameIsNull(String email);
}
