package dev.opspilot.inventory;

import dev.opspilot.common.NotFoundException;
import dev.opspilot.inventory.InventoryDtos.ClusterView;
import dev.opspilot.inventory.InventoryDtos.ServerView;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Arrays;
import java.util.List;

@Service
@Transactional(readOnly = true)   // read-only transactions let Hibernate skip dirty checking
public class InventoryService {
    private final ClusterRepository clusters;
    private final ServerRepository servers;

    public InventoryService(ClusterRepository clusters, ServerRepository servers) {
        this.clusters = clusters;
        this.servers = servers;
    }

    public List<ClusterView> listClusters() {
        return clusters.findAll().stream().map(this::toView).toList();
    }

    public ClusterView getCluster(Long id) {
        return toView(findCluster(id));
    }

    public Cluster findCluster(Long id) {
        return clusters.findById(id).orElseThrow(() -> new NotFoundException("Cluster " + id + " not found"));
    }

    public long activeServerCount(Long clusterId) {
        return servers.countByClusterIdAndStatus(clusterId, Server.Status.ACTIVE);
    }

    public List<ServerView> listServers(Long clusterId) {
        var list = clusterId == null
                ? servers.findAllActiveWithCluster()
                : servers.findByClusterIdAndStatusOrderByHostname(clusterId, Server.Status.ACTIVE);
        return list.stream().map(InventoryService::toView).toList();
    }

    private ClusterView toView(Cluster c) {
        long active = activeServerCount(c.getId());
        double util = Math.round(1000.0 * active / c.getMaxServers()) / 10.0;
        return new ClusterView(c.getId(), c.getName(), c.getEnvironment(), c.getRegion(), c.getOwnerTeam(),
                c.getMaxServers(), active, util);
    }

    static ServerView toView(Server s) {
        List<String> tags = s.getTags().isBlank() ? List.of()
                : Arrays.stream(s.getTags().split(",")).map(String::trim).toList();
        return new ServerView(s.getId(), s.getHostname(), s.getCluster().getName(), s.getCpuCores(),
                s.getMemoryGb(), s.getOsName(), s.getOsEolDate(), s.getOwner(), tags);
    }
}
