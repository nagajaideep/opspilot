package dev.opspilot.inventory;

import dev.opspilot.inventory.InventoryDtos.ClusterView;
import dev.opspilot.inventory.InventoryDtos.ServerView;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/inventory")
@Tag(name = "Inventory", description = "Clusters and servers (read-only for all signed-in users)")
public class InventoryController {
    private final InventoryService inventory;

    public InventoryController(InventoryService inventory) {
        this.inventory = inventory;
    }

    @GetMapping("/clusters")
    @Operation(summary = "List clusters with live utilization")
    public List<ClusterView> clusters() {
        return inventory.listClusters();
    }

    @GetMapping("/clusters/{id}")
    public ClusterView cluster(@PathVariable Long id) {
        return inventory.getCluster(id);
    }

    @GetMapping("/servers")
    @Operation(summary = "List active servers, optionally filtered by cluster")
    public List<ServerView> servers(@RequestParam(required = false) Long clusterId) {
        return inventory.listServers(clusterId);
    }
}
