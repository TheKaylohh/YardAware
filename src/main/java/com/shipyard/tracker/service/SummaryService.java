package com.shipyard.tracker.service;

import com.shipyard.tracker.domain.Activity;
import com.shipyard.tracker.domain.Hull;
import com.shipyard.tracker.domain.ItemStatus;
import com.shipyard.tracker.domain.Phase;
import com.shipyard.tracker.domain.Zone;
import com.shipyard.tracker.repo.ActivityRepository;
import com.shipyard.tracker.repo.HullRepository;
import com.shipyard.tracker.repo.ItemRepository;
import com.shipyard.tracker.repo.PhaseRepository;
import com.shipyard.tracker.repo.ZoneRepository;
import com.shipyard.tracker.service.BulkDtos.HullProgress;
import com.shipyard.tracker.service.BulkDtos.PhaseCount;
import com.shipyard.tracker.service.BulkDtos.RecentActivity;
import com.shipyard.tracker.service.BulkDtos.Summary;
import com.shipyard.tracker.service.BulkDtos.ZoneLoad;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** The numbers behind the home page. */
@Service
public class SummaryService {

    private final ItemRepository items;
    private final HullRepository hulls;
    private final ZoneRepository zones;
    private final PhaseRepository phases;
    private final ActivityRepository activities;

    public SummaryService(ItemRepository items, HullRepository hulls, ZoneRepository zones, PhaseRepository phases,
                          ActivityRepository activities) {
        this.items = items;
        this.hulls = hulls;
        this.zones = zones;
        this.phases = phases;
        this.activities = activities;
    }

    @Transactional(readOnly = true)
    public Summary summary() {
        // hull id -> status -> count
        Map<Long, Map<ItemStatus, Long>> byHull = new HashMap<>();
        for (Object[] row : items.countByHullAndStatus()) {
            Long hullId = ((Number) row[0]).longValue();
            ItemStatus status = (ItemStatus) row[1];
            long count = ((Number) row[2]).longValue();
            byHull.computeIfAbsent(hullId, k -> new HashMap<>()).put(status, count);
        }

        List<HullProgress> progress = new ArrayList<>();
        long planned = 0;
        long active = 0;
        long consumed = 0;
        for (Hull hull : hulls.findAllByOrderByCode()) {
            Map<ItemStatus, Long> counts = byHull.getOrDefault(hull.getId(), Map.of());
            long p = counts.getOrDefault(ItemStatus.PLANNED, 0L);
            long a = counts.getOrDefault(ItemStatus.ACTIVE, 0L);
            long c = counts.getOrDefault(ItemStatus.CONSUMED, 0L);
            planned += p;
            active += a;
            consumed += c;
            progress.add(new HullProgress(hull.getId(), hull.getCode(), hull.getName(), hull.getColor(), p, a, c));
        }

        Map<Integer, Long> phaseCounts = new HashMap<>();
        for (Object[] row : items.countByPhase(ItemStatus.ACTIVE)) {
            phaseCounts.put(((Number) row[0]).intValue(), ((Number) row[1]).longValue());
        }
        List<PhaseCount> byPhase = new ArrayList<>();
        for (Phase phase : phases.findAllByOrderByNumber()) {
            byPhase.add(new PhaseCount(phase.getNumber(), phase.getName(), phaseCounts.getOrDefault(phase.getNumber(), 0L)));
        }

        Map<Long, Long> zoneCounts = new HashMap<>();
        for (Object[] row : items.countByZone(ItemStatus.ACTIVE)) {
            zoneCounts.put(((Number) row[0]).longValue(), ((Number) row[1]).longValue());
        }
        List<ZoneLoad> load = new ArrayList<>();
        for (Zone zone : zones.findAllByOrderByName()) {
            load.add(new ZoneLoad(zone.getId(), zone.getCode(), zone.getName(), zoneCounts.getOrDefault(zone.getId(), 0L)));
        }
        load.sort(Comparator.comparingLong(ZoneLoad::count).reversed().thenComparing(ZoneLoad::name));

        List<RecentActivity> recent = new ArrayList<>();
        for (Activity a : activities.findTop12ByOrderByOccurredAtDescIdDesc()) {
            recent.add(new RecentActivity(
                    a.getId(),
                    a.getType().name(),
                    a.getItem().getName(),
                    a.getItem().getHull().getCode(),
                    a.getFromZone() == null ? null : a.getFromZone().getName(),
                    a.getToZone() == null ? null : a.getToZone().getName(),
                    a.getActor(),
                    a.getNote(),
                    a.getOccurredAt()));
        }

        return new Summary(hulls.count(), zones.count(), planned + active + consumed, planned, active, consumed,
                progress, byPhase, load, recent);
    }
}
