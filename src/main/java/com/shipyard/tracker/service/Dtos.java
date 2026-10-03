package com.shipyard.tracker.service;

import com.shipyard.tracker.domain.ActivityType;
import com.shipyard.tracker.domain.ItemStatus;
import com.shipyard.tracker.domain.NodeLevel;
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

    public record ZoneDto(Long id, String code, String name, String kind, String facility, String points) {
    }

    public record PhaseDto(int number, String name, String facility, String appliesTo) {
    }

    public record AreaDto(String code, String name, String function, String confidence, String note) {
    }

    public record DesignBasisDto(String item, String value, String status, String source) {
    }

    /** One row of the plan (hierarchy), shared by every hull. */
    public record NodeDto(
            Long id,
            String code,
            NodeLevel level,
            String levelLabel,
            String parentCode,
            String areaCode,
            String areaName,
            String band,
            String latitude,
            String side,
            int primaryPhase,
            String primaryPhaseName,
            String phaseRoute,
            String assemblyFacility,
            Integer erectionOrder,
            Boolean outfitHeavy,
            String function,
            String confidence,
            String note) {
    }

    /** A hull's tracked piece of the plan: where it is, which phase it is in, what it is made of. */
    public record ItemDto(
            Long id,
            String name,
            String nodeCode,
            NodeLevel level,
            String levelLabel,
            ItemStatus status,
            Long hullId,
            String hullCode,
            String hullColor,
            Long zoneId,
            String zoneName,
            Long parentId,
            String parentName,
            String areaCode,
            String areaName,
            String band,
            String latitude,
            String side,
            int phaseNumber,
            String phaseName,
            String phaseFacility,
            String phaseRoute,
            List<Integer> routePhases,
            Integer erectionOrder,
            Boolean outfitHeavy,
            String function,
            String confidence,
            String note,
            int childCount,
            Map<String, Object> specs,
            Instant createdAt,
            Instant updatedAt) {
    }

    public record TreeNode(
            Long id,
            String name,
            NodeLevel level,
            String levelLabel,
            ItemStatus status,
            String zoneName,
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

    public record LevelInfo(String code, String label, boolean tracked) {
    }

    public record Meta(List<LevelInfo> levels, List<AreaDto> areas, List<PhaseDto> phases) {
    }

    // ---- requests ----

    /** Admin only: a new ship. The server creates its planned items from the hierarchy. */
    public record CreateHullRequest(String code, String name, String color) {
    }

    /** Put a PLANNED item on the yard for the first time. */
    public record PlaceRequest(Long zoneId, String note) {
    }

    public record UpdateItemRequest(Map<String, Object> specs, String note) {
    }

    public record MoveRequest(Long zoneId, String note) {
    }

    public record PhaseRequest(Integer phase, String note) {
    }

    /** Join all pieces of one planned parent into it. {@code zoneId} defaults to the first piece's zone. */
    public record AssembleRequest(List<Long> childIds, Long zoneId, String note) {
    }
}
