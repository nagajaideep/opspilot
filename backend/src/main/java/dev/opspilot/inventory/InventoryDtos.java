package dev.opspilot.inventory;

import java.time.LocalDate;
import java.util.List;

/**
 * DTOs (Data Transfer Objects) are what the API returns. We never return JPA entities directly:
 * that would leak internal fields, couple the API to the schema, and risk lazy-loading errors.
 */
public final class InventoryDtos {
    private InventoryDtos() { }

    public record ClusterView(Long id, String name, Environment environment, String region, String ownerTeam,
                              int maxServers, long activeServers, double utilization) { }

    public record ServerView(Long id, String hostname, String cluster, int cpuCores, int memoryGb, String osName,
                             LocalDate osEolDate, String owner, List<String> tags) { }
}
