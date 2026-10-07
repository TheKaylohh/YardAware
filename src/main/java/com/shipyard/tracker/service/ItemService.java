package com.shipyard.tracker.service;

import com.shipyard.tracker.domain.Activity;
import com.shipyard.tracker.domain.ActivityType;
import com.shipyard.tracker.domain.Area;
import com.shipyard.tracker.domain.Hull;
import com.shipyard.tracker.domain.HierarchyNode;
import com.shipyard.tracker.domain.Item;
import com.shipyard.tracker.domain.ItemStatus;
import com.shipyard.tracker.domain.Phase;
import com.shipyard.tracker.domain.PhaseRoute;
import com.shipyard.tracker.domain.Zone;
import com.shipyard.tracker.repo.ActivityRepository;
import com.shipyard.tracker.repo.HullRepository;
import com.shipyard.tracker.repo.ItemRepository;
import com.shipyard.tracker.repo.PhaseRepository;
import com.shipyard.tracker.repo.ZoneRepository;
import com.shipyard.tracker.service.Dtos.ActivityDto;
import com.shipyard.tracker.service.Dtos.AssembleRequest;
import com.shipyard.tracker.service.Dtos.ItemDto;
import com.shipyard.tracker.service.Dtos.MoveRequest;
import com.shipyard.tracker.service.Dtos.PhaseRequest;
import com.shipyard.tracker.service.Dtos.PlaceRequest;
import com.shipyard.tracker.service.Dtos.TreeNode;
import com.shipyard.tracker.service.Dtos.UpdateItemRequest;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;
import tools.jackson.core.JacksonException;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;

/**
 * All business rules for items live here so the controller stays thin and the rules are testable.
 *
 * <p>The structure of the ship is fixed by the plan (the hierarchy tables), so items are never created or renamed
 * here. They move through a lifecycle instead: PLANNED -> placed on the yard (ACTIVE) -> moved between zones and
 * production phases -> joined into their planned parent (CONSUMED), which then becomes ACTIVE itself.
 */
@Service
public class ItemService {

    private static final int MAX_SPECS_LENGTH = 4000;
    private static final int MAX_NOTE_LENGTH = 500;
    private static final int MAX_ASSEMBLY_PARTS = 50;
    private static final int MAX_SPEC_ENTRIES = 50;
    private static final int MAX_SPEC_KEY_LENGTH = 80;
    private static final int MAX_SPEC_VALUE_LENGTH = 500;
    /** Activity.note column size. Generated notes ("Assembled from ...") are cut to fit instead of failing. */
    private static final int ACTIVITY_NOTE_COLUMN = 1000;
    /** Control characters and bidirectional overrides, which can disguise notes in the audit trail. */
    private static final Pattern UNSAFE_TEXT = Pattern.compile("[\\p{Cc}\\u202A-\\u202E\\u2066-\\u2069]");

    private final ItemRepository items;
    private final HullRepository hulls;
    private final ZoneRepository zones;
    private final PhaseRepository phases;
    private final ActivityRepository activities;
    private final CurrentUserProvider users;
    private final ObjectMapper mapper;

    public ItemService(ItemRepository items,
                       HullRepository hulls,
                       ZoneRepository zones,
                       PhaseRepository phases,
                       ActivityRepository activities,
                       CurrentUserProvider users,
                       ObjectMapper mapper) {
        this.items = items;
        this.hulls = hulls;
        this.zones = zones;
        this.phases = phases;
        this.activities = activities;
        this.users = users;
        this.mapper = mapper;
    }

    // ---------------------------------------------------------------- reads

    /** Items that physically exist on the yard, i.e. everything drawn on the map. */
    @Transactional(readOnly = true)
    public List<ItemDto> listOnMap() {
        return list(ItemStatus.ACTIVE, null);
    }

    /** Items with the given status (default ACTIVE), optionally for one hull. */
    @Transactional(readOnly = true)
    public List<ItemDto> list(ItemStatus status, Long hullId) {
        ItemStatus wanted = status == null ? ItemStatus.ACTIVE : status;
        List<Item> found = hullId == null
                ? items.findByStatusOrderByName(wanted)
                : items.findByStatusAndHullOrderByName(wanted, requireHull(hullId));
        return found.stream().map(this::toDto).toList();
    }

    @Transactional(readOnly = true)
    public ItemDto get(Long id) {
        return toDto(find(id));
    }

    /** The item with everything planned below it, recursively. Consumed and planned pieces are included. */
    @Transactional(readOnly = true)
    public TreeNode tree(Long id) {
        return toNode(find(id));
    }

    @Transactional(readOnly = true)
    public List<ActivityDto> activity(Long id) {
        Item item = find(id);
        return activities.findByItemOrderByOccurredAtDescIdDesc(item).stream()
                .map(a -> new ActivityDto(
                        a.getId(),
                        a.getType(),
                        a.getFromZone() == null ? null : a.getFromZone().getName(),
                        a.getToZone() == null ? null : a.getToZone().getName(),
                        a.getActor(),
                        a.getNote(),
                        a.getOccurredAt()))
                .toList();
    }

    // --------------------------------------------------------------- writes

    /** Puts a planned item on the yard. It starts in the first phase of its route. */
    @Transactional
    public ItemDto place(Long id, PlaceRequest req) {
        String note = userNote(req == null ? null : req.note());   // check what the user typed before looking at state
        Item item = find(id);
        if (item.getStatus() != ItemStatus.PLANNED) {
            throw bad(item.getName() + " is already on the yard or has been built into something else.");
        }
        Item parent = item.getParent();
        if (parent != null && parent.getStatus() != ItemStatus.PLANNED) {
            throw bad(parent.getName() + " has already been built, so " + item.getName() + " can't be placed on its own.");
        }
        Zone zone = requireZone(req == null ? null : req.zoneId());

        item.setStatus(ItemStatus.ACTIVE);
        item.setZone(zone);
        item.setUpdatedAt(Instant.now());
        items.save(item);
        log(item, ActivityType.PLACED, null, zone, noteOr(note, "Placed on the yard"));
        return toDto(item);
    }

    /** Only the free-form specs are editable: identity, hull and structure come from the plan. */
    @Transactional
    public ItemDto update(Long id, UpdateItemRequest req) {
        Item item = find(id);
        String specs = toJson(req.specs());
        if (Objects.equals(specs, item.getSpecs())) {
            return toDto(item);
        }
        item.setSpecs(specs);
        item.setUpdatedAt(Instant.now());
        items.save(item);
        String extra = userNote(req.note());
        log(item, ActivityType.EDITED, null, null, extra == null ? "Changed specs" : "Changed specs. " + extra);
        return toDto(item);
    }

    @Transactional
    public ItemDto move(Long id, MoveRequest req) {
        Item item = requireActive(find(id), "moved");
        Zone to = requireZone(req.zoneId());
        Zone from = item.getZone();
        if (from != null && from.getId().equals(to.getId())) {
            throw bad(item.getName() + " is already in " + to.getName() + ".");
        }
        item.setZone(to);
        item.setUpdatedAt(Instant.now());
        items.save(item);
        log(item, ActivityType.MOVED, from, to, userNote(req.note()));
        return toDto(item);
    }

    /** Moves an item to another phase of its own route (for example section assembly -> section outfitting). */
    @Transactional
    public ItemDto changePhase(Long id, PhaseRequest req) {
        Item item = requireActive(find(id), "changed phase");
        if (req.phase() == null) {
            throw bad("Choose a phase.");
        }
        List<Integer> route = PhaseRoute.parse(item.getNode().getPhaseRoute());
        if (!route.contains(req.phase())) {
            throw bad("Phase " + req.phase() + " isn't on the route of " + item.getName() + " ("
                    + item.getNode().getPhaseRoute() + ").");
        }
        Phase to = phases.findById(req.phase()).orElseThrow(() -> bad("That phase doesn't exist."));
        Phase from = item.getPhase();
        if (from.getNumber().equals(to.getNumber())) {
            throw bad(item.getName() + " is already in phase " + to.getNumber() + ", " + to.getName() + ".");
        }
        item.setPhase(to);
        item.setUpdatedAt(Instant.now());
        items.save(item);

        String note = "Phase " + from.getNumber() + " " + from.getName() + " to " + to.getNumber() + " " + to.getName();
        String extra = userNote(req.note());
        log(item, ActivityType.PHASE_CHANGED, null, null, extra == null ? note : note + ". " + extra);
        return toDto(item);
    }

    /**
     * Joins pieces into their planned parent (sections into a block, blocks into a unit). All pieces of the parent must
     * be on the yard and selected together. The pieces are marked CONSUMED (kept for the tree and their history,
     * removed from the map) and the parent becomes ACTIVE in the chosen zone.
     */
    @Transactional
    public ItemDto assemble(AssembleRequest req) {
        List<Long> ids = req.childIds() == null ? List.<Long>of() : req.childIds().stream().distinct().toList();
        if (ids.size() < 2) {
            throw bad("Select at least two items to assemble.");
        }
        if (ids.size() > MAX_ASSEMBLY_PARTS) {
            throw bad("An assembly can have at most " + MAX_ASSEMBLY_PARTS + " parts at a time.");
        }
        String extra = userNote(req.note());
        List<Item> children = ids.stream().map(this::find).toList();

        for (Item child : children) {
            if (child.getParent() == null) {
                throw bad(child.getName() + " is a unit, the top of the hierarchy, so it can't be assembled into anything.");
            }
            if (child.getStatus() == ItemStatus.PLANNED) {
                throw bad(child.getName() + " isn't on the yard yet. Place it first.");
            }
            if (child.getStatus() == ItemStatus.CONSUMED) {
                throw bad(child.getName() + " has already been assembled into " + child.getParent().getName() + ".");
            }
        }

        Item parent = children.get(0).getParent();
        for (Item child : children) {
            if (!child.getParent().getId().equals(parent.getId())) {
                throw bad("These items belong to different parents (" + parent.getName() + ", "
                        + child.getParent().getName() + "). Pick the pieces of one block or unit at a time.");
            }
        }
        if (parent.getStatus() != ItemStatus.PLANNED) {
            throw bad(parent.getName() + " has already been built.");
        }
        long expected = items.countByParent(parent);
        if (children.size() != expected) {
            String missing = items.findByParentOrderByNodeSortOrder(parent).stream()
                    .filter(sibling -> ids.stream().noneMatch(id -> id.equals(sibling.getId())))
                    .map(Item::getName)
                    .collect(Collectors.joining(", "));
            throw bad(parent.getName() + " is made of " + expected + " pieces and you selected " + children.size()
                    + ". Missing: " + missing + ".");
        }

        Zone zone = req.zoneId() != null ? requireZone(req.zoneId()) : children.get(0).getZone();
        Instant now = Instant.now();
        Phase first = firstPhase(parent.getNode());

        parent.setStatus(ItemStatus.ACTIVE);
        parent.setZone(zone);
        parent.setPhase(first);
        parent.setUpdatedAt(now);
        items.save(parent);

        String childNames = children.stream().map(Item::getName).collect(Collectors.joining(", "));
        String note = "Assembled from " + childNames;
        log(parent, ActivityType.ASSEMBLED, null, zone, extra == null ? note : note + ". " + extra);

        for (Item child : children) {
            Zone lastZone = child.getZone();
            child.setStatus(ItemStatus.CONSUMED);
            child.setUpdatedAt(now);
            items.save(child);
            log(child, ActivityType.CONSUMED, lastZone, null, "Assembled into " + parent.getName());
        }
        return toDto(parent);
    }

    // -------------------------------------------------------------- helpers

    /** First phase of the node's route; the primary phase for nodes without a route. */
    public static Phase firstPhase(HierarchyNode node, PhaseRepository phases) {
        List<Integer> route = PhaseRoute.parse(node.getPhaseRoute());
        return route.isEmpty() ? node.getPrimaryPhase() : phases.getReferenceById(route.get(0));
    }

    private Phase firstPhase(HierarchyNode node) {
        return firstPhase(node, phases);
    }

    private Item find(Long id) {
        return items.findById(id)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "No item with id " + id));
    }

    private Item requireActive(Item item, String verb) {
        if (item.getStatus() == ItemStatus.PLANNED) {
            throw bad(item.getName() + " isn't on the yard yet, so it can't be " + verb + ". Place it first.");
        }
        if (item.getStatus() == ItemStatus.CONSUMED) {
            throw bad(item.getName() + " was assembled into " + item.getParent().getName() + " and can't be " + verb + " on its own.");
        }
        return item;
    }

    private Zone requireZone(Long zoneId) {
        if (zoneId == null) {
            throw bad("Choose a zone.");
        }
        return zones.findById(zoneId).orElseThrow(() -> bad("That zone doesn't exist."));
    }

    private Hull requireHull(Long hullId) {
        return hulls.findById(hullId).orElseThrow(() -> bad("That hull doesn't exist."));
    }

    private void log(Item item, ActivityType type, Zone from, Zone to, String note) {
        Activity a = new Activity();
        a.setItem(item);
        a.setType(type);
        a.setFromZone(from);
        a.setToZone(to);
        a.setActor(users.currentUser());
        a.setNote(note != null && note.length() > ACTIVITY_NOTE_COLUMN
                ? note.substring(0, ACTIVITY_NOTE_COLUMN - 3) + "..." : note);
        a.setOccurredAt(Instant.now());
        activities.save(a);
    }

    private static String blankToNull(String s) {
        return s == null || s.isBlank() ? null : s.trim();
    }

    /** A note typed by the user: optional, limited in length, no control characters. */
    private static String userNote(String note) {
        String clean = blankToNull(note);
        if (clean == null) {
            return null;
        }
        if (clean.length() > MAX_NOTE_LENGTH) {
            throw bad("Notes can be at most " + MAX_NOTE_LENGTH + " characters.");
        }
        return checkText(clean, "Notes");
    }

    private static String checkText(String value, String label) {
        if (value != null && UNSAFE_TEXT.matcher(value).find()) {
            throw bad(label + " can't contain control or text-direction characters.");
        }
        return value;
    }

    /** Specs are a flat list of "name: value" pairs; values are text, numbers or true/false. */
    private static void validateSpecs(Map<String, Object> specs) {
        if (specs.size() > MAX_SPEC_ENTRIES) {
            throw bad("An item can have at most " + MAX_SPEC_ENTRIES + " specs.");
        }
        for (Map.Entry<String, Object> entry : specs.entrySet()) {
            String key = entry.getKey();
            if (key == null || key.isBlank() || key.length() > MAX_SPEC_KEY_LENGTH) {
                throw bad("Spec names must be 1 to " + MAX_SPEC_KEY_LENGTH + " characters.");
            }
            checkText(key, "Spec names");
            Object value = entry.getValue();
            if (value instanceof String text) {
                if (text.length() > MAX_SPEC_VALUE_LENGTH) {
                    throw bad("The spec " + key + " is longer than " + MAX_SPEC_VALUE_LENGTH + " characters.");
                }
                checkText(text, "Spec values");
            } else if (value != null && !(value instanceof Number) && !(value instanceof Boolean)) {
                throw bad("The spec " + key + " must be text, a number or true/false.");
            }
        }
    }

    private static String noteOr(String note, String fallback) {
        String clean = blankToNull(note);
        return clean == null ? fallback : clean;
    }

    private String toJson(Map<String, Object> specs) {
        if (specs == null || specs.isEmpty()) {
            return null;
        }
        validateSpecs(specs);
        try {
            String json = mapper.writeValueAsString(specs);
            if (json.length() > MAX_SPECS_LENGTH) {
                throw bad("The specs are too long. Keep them under " + MAX_SPECS_LENGTH + " characters.");
            }
            return json;
        } catch (JacksonException e) {
            throw bad("The specs couldn't be saved: " + e.getOriginalMessage());
        }
    }

    private Map<String, Object> fromJson(String json) {
        if (json == null || json.isBlank()) {
            return Map.of();
        }
        try {
            return mapper.readValue(json, new TypeReference<LinkedHashMap<String, Object>>() { });
        } catch (JacksonException e) {
            return Map.of();
        }
    }

    private ItemDto toDto(Item i) {
        Hull h = i.getHull();
        Zone z = i.getZone();
        Item p = i.getParent();
        HierarchyNode n = i.getNode();
        Area a = n.getArea();
        Phase ph = i.getPhase();
        return new ItemDto(
                i.getId(),
                i.getName(),
                n.getCode(),
                n.getLevel(),
                n.getLevel().getLabel(),
                i.getStatus(),
                h.getId(),
                h.getCode(),
                h.getColor(),
                z == null ? null : z.getId(),
                z == null ? null : z.getName(),
                p == null ? null : p.getId(),
                p == null ? null : p.getName(),
                a == null ? null : a.getCode(),
                a == null ? null : a.getName(),
                n.getBand(),
                n.getLatitude(),
                n.getSide(),
                ph.getNumber(),
                ph.getName(),
                ph.getFacility(),
                n.getPhaseRoute(),
                PhaseRoute.parse(n.getPhaseRoute()),
                n.getErectionOrder(),
                n.getOutfitHeavy(),
                n.getFunction(),
                n.getConfidence(),
                n.getNote(),
                (int) items.countByParent(i),
                fromJson(i.getSpecs()),
                i.getCreatedAt(),
                i.getUpdatedAt());
    }

    private TreeNode toNode(Item item) {
        List<TreeNode> kids = items.findByParentOrderByNodeSortOrder(item).stream().map(this::toNode).toList();
        return new TreeNode(
                item.getId(),
                item.getName(),
                item.getNode().getLevel(),
                item.getNode().getLevel().getLabel(),
                item.getStatus(),
                item.getZone() == null ? null : item.getZone().getName(),
                kids);
    }

    private static ResponseStatusException bad(String message) {
        return new ResponseStatusException(HttpStatus.BAD_REQUEST, message);
    }
}
