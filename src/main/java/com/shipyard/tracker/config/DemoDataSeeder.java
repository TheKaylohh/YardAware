package com.shipyard.tracker.config;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.shipyard.tracker.domain.Activity;
import com.shipyard.tracker.domain.ActivityType;
import com.shipyard.tracker.domain.HierarchyNode;
import com.shipyard.tracker.domain.Hull;
import com.shipyard.tracker.domain.Item;
import com.shipyard.tracker.domain.ItemStatus;
import com.shipyard.tracker.domain.NodeLevel;
import com.shipyard.tracker.domain.Zone;
import com.shipyard.tracker.repo.ActivityRepository;
import com.shipyard.tracker.repo.HierarchyNodeRepository;
import com.shipyard.tracker.repo.HullRepository;
import com.shipyard.tracker.repo.ItemRepository;
import com.shipyard.tracker.repo.PhaseRepository;
import com.shipyard.tracker.repo.ZoneRepository;
import com.shipyard.tracker.service.ItemService;
import java.io.IOException;
import java.io.InputStream;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.annotation.Order;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Fills an empty database with a yard to track the plan on: the Philly shipyard's 13 zones, 3 hulls, and for every
 * hull one PLANNED item per unit, block and section of the hierarchy (506 each). The plan itself is loaded by
 * {@link HierarchyLoader}.
 *
 * <p>With {@code shipyard.seed-demo-placement=true} (default) two of the ships are then shown mid-build, with every
 * zone busy: S041 in Building Dock 1, S042 in Building Dock 2, S043 still on paper. That progress is made-up demo
 * data from {@code seed/demo-state.json}, produced by {@code tools/make_demo_state.py}; it obeys the same rules the
 * service does and carries a back-dated activity history for every item. The zones come from
 * {@code seed/yard-zones.json} (made by {@code tools/make_map.py} together with the background picture).
 * Delete the ./data folder to re-seed.
 */
@Component
@Order(2)
public class DemoDataSeeder implements ApplicationRunner {

    static final String ZONES_RESOURCE = "seed/yard-zones.json";
    static final String STATE_RESOURCE = "seed/demo-state.json";

    @JsonIgnoreProperties(ignoreUnknown = true)
    record ZoneRow(String code, String name, String kind, String facility, String points) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    record HullRow(String code, String name, String color, int ageDays) {
    }

    /** events: [minutesAgo, type, fromZoneCode, toZoneCode, actor, note], oldest first. */
    @JsonIgnoreProperties(ignoreUnknown = true)
    record ItemRow(String name, String status, String zone, int phase, List<List<Object>> events) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    record StateFile(List<HullRow> hulls, List<ItemRow> items) {
    }

    private final HullRepository hulls;
    private final ZoneRepository zones;
    private final ItemRepository items;
    private final HierarchyNodeRepository nodes;
    private final PhaseRepository phases;
    private final ActivityRepository activities;
    private final TransactionTemplate tx;
    private final ObjectMapper mapper;
    private final boolean enabled;
    private final boolean placeDemoItems;

    public DemoDataSeeder(HullRepository hulls,
                          ZoneRepository zones,
                          ItemRepository items,
                          HierarchyNodeRepository nodes,
                          PhaseRepository phases,
                          ActivityRepository activities,
                          TransactionTemplate tx,
                          ObjectMapper mapper,
                          @Value("${shipyard.seed-demo-data:true}") boolean enabled,
                          @Value("${shipyard.seed-demo-placement:true}") boolean placeDemoItems) {
        this.hulls = hulls;
        this.zones = zones;
        this.items = items;
        this.nodes = nodes;
        this.phases = phases;
        this.activities = activities;
        this.tx = tx;
        this.mapper = mapper;
        this.enabled = enabled;
        this.placeDemoItems = placeDemoItems;
    }

    @Override
    public void run(ApplicationArguments args) throws IOException {
        if (!enabled || hulls.count() > 0) {
            return;
        }
        List<ZoneRow> zoneRows;
        try (InputStream in = new ClassPathResource(ZONES_RESOURCE).getInputStream()) {
            zoneRows = Arrays.asList(mapper.readValue(in, ZoneRow[].class));
        }
        StateFile state;
        try (InputStream in = new ClassPathResource(STATE_RESOURCE).getInputStream()) {
            state = mapper.readValue(in, StateFile.class);
        }
        tx.executeWithoutResult(status -> seed(zoneRows, state));
    }

    private void seed(List<ZoneRow> zoneRows, StateFile state) {
        Map<String, Zone> zoneByCode = new HashMap<>();
        for (ZoneRow z : zoneRows) {
            zoneByCode.put(z.code(), zones.save(new Zone(z.code(), z.name(), z.kind(), z.points(), z.facility())));
        }

        List<HierarchyNode> tracked = nodes.findByLevelInOrderBySortOrder(
                Arrays.stream(NodeLevel.values()).filter(NodeLevel::isTracked).toList());
        Instant now = Instant.now();
        Map<String, Item> itemByName = new HashMap<>();
        for (HullRow row : state.hulls()) {
            Hull hull = hulls.save(new Hull(row.code(), row.name(), row.color()));
            plan(hull, tracked, now.minus(Duration.ofDays(row.ageDays())), itemByName);
        }

        if (placeDemoItems) {
            applyProgress(state.items(), itemByName, zoneByCode, now);
        }
    }

    /** One PLANNED item per tracked node, parents linked the way the plan links the nodes. */
    private void plan(Hull hull, List<HierarchyNode> tracked, Instant created, Map<String, Item> itemByName) {
        Map<Long, Item> itemByNode = new HashMap<>();
        List<Item> made = new ArrayList<>(tracked.size());
        for (HierarchyNode node : tracked) {
            Item item = new Item();
            item.setName(hull.getCode() + "-" + node.getCode());
            item.setHull(hull);
            item.setNode(node);
            item.setParent(node.getParent() == null ? null : itemByNode.get(node.getParent().getId()));
            item.setStatus(ItemStatus.PLANNED);
            item.setPhase(ItemService.firstPhase(node, phases));
            item.setCreatedAt(created);
            item.setUpdatedAt(created);
            itemByNode.put(node.getId(), item);
            itemByName.put(item.getName(), item);
            made.add(item);
        }
        items.saveAll(made);

        List<Activity> history = new ArrayList<>(made.size());
        for (Item item : made) {
            history.add(activity(item, ActivityType.CREATED, null, null, "seed",
                    "Planned from the Aloha class hierarchy", created));
        }
        activities.saveAll(history);
    }

    /** Puts items where the demo state says they are and writes the history that got them there. */
    private void applyProgress(List<ItemRow> rows, Map<String, Item> itemByName, Map<String, Zone> zoneByCode,
                               Instant now) {
        List<Item> changed = new ArrayList<>(rows.size());
        List<Activity> history = new ArrayList<>();
        for (ItemRow row : rows) {
            Item item = itemByName.get(row.name());
            if (item == null) {
                throw new IllegalStateException("Demo state names an item that is not in the plan: " + row.name());
            }
            item.setStatus(ItemStatus.valueOf(row.status()));
            item.setZone(zoneByCode.get(row.zone()));
            item.setPhase(phases.getReferenceById(row.phase()));
            Instant last = null;
            for (List<Object> e : row.events()) {
                Instant at = now.minus(Duration.ofMinutes(((Number) e.get(0)).longValue()));
                history.add(activity(item, ActivityType.valueOf((String) e.get(1)), zoneByCode.get((String) e.get(2)),
                        zoneByCode.get((String) e.get(3)), (String) e.get(4), (String) e.get(5), at));
                last = at;
            }
            if (last != null) {
                item.setUpdatedAt(last);
            }
            changed.add(item);
        }
        items.saveAll(changed);
        activities.saveAll(history);
    }

    private static Activity activity(Item item, ActivityType type, Zone from, Zone to, String actor, String note,
                                     Instant at) {
        Activity a = new Activity();
        a.setItem(item);
        a.setType(type);
        a.setFromZone(from);
        a.setToZone(to);
        a.setActor(actor);
        a.setNote(note);
        a.setOccurredAt(at);
        return a;
    }
}
