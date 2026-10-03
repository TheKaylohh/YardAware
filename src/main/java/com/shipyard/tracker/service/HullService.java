package com.shipyard.tracker.service;

import com.shipyard.tracker.domain.Activity;
import com.shipyard.tracker.domain.ActivityType;
import com.shipyard.tracker.domain.HierarchyNode;
import com.shipyard.tracker.domain.Hull;
import com.shipyard.tracker.domain.Item;
import com.shipyard.tracker.domain.ItemStatus;
import com.shipyard.tracker.domain.NodeLevel;
import com.shipyard.tracker.repo.ActivityRepository;
import com.shipyard.tracker.repo.HierarchyNodeRepository;
import com.shipyard.tracker.repo.HullRepository;
import com.shipyard.tracker.repo.ItemRepository;
import com.shipyard.tracker.repo.PhaseRepository;
import com.shipyard.tracker.service.Dtos.CreateHullRequest;
import com.shipyard.tracker.service.Dtos.HullDto;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

/**
 * Creates ships. A new hull gets one PLANNED item per tracked node of the hierarchy (units, blocks, sections), linked
 * the way the plan links the nodes, each with a CREATED entry in its history. The demo seeder uses the same method,
 * so a hull made through the API is identical to a seeded one.
 */
@Service
public class HullService {

    private static final Pattern CODE = Pattern.compile("[A-Z0-9][A-Z0-9-]{1,19}");
    private static final Pattern COLOR = Pattern.compile("#[0-9A-Fa-f]{6}");
    private static final Pattern UNSAFE_TEXT = Pattern.compile("[\\p{Cc}\\u202A-\\u202E\\u2066-\\u2069]");
    private static final int MAX_NAME_LENGTH = 100;

    private final HullRepository hulls;
    private final HierarchyNodeRepository nodes;
    private final ItemRepository items;
    private final PhaseRepository phases;
    private final ActivityRepository activities;
    private final CurrentUserProvider users;

    public HullService(HullRepository hulls,
                       HierarchyNodeRepository nodes,
                       ItemRepository items,
                       PhaseRepository phases,
                       ActivityRepository activities,
                       CurrentUserProvider users) {
        this.hulls = hulls;
        this.nodes = nodes;
        this.items = items;
        this.phases = phases;
        this.activities = activities;
        this.users = users;
    }

    @Transactional
    public HullDto create(CreateHullRequest req) {
        if (req == null) {
            throw bad("Send a code, a name and a colour.");
        }
        String code = req.code() == null ? "" : req.code().trim().toUpperCase();
        if (!CODE.matcher(code).matches()) {
            throw bad("The ship code must be 2 to 20 characters: capital letters, digits and dashes (for example S044).");
        }
        String name = req.name() == null ? "" : req.name().trim();
        if (name.isEmpty() || name.length() > MAX_NAME_LENGTH || UNSAFE_TEXT.matcher(name).find()) {
            throw bad("The ship name is required, at most " + MAX_NAME_LENGTH + " characters, with no control characters.");
        }
        String color = req.color() == null ? "" : req.color().trim();
        if (!COLOR.matcher(color).matches()) {
            throw bad("The colour must be a hex value such as #2D6CDF.");
        }
        if (hulls.findByCode(code).isPresent()) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "A ship with code " + code + " already exists.");
        }

        Hull hull = hulls.save(new Hull(code, name, color));
        planItems(hull, Instant.now(), users.currentUser(), "Planned from the Aloha class hierarchy");
        return new HullDto(hull.getId(), hull.getCode(), hull.getName(), hull.getColor());
    }

    /**
     * One PLANNED item per tracked node for this hull, parents linked, each with a CREATED activity.
     * Must run inside a transaction. Returns the new items in plan order.
     */
    public List<Item> planItems(Hull hull, Instant created, String actor, String note) {
        List<HierarchyNode> tracked = nodes.findByLevelInOrderBySortOrder(
                Arrays.stream(NodeLevel.values()).filter(NodeLevel::isTracked).toList());
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
            made.add(item);
        }
        items.saveAll(made);

        List<Activity> history = new ArrayList<>(made.size());
        for (Item item : made) {
            Activity a = new Activity();
            a.setItem(item);
            a.setType(ActivityType.CREATED);
            a.setActor(actor);
            a.setNote(note);
            a.setOccurredAt(created);
            history.add(a);
        }
        activities.saveAll(history);
        return made;
    }

    private static ResponseStatusException bad(String message) {
        return new ResponseStatusException(HttpStatus.BAD_REQUEST, message);
    }
}
