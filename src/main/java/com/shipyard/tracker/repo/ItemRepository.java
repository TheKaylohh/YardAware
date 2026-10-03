package com.shipyard.tracker.repo;

import com.shipyard.tracker.domain.Hull;
import com.shipyard.tracker.domain.Item;
import com.shipyard.tracker.domain.ItemStatus;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ItemRepository extends JpaRepository<Item, Long> {
    List<Item> findByStatusOrderByName(ItemStatus status);

    List<Item> findByStatusAndHullOrderByName(ItemStatus status, Hull hull);

    List<Item> findByParentOrderByNodeSortOrder(Item parent);

    long countByParent(Item parent);

    long countByParentAndStatusNot(Item parent, ItemStatus status);

    Optional<Item> findByNameIgnoreCase(String name);

    long countByHull(Hull hull);
}
