package com.shipyard.tracker.service;

import com.shipyard.tracker.domain.ActivityType;
import com.shipyard.tracker.domain.ItemStatus;
import com.shipyard.tracker.domain.ItemType;
import java.time.Instant;
import java.util.List;
import java.util.Map;

/** Request and response shapes for the REST API. */
public final class Dtos {

    private Dtos() {
    }

    // ---- responses ----

    public record HullDto(Long id, String code, String name, String color) {
    }

    public record ZoneDto(Long id, String code, String name, String kind, String points) {
    }

    public record ItemDto(
            Long id,
            String name,
            ItemType type,
            String typeLabel,
            boolean batch,
            ItemStatus status,
            Long hullId,
            String hullCode,
            String hullColor,
            Long zoneId,
            String zoneName,
            Long parentId,
            String parentName,
            String tag,
            String tagDescription,
            Integer quantity,
            String unit,
            int childCount,
            Map<String, Object> specs,
            Instant createdAt,
            Instant updatedAt) {
    }

    public record TreeNode(
            Long id,
            String name,
            ItemType type,
            String typeLabel,
            ItemStatus status,
            String tag,
            Integer quantity,
            String unit,
            List<TreeNode> children) {
    }

    public record ActivityDto(
            Long id,
            ActivityType type,
            String fromZone,
            String toZone,
            String actor,
            String note,
            Instant timestamp) {
    }

    public record TypeInfo(String code, String label, boolean batch) {
    }

    public record AreaInfo(String code, String name) {
    }

    public record Meta(List<TypeInfo> types, List<AreaInfo> areas) {
    }

    // ---- requests ----

    public record CreateItemRequest(
            String name,
            ItemType type,
            Long hullId,
            Long zoneId,
            String tag,
            Integer quantity,
            String unit,
            Map<String, Object> specs,
            String note) {
    }

    public record UpdateItemRequest(
            String name,
            Long hullId,
            String tag,
            Integer quantity,
            String unit,
            Map<String, Object> specs,
            String note) {
    }

    public record MoveRequest(Long zoneId, String note) {
    }

    public record AssembleRequest(
            List<Long> childIds,
            String name,
            ItemType type,
            String tag,
            Long zoneId,
            Map<String, Object> specs,
            String note) {
    }
}
