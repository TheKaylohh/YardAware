package com.shipyard.tracker.repo;

import com.shipyard.tracker.domain.Hull;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface HullRepository extends JpaRepository<Hull, Long> {
    Optional<Hull> findByCode(String code);

    List<Hull> findAllByOrderByCode();
}
