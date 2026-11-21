package dev.opspilot.inventory;

import jakarta.persistence.*;

@Entity
@Table(name = "cluster")
public class Cluster {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, unique = true)
    private String name;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private Environment environment;

    @Column(nullable = false)
    private String region;

    @Column(name = "owner_team")
    private String ownerTeam;

    @Column(name = "max_servers", nullable = false)
    private int maxServers;

    protected Cluster() { }

    public Cluster(String name, Environment environment, String region, String ownerTeam, int maxServers) {
        this.name = name;
        this.environment = environment;
        this.region = region;
        this.ownerTeam = ownerTeam;
        this.maxServers = maxServers;
    }

    public Long getId() { return id; }
    public String getName() { return name; }
    public Environment getEnvironment() { return environment; }
    public String getRegion() { return region; }
    public String getOwnerTeam() { return ownerTeam; }
    public int getMaxServers() { return maxServers; }
    public void setOwnerTeam(String ownerTeam) { this.ownerTeam = ownerTeam; }
}
