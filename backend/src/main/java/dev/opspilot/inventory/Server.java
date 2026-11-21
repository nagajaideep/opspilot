package dev.opspilot.inventory;

import jakarta.persistence.*;
import java.time.LocalDate;

@Entity
@Table(name = "server")
public class Server {
    public enum Status { ACTIVE, DECOMMISSIONED }

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, unique = true)
    private String hostname;

    // LAZY: don't load the cluster row unless we actually touch server.getCluster()
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "cluster_id")
    private Cluster cluster;

    @Column(name = "cpu_cores", nullable = false)
    private int cpuCores;

    @Column(name = "memory_gb", nullable = false)
    private int memoryGb;

    @Column(name = "os_name", nullable = false)
    private String osName;

    @Column(name = "os_eol_date")
    private LocalDate osEolDate;

    private String owner;

    @Column(nullable = false)
    private String tags = "";

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private Status status = Status.ACTIVE;

    protected Server() { }

    public Server(String hostname, Cluster cluster, int cpuCores, int memoryGb, String osName,
                  LocalDate osEolDate, String owner, String tags) {
        this.hostname = hostname;
        this.cluster = cluster;
        this.cpuCores = cpuCores;
        this.memoryGb = memoryGb;
        this.osName = osName;
        this.osEolDate = osEolDate;
        this.owner = owner;
        this.tags = tags == null ? "" : tags;
    }

    public Long getId() { return id; }
    public String getHostname() { return hostname; }
    public Cluster getCluster() { return cluster; }
    public int getCpuCores() { return cpuCores; }
    public int getMemoryGb() { return memoryGb; }
    public String getOsName() { return osName; }
    public LocalDate getOsEolDate() { return osEolDate; }
    public String getOwner() { return owner; }
    public String getTags() { return tags; }
    public Status getStatus() { return status; }
    public void decommission() { this.status = Status.DECOMMISSIONED; }

    public void upgradeOs(String osName, LocalDate osEolDate) {
        this.osName = osName;
        this.osEolDate = osEolDate;
    }
}
