package com.shipyard.tracker.repo;

import com.shipyard.tracker.domain.Hull;
import com.shipyard.tracker.domain.Item;
import com.shipyard.tracker.domain.ItemStatus;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface ItemRepository extends JpaRepository<Item, Long> {
    List<Item> findByStatusOrderByName(ItemStatus status);

    List<Item> findByStatusAndHullOrderByName(ItemStatus status, Hull hull);

    List<Item> findByParentOrderByNodeSortOrder(Item parent);

    long countByParent(Item parent);

    long countByParentAndStatusNot(Item parent, ItemStatus status);

    Optional<Item> findByNameIgnoreCase(String name);

    long countByHull(Hull hull);

    /** Every item with everything the data grid shows, in one query. */
    @Query("select i from Item i join fetch i.hull join fetch i.node n left join fetch n.area "
            + "left join fetch i.zone join fetch i.phase left join fetch i.parent order by i.name")
    List<Item> findAllForGrid();

    /** Rows of (hull id, status, count). */
    @Query("select i.hull.id, i.status, count(i) from Item i group by i.hull.id, i.status")
    List<Object[]> countByHullAndStatus();

    /** Rows of (phase number, count) for items with the given status. */
    @Query("select i.phase.number, count(i) from Item i where i.status = :status group by i.phase.number")
    List<Object[]> countByPhase(@Param("status") ItemStatus status);

    /** Rows of (zone id, count) for items with the given status. */
    @Query("select i.zone.id, count(i) from Item i where i.status = :status and i.zone is not null group by i.zone.id")
    List<Object[]> countByZone(@Param("status") ItemStatus status);
}
