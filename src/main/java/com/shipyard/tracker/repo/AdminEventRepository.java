package com.shipyard.tracker.repo;

import com.shipyard.tracker.domain.AdminEvent;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

public interface AdminEventRepository extends JpaRepository<AdminEvent, Long> {
    List<AdminEvent> findTop100ByOrderByOccurredAtDescIdDesc();
}
