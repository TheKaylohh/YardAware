package com.shipyard.tracker.web;

import com.shipyard.tracker.domain.AreaCode;
import com.shipyard.tracker.domain.ItemType;
import com.shipyard.tracker.repo.HullRepository;
import com.shipyard.tracker.repo.ZoneRepository;
import com.shipyard.tracker.service.Dtos.ActivityDto;
import com.shipyard.tracker.service.Dtos.AreaInfo;
import com.shipyard.tracker.service.Dtos.AssembleRequest;
import com.shipyard.tracker.service.Dtos.CreateItemRequest;
import com.shipyard.tracker.service.Dtos.HullDto;
import com.shipyard.tracker.service.Dtos.ItemDto;
import com.shipyard.tracker.service.Dtos.Meta;
import com.shipyard.tracker.service.Dtos.MoveRequest;
import com.shipyard.tracker.service.Dtos.TreeNode;
import com.shipyard.tracker.service.Dtos.TypeInfo;
import com.shipyard.tracker.service.Dtos.UpdateItemRequest;
import com.shipyard.tracker.service.Dtos.ZoneDto;
import com.shipyard.tracker.service.ItemService;
import java.util.List;
import java.util.stream.Stream;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api")
public class ApiController {

    private final ItemService itemService;
    private final HullRepository hulls;
    private final ZoneRepository zones;

    public ApiController(ItemService itemService, HullRepository hulls, ZoneRepository zones) {
        this.itemService = itemService;
        this.hulls = hulls;
        this.zones = zones;
    }

    /** Static lookup data: item types and ship area codes. */
    @GetMapping("/meta")
    public Meta meta() {
        List<TypeInfo> types = Stream.of(ItemType.values())
                .map(t -> new TypeInfo(t.name(), t.getLabel(), t.isBatch()))
                .toList();
        List<AreaInfo> areas = Stream.of(AreaCode.values())
                .map(a -> new AreaInfo(a.name(), a.getLabel()))
                .toList();
        return new Meta(types, areas);
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
                .map(z -> new ZoneDto(z.getId(), z.getCode(), z.getName(), z.getKind(), z.getPoints()))
                .toList();
    }

    /** Everything currently on the yard (consumed components are excluded). */
    @GetMapping("/items")
    public List<ItemDto> items() {
        return itemService.listOnMap();
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

    @PostMapping("/items")
    @ResponseStatus(HttpStatus.CREATED)
    public ItemDto create(@RequestBody CreateItemRequest request) {
        return itemService.create(request);
    }

    @PutMapping("/items/{id}")
    public ItemDto update(@PathVariable("id") Long id, @RequestBody UpdateItemRequest request) {
        return itemService.update(id, request);
    }

    @PostMapping("/items/{id}/move")
    public ItemDto move(@PathVariable("id") Long id, @RequestBody MoveRequest request) {
        return itemService.move(id, request);
    }

    @PostMapping("/items/assemble")
    @ResponseStatus(HttpStatus.CREATED)
    public ItemDto assemble(@RequestBody AssembleRequest request) {
        return itemService.assemble(request);
    }
}
