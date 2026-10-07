package com.shipyard.tracker.config;

import com.shipyard.tracker.domain.Zone;
import com.shipyard.tracker.repo.ZoneRepository;
import java.io.IOException;
import java.io.InputStream;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.annotation.Order;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.ObjectMapper;

/**
 * Loads the yard's zones (the outlines on the map) on first start, in every environment, production included.
 * Zones are the layout of the yard, not demo data, so they do not depend on {@code shipyard.seed-demo-data}.
 * Only runs when the zones table is empty; set {@code shipyard.seed-zones=false} to manage zones yourself.
 */
@Component
@Order(1)
public class ZoneLoader implements ApplicationRunner {

    private final ZoneRepository zones;
    private final TransactionTemplate tx;
    private final ObjectMapper mapper;
    private final boolean enabled;

    public ZoneLoader(ZoneRepository zones,
                      TransactionTemplate tx,
                      ObjectMapper mapper,
                      @Value("${shipyard.seed-zones:true}") boolean enabled) {
        this.zones = zones;
        this.tx = tx;
        this.mapper = mapper;
        this.enabled = enabled;
    }

    @Override
    public void run(ApplicationArguments args) throws IOException {
        if (!enabled || zones.count() > 0) {
            return;
        }
        DemoDataSeeder.ZoneRow[] rows;
        try (InputStream in = new ClassPathResource(DemoDataSeeder.ZONES_RESOURCE).getInputStream()) {
            rows = mapper.readValue(in, DemoDataSeeder.ZoneRow[].class);
        }
        tx.executeWithoutResult(status -> {
            for (DemoDataSeeder.ZoneRow z : rows) {
                zones.save(new Zone(z.code(), z.name(), z.kind(), z.points(), z.facility()));
            }
        });
    }
}
