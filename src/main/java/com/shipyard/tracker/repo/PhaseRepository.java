package com.shipyard.tracker.repo;

import com.shipyard.tracker.domain.Phase;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

public interface PhaseRepository extends JpaRepository<Phase, Integer> {
    List<Phase> findAllByOrderByNumber();
}
