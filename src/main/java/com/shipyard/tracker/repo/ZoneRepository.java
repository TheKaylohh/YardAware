package com.shipyard.tracker.repo;

import com.shipyard.tracker.domain.Zone;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ZoneRepository extends JpaRepository<Zone, Long> {
    Optional<Zone> findByCode(String code);

    List<Zone> findAllByOrderByName();
}
