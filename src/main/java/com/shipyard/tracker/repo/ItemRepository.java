package com.shipyard.tracker.repo;

import com.shipyard.tracker.domain.Item;
import com.shipyard.tracker.domain.ItemStatus;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ItemRepository extends JpaRepository<Item, Long> {
    List<Item> findByStatusOrderByName(ItemStatus status);

    List<Item> findByParentOrderByName(Item parent);

    long countByParent(Item parent);

    Optional<Item> findByNameIgnoreCase(String name);
}
