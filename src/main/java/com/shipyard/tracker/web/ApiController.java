package com.shipyard.tracker.web;

import com.shipyard.tracker.domain.ItemStatus;
import com.shipyard.tracker.repo.HullRepository;
import com.shipyard.tracker.repo.ZoneRepository;
import com.shipyard.tracker.service.Dtos.ActivityDto;
import com.shipyard.tracker.service.Dtos.AreaDto;
import com.shipyard.tracker.service.Dtos.AssembleRequest;
import com.shipyard.tracker.service.Dtos.DesignBasisDto;
import com.shipyard.tracker.service.Dtos.HullDto;
import com.shipyard.tracker.service.Dtos.ItemDto;
import com.shipyard.tracker.service.Dtos.Meta;
import com.shipyard.tracker.service.Dtos.MoveRequest;
import com.shipyard.tracker.service.Dtos.NodeDto;
import com.shipyard.tracker.service.Dtos.PhaseDto;
import com.shipyard.tracker.service.Dtos.PhaseRequest;
import com.shipyard.tracker.service.Dtos.PlaceRequest;
import com.shipyard.tracker.service.Dtos.TreeNode;
import com.shipyard.tracker.service.Dtos.UpdateItemRequest;
import com.shipyard.tracker.service.Dtos.ZoneDto;
import com.shipyard.tracker.service.HierarchyService;
import com.shipyard.tracker.service.ItemService;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

@RestController
@RequestMapping("/api")
public class ApiController {

    private final ItemService itemService;
    private final HierarchyService hierarchy;
    private final HullRepository hulls;
    private final ZoneRepository zones;

    public ApiController(ItemService itemService, HierarchyService hierarchy, HullRepository hulls, ZoneRepository zones) {
        this.itemService = itemService;
        this.hierarchy = hierarchy;
        this.hulls = hulls;
        this.zones = zones;
    }

    /** Static lookup data: hierarchy levels, ship areas and production phases. */
    @GetMapping("/meta")
    public Meta meta() {
        return hierarchy.meta();
    }

    @GetMapping("/phases")
    public List<PhaseDto> phases() {
        return hierarchy.phases();
    }

    @GetMapping("/areas")
    public List<AreaDto> areas() {
        return hierarchy.areas();
    }

    @GetMapping("/design-basis")
    public List<DesignBasisDto> designBasis() {
        return hierarchy.designBasis();
    }

    /** The plan (units, blocks, sections and milestones), optionally for one area: /api/hierarchy?area=BA */
    @GetMapping("/hierarchy")
    public List<NodeDto> hierarchy(@RequestParam(name = "area", required = false) String area) {
        return hierarchy.nodes(area);
    }

    @GetMapping("/hulls")
    public List<HullDto> hulls() {
        return hulls.findAllByOrderByCode().stream()
                .map(h -> new HullDto(h.getId(), h.getCode(), h.getName(), h.getColor()))
                .toList();
    }

    @GetMapping("/zones")
    public List<ZoneDto> zones() {
        return zones.findAllByOrderByName().stream()
                .map(z -> new ZoneDto(z.getId(), z.getCode(), z.getName(), z.getKind(), z.getFacility(), z.getPoints()))
                .toList();
    }

    /**
     * Items on the yard (default). {@code status=PLANNED} lists what is still to be built, {@code CONSUMED} what has been
     * joined into a parent. {@code hullId} limits the list to one ship.
     */
    @GetMapping("/items")
    public List<ItemDto> items(@RequestParam(name = "status", required = false) String status,
                               @RequestParam(name = "hullId", required = false) Long hullId) {
        return itemService.list(parseStatus(status), hullId);
    }

    @GetMapping("/items/{id}")
    public ItemDto item(@PathVariable("id") Long id) {
        return itemService.get(id);
    }

    @GetMapping("/items/{id}/tree")
    public TreeNode tree(@PathVariable("id") Long id) {
        return itemService.tree(id);
    }

    @GetMapping("/items/{id}/activity")
    public List<ActivityDto> activity(@PathVariable("id") Long id) {
        return itemService.activity(id);
    }

    @PostMapping("/items/{id}/place")
    public ItemDto place(@PathVariable("id") Long id, @RequestBody PlaceRequest request) {
        return itemService.place(id, request);
    }

    @PutMapping("/items/{id}")
    public ItemDto update(@PathVariable("id") Long id, @RequestBody UpdateItemRequest request) {
        return itemService.update(id, request);
    }

    @PostMapping("/items/{id}/move")
    public ItemDto move(@PathVariable("id") Long id, @RequestBody MoveRequest request) {
        return itemService.move(id, request);
    }

    @PostMapping("/items/{id}/phase")
    public ItemDto phase(@PathVariable("id") Long id, @RequestBody PhaseRequest request) {
        return itemService.changePhase(id, request);
    }

    @PostMapping("/items/assemble")
    @ResponseStatus(HttpStatus.CREATED)
    public ItemDto assemble(@RequestBody AssembleRequest request) {
        return itemService.assemble(request);
    }

    private static ItemStatus parseStatus(String status) {
        if (status == null || status.isBlank()) {
            return null;
        }
        try {
            return ItemStatus.valueOf(status.trim().toUpperCase());
        } catch (IllegalArgumentException e) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Status must be PLANNED, ACTIVE or CONSUMED.");
        }
    }
}
