package com.shipyard.tracker.repo;

import com.shipyard.tracker.domain.Area;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface AreaRepository extends JpaRepository<Area, Long> {
    Optional<Area> findByCode(String code);

    List<Area> findAllByOrderBySortOrder();
}
