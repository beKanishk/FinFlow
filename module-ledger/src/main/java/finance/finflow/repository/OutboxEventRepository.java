package finance.finflow.repository;

import finance.finflow.module.OutboxEvent;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

public interface OutboxEventRepository extends JpaRepository<OutboxEvent, Long> {

    // Multiple app instances can run this same scheduled poller concurrently. A plain SELECT
    // would let two instances read and publish the same PENDING row at once (duplicate Kafka
    // sends). "FOR UPDATE" row-locks whatever this query selects so a concurrent transaction
    // touching the same rows has to wait; "SKIP LOCKED" tells Postgres to instead skip rows
    // already locked by another in-flight transaction and grab the next unlocked ones. The net
    // effect: each instance walks away with a disjoint batch, so the table behaves like a safe
    // multi-consumer work queue without an external lock/coordination service.
    @Query(value = """
            SELECT * FROM outbox_events
            WHERE status = 'PENDING' AND next_retry_at <= :now
            ORDER BY created_at ASC
            LIMIT 50
            FOR UPDATE SKIP LOCKED
            """, nativeQuery = true)
    List<OutboxEvent> findAndLockPendingBatch(@Param("now") Instant now);

    // Used by ReconciliationService to find the latest publish attempt for a payment order, so it
    // knows whether to leave it alone (still PENDING), reset it (FAILED), or write a fresh one
    // (nothing found).
    Optional<OutboxEvent> findTopByAggregateIdAndEventTypeOrderByIdDesc(String aggregateId, String eventType);
}
