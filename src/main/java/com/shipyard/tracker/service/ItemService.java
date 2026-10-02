package com.shipyard.tracker.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.shipyard.tracker.domain.Activity;
import com.shipyard.tracker.domain.ActivityType;
import com.shipyard.tracker.domain.AreaCode;
import com.shipyard.tracker.domain.Hull;
import com.shipyard.tracker.domain.Item;
import com.shipyard.tracker.domain.ItemStatus;
import com.shipyard.tracker.domain.ItemType;
import com.shipyard.tracker.domain.Zone;
import com.shipyard.tracker.repo.ActivityRepository;
import com.shipyard.tracker.repo.HullRepository;
import com.shipyard.tracker.repo.ItemRepository;
import com.shipyard.tracker.repo.ZoneRepository;
import com.shipyard.tracker.service.Dtos.ActivityDto;
import com.shipyard.tracker.service.Dtos.AssembleRequest;
import com.shipyard.tracker.service.Dtos.CreateItemRequest;
import com.shipyard.tracker.service.Dtos.ItemDto;
import com.shipyard.tracker.service.Dtos.MoveRequest;
import com.shipyard.tracker.service.Dtos.TreeNode;
import com.shipyard.tracker.service.Dtos.UpdateItemRequest;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

/** All business rules for items live here so the controller stays thin and the rules are testable. */
@Service
public class ItemService {

    private static final int MAX_SPECS_LENGTH = 4000;
    private static final int MAX_NAME_LENGTH = 120;

    private final ItemRepository items;
    private final HullRepository hulls;
    private final ZoneRepository zones;
    private final ActivityRepository activities;
    private final CurrentUserProvider users;
    private final ObjectMapper mapper;

    public ItemService(ItemRepository items,
                       HullRepository hulls,
                       ZoneRepository zones,
                       ActivityRepository activities,
                       CurrentUserProvider users,
                       ObjectMapper mapper) {
        this.items = items;
        this.hulls = hulls;
        this.zones = zones;
        this.activities = activities;
        this.users = users;
        this.mapper = mapper;
    }

    // ---------------------------------------------------------------- reads

    /** Items that physically exist on the yard, i.e. everything drawn on the map. */
    @Transactional(readOnly = true)
    public List<ItemDto> listOnMap() {
        return items.findByStatusOrderByName(ItemStatus.ACTIVE).stream().map(this::toDto).toList();
    }

    @Transactional(readOnly = true)
    public ItemDto get(Long id) {
        return toDto(find(id));
    }

    /** The item with all of its components, recursively. Consumed components are included. */
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

    @Transactional
    public ItemDto create(CreateItemRequest req) {
        if (req.type() == null) {
            throw bad("Choose an item type.");
        }
        Hull hull = optionalHull(req.hullId());
        Zone zone = requireZone(req.zoneId());
        String tag = normalizeTag(req.tag(), req.type());
        String name = resolveName(req.name(), req.type(), hull, tag);
        ensureNameFree(name, null);

        Item item = new Item();
        item.setName(name);
        item.setType(req.type());
        item.setHull(hull);
        item.setZone(zone);
        item.setTag(tag);
        applyBatch(item, req.quantity(), req.unit());
        item.setSpecs(toJson(req.specs()));
        Instant now = Instant.now();
        item.setCreatedAt(now);
        item.setUpdatedAt(now);
        items.save(item);

        log(item, ActivityType.CREATED, null, zone, noteOr(req.note(), "Added to the yard log"));
        return toDto(item);
    }

    @Transactional
    public ItemDto update(Long id, UpdateItemRequest req) {
        Item item = find(id);
        List<String> changed = new ArrayList<>();

        if (req.name() != null && !req.name().isBlank() && !req.name().trim().equals(item.getName())) {
            String name = req.name().trim();
            checkNameLength(name);
            ensureNameFree(name, item.getId());
            item.setName(name);
            changed.add("name");
        }

        Hull hull = optionalHull(req.hullId());
        if (!Objects.equals(idOf(hull), idOf(item.getHull()))) {
            item.setHull(hull);
            changed.add("hull");
        }

        String tag = normalizeTag(req.tag(), item.getType());
        if (!Objects.equals(tag, item.getTag())) {
            item.setTag(tag);
            changed.add("tag");
        }

        if (item.getType().isBatch()) {
            Integer oldQuantity = item.getQuantity();
            String oldUnit = item.getUnit();
            applyBatch(item, req.quantity(), req.unit());
            if (!Objects.equals(oldQuantity, item.getQuantity()) || !Objects.equals(oldUnit, item.getUnit())) {
                changed.add("quantity");
            }
        }

        String specs = toJson(req.specs());
        if (!Objects.equals(specs, item.getSpecs())) {
            item.setSpecs(specs);
            changed.add("specs");
        }

        if (changed.isEmpty()) {
            return toDto(item);
        }
        item.setUpdatedAt(Instant.now());
        items.save(item);

        String note = "Changed " + String.join(", ", changed);
        String extra = blankToNull(req.note());
        log(item, ActivityType.EDITED, null, null, extra == null ? note : note + ". " + extra);
        return toDto(item);
    }

    @Transactional
    public ItemDto move(Long id, MoveRequest req) {
        Item item = find(id);
        if (item.getStatus() != ItemStatus.ACTIVE) {
            String into = item.getParent() == null ? "a larger item" : item.getParent().getName();
            throw bad(item.getName() + " was assembled into " + into + " and can't be moved on its own.");
        }
        Zone to = requireZone(req.zoneId());
        Zone from = item.getZone();
        if (from != null && from.getId().equals(to.getId())) {
            throw bad(item.getName() + " is already in " + to.getName() + ".");
        }
        item.setZone(to);
        item.setUpdatedAt(Instant.now());
        items.save(item);
        log(item, ActivityType.MOVED, from, to, blankToNull(req.note()));
        return toDto(item);
    }

    /**
     * Joins several items into a new parent. The parent is created and placed on the map; the children
     * are marked CONSUMED (kept for the component tree and their history, but removed from the map).
     */
    @Transactional
    public ItemDto assemble(AssembleRequest req) {
        if (req.type() == null) {
            throw bad("Choose what the assembled item is.");
        }
        if (req.type().isBatch() || req.type() == ItemType.TOOL) {
            throw bad("An assembly can't be a " + req.type().getLabel().toLowerCase() + ".");
        }
        List<Long> ids = req.childIds() == null ? List.<Long>of() : req.childIds().stream().distinct().toList();
        if (ids.size() < 2) {
            throw bad("Select at least two items to assemble.");
        }

        List<Item> children = new ArrayList<>();
        for (Long id : ids) {
            children.add(find(id));
        }
        for (Item child : children) {
            if (child.getStatus() != ItemStatus.ACTIVE) {
                throw bad(child.getName() + " has already been assembled into something else.");
            }
            if (child.getType() == ItemType.TOOL) {
                throw bad(child.getName() + " is a tool and can't be part of an assembly.");
            }
        }

        Set<Long> hullIds = children.stream()
                .map(Item::getHull)
                .filter(Objects::nonNull)
                .map(Hull::getId)
                .collect(Collectors.toSet());
        if (hullIds.size() > 1) {
            throw bad("These items belong to different hulls. Only items from the same hull can be assembled together.");
        }
        Hull hull = children.stream().map(Item::getHull).filter(Objects::nonNull).findFirst().orElse(null);

        Zone zone = req.zoneId() != null ? requireZone(req.zoneId()) : children.get(0).getZone();
        String tag = normalizeTag(req.tag(), req.type());
        String name = resolveName(req.name(), req.type(), hull, tag);
        ensureNameFree(name, null);

        Instant now = Instant.now();
        Item parent = new Item();
        parent.setName(name);
        parent.setType(req.type());
        parent.setHull(hull);
        parent.setZone(zone);
        parent.setTag(tag);
        parent.setSpecs(toJson(req.specs()));
        parent.setCreatedAt(now);
        parent.setUpdatedAt(now);
        items.save(parent);

        String childNames = children.stream().map(Item::getName).collect(Collectors.joining(", "));
        String extra = blankToNull(req.note());
        String note = "Assembled from " + childNames;
        log(parent, ActivityType.ASSEMBLED, null, zone, extra == null ? note : note + ". " + extra);

        for (Item child : children) {
            Zone lastZone = child.getZone();
            child.setStatus(ItemStatus.CONSUMED);
            child.setParent(parent);
            child.setUpdatedAt(now);
            items.save(child);
            log(child, ActivityType.CONSUMED, lastZone, null, "Assembled into " + parent.getName());
        }
        return toDto(parent);
    }

    // -------------------------------------------------------------- helpers

    private Item find(Long id) {
        return items.findById(id)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "No item with id " + id));
    }

    private Zone requireZone(Long zoneId) {
        if (zoneId == null) {
            throw bad("Choose a zone.");
        }
        return zones.findById(zoneId).orElseThrow(() -> bad("That zone doesn't exist."));
    }

    private Hull optionalHull(Long hullId) {
        if (hullId == null) {
            return null;
        }
        return hulls.findById(hullId).orElseThrow(() -> bad("That hull doesn't exist."));
    }

    private static Long idOf(Hull hull) {
        return hull == null ? null : hull.getId();
    }

    /** Trims and upper-cases the tag and checks it. Grand blocks must have one; other types may. */
    private String normalizeTag(String raw, ItemType type) {
        String tag = raw == null ? "" : raw.trim().toUpperCase();
        if (tag.isEmpty()) {
            if (type == ItemType.GRAND_BLOCK) {
                throw bad("Grand blocks need an area tag such as EA500.");
            }
            return null;
        }
        if (AreaCode.areaOf(tag).isEmpty()) {
            String areas = Stream.of(AreaCode.values()).map(Enum::name).collect(Collectors.joining(", "));
            throw bad("The tag needs an area code plus three digits, like EA500. Area codes: " + areas + ".");
        }
        return tag;
    }

    /** Uses the typed name, or builds one like S041-EA500 for grand blocks when the name is left blank. */
    private String resolveName(String raw, ItemType type, Hull hull, String tag) {
        String name = raw == null ? "" : raw.trim();
        if (name.isEmpty()) {
            if (type == ItemType.GRAND_BLOCK && hull != null && tag != null) {
                return hull.getCode() + "-" + tag;
            }
            throw bad("Give the item a name.");
        }
        checkNameLength(name);
        return name;
    }

    private void checkNameLength(String name) {
        if (name.length() > MAX_NAME_LENGTH) {
            throw bad("Names can be at most " + MAX_NAME_LENGTH + " characters.");
        }
    }

    private void ensureNameFree(String name, Long ownId) {
        boolean taken = items.findByNameIgnoreCase(name)
                .filter(other -> !other.getId().equals(ownId))
                .isPresent();
        if (taken) {
            throw bad("An item named " + name + " already exists.");
        }
    }

    private void applyBatch(Item item, Integer quantity, String unit) {
        if (item.getType().isBatch()) {
            if (quantity == null || quantity <= 0) {
                throw bad("Batches need a quantity above zero.");
            }
            item.setQuantity(quantity);
            String cleanUnit = blankToNull(unit);
            item.setUnit(cleanUnit == null ? "pcs" : cleanUnit);
        } else {
            item.setQuantity(null);
            item.setUnit(null);
        }
    }

    private void log(Item item, ActivityType type, Zone from, Zone to, String note) {
        Activity a = new Activity();
        a.setItem(item);
        a.setType(type);
        a.setFromZone(from);
        a.setToZone(to);
        a.setActor(users.currentUser());
        a.setNote(note);
        a.setOccurredAt(Instant.now());
        activities.save(a);
    }

    private static String blankToNull(String s) {
        return s == null || s.isBlank() ? null : s.trim();
    }

    private static String noteOr(String note, String fallback) {
        String clean = blankToNull(note);
        return clean == null ? fallback : clean;
    }

    private String toJson(Map<String, Object> specs) {
        if (specs == null || specs.isEmpty()) {
            return null;
        }
        try {
            String json = mapper.writeValueAsString(specs);
            if (json.length() > MAX_SPECS_LENGTH) {
                throw bad("The specs are too long. Keep them under " + MAX_SPECS_LENGTH + " characters.");
            }
            return json;
        } catch (JsonProcessingException e) {
            throw bad("The specs couldn't be saved: " + e.getOriginalMessage());
        }
    }

    private Map<String, Object> fromJson(String json) {
        if (json == null || json.isBlank()) {
            return Map.of();
        }
        try {
            return mapper.readValue(json, new TypeReference<LinkedHashMap<String, Object>>() { });
        } catch (JsonProcessingException e) {
            return Map.of();
        }
    }

    private ItemDto toDto(Item i) {
        Hull h = i.getHull();
        Zone z = i.getZone();
        Item p = i.getParent();
        return new ItemDto(
                i.getId(),
                i.getName(),
                i.getType(),
                i.getType().getLabel(),
                i.getType().isBatch(),
                i.getStatus(),
                h == null ? null : h.getId(),
                h == null ? null : h.getCode(),
                h == null ? null : h.getColor(),
                z == null ? null : z.getId(),
                z == null ? null : z.getName(),
                p == null ? null : p.getId(),
                p == null ? null : p.getName(),
                i.getTag(),
                AreaCode.describe(i.getTag()).orElse(null),
                i.getQuantity(),
                i.getUnit(),
                (int) items.countByParent(i),
                fromJson(i.getSpecs()),
                i.getCreatedAt(),
                i.getUpdatedAt());
    }

    private TreeNode toNode(Item item) {
        List<TreeNode> kids = items.findByParentOrderByName(item).stream().map(this::toNode).toList();
        return new TreeNode(
                item.getId(),
                item.getName(),
                item.getType(),
                item.getType().getLabel(),
                item.getStatus(),
                item.getTag(),
                item.getQuantity(),
                item.getUnit(),
                kids);
    }

    private static ResponseStatusException bad(String message) {
        return new ResponseStatusException(HttpStatus.BAD_REQUEST, message);
    }
}
