package dev.opspilot.inventory;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import java.util.List;

public interface ServerRepository extends JpaRepository<Server, Long> {

    long countByClusterIdAndStatus(Long clusterId, Server.Status status);

    List<Server> findByClusterIdAndStatusOrderByHostname(Long clusterId, Server.Status status);

    // JOIN FETCH loads servers and their clusters in ONE query, avoiding the "N+1 queries" problem.
    @Query("select s from Server s join fetch s.cluster where s.status = 'ACTIVE' order by s.hostname")
    List<Server> findAllActiveWithCluster();
}
