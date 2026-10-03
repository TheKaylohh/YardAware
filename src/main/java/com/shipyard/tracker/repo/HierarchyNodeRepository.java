package com.shipyard.tracker.repo;

import com.shipyard.tracker.domain.HierarchyNode;
import com.shipyard.tracker.domain.NodeLevel;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface HierarchyNodeRepository extends JpaRepository<HierarchyNode, Long> {
    Optional<HierarchyNode> findByCode(String code);

    List<HierarchyNode> findAllByOrderBySortOrder();

    List<HierarchyNode> findByAreaCodeOrderBySortOrder(String areaCode);

    List<HierarchyNode> findByLevelInOrderBySortOrder(List<NodeLevel> levels);

    long countByLevel(NodeLevel level);
}
