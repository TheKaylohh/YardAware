package com.shipyard.tracker.repo;

import com.shipyard.tracker.domain.DesignBasis;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

public interface DesignBasisRepository extends JpaRepository<DesignBasis, Long> {
    List<DesignBasis> findAllByOrderBySortOrder();
}
