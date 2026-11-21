package dev.opspilot.request;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Optional;

public interface ChangeRequestRepository extends JpaRepository<ChangeRequest, Long> {

    // @EntityGraph = fetch these associations in the same query (no N+1 when mapping to DTOs).
    @EntityGraph(attributePaths = {"cluster", "requester", "approver"})
    @Query("""
           select cr from ChangeRequest cr
           where (:status is null or cr.status = :status)
             and (:requesterId is null or cr.requester.id = :requesterId)
           """)
    Page<ChangeRequest> search(@Param("status") RequestStatus status,
                               @Param("requesterId") Long requesterId,
                               Pageable pageable);

    @EntityGraph(attributePaths = {"cluster", "requester", "approver"})
    Optional<ChangeRequest> findWithDetailsById(Long id);

    /** Overlapping windows on the same cluster: (startA < endB) AND (endA > startB). */
    @EntityGraph(attributePaths = {"requester"})
    @Query("""
           select cr from ChangeRequest cr
           where cr.cluster.id = :clusterId
             and cr.id <> :excludeId
             and cr.status in :statuses
             and cr.windowStart < :windowEnd
             and cr.windowEnd > :windowStart
           order by cr.windowStart
           """)
    List<ChangeRequest> findOverlapping(@Param("clusterId") Long clusterId,
                                        @Param("windowStart") Instant windowStart,
                                        @Param("windowEnd") Instant windowEnd,
                                        @Param("excludeId") Long excludeId,
                                        @Param("statuses") Collection<RequestStatus> statuses);

    long countByStatus(RequestStatus status);
}
