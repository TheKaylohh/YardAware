package com.shipyard.tracker.repo;

import com.shipyard.tracker.domain.Activity;
import com.shipyard.tracker.domain.Item;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ActivityRepository extends JpaRepository<Activity, Long> {
    List<Activity> findByItemOrderByOccurredAtDescIdDesc(Item item);
}
