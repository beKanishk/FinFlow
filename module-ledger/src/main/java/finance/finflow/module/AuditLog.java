package finance.finflow.module;

import jakarta.persistence.*;
import lombok.Data;

import java.time.Instant;

// Written by AuditListener, a Kafka consumer group ("finflow-audit-group") that's completely
// independent of the business consumers (deposit/failure/notify/dlt groups) - it gets its own copy
// of every event regardless of what those groups do with theirs, so this table fills in even when
// business processing of the same message fails or gets dead-lettered.
@Data
@Entity
@Table(name = "audit_logs")
public class AuditLog {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    // The actual Kafka topic the message arrived on - a business topic (e.g. "payment-completed")
    // or its dead-letter counterpart (e.g. "payment-completed.DLT").
    @Column(nullable = false)
    private String topic;

    // "PAYMENT_COMPLETED" / "PAYMENT_FAILED" / "WALLET_CREDITED" - derived from the topic name,
    // not re-read from the payload (the payload itself doesn't carry a type field).
    @Column(name = "event_type", nullable = false)
    private String eventType;

    // Username of the wallet owner this event is about, looked up via WalletRepository by the
    // event's walletId. Null if the wallet couldn't be found - never fail the audit write over it.
    @Column(name = "user_id")
    private String userId;

    // Short human-readable summary built from the event's own fields, so the table is scannable
    // without needing to read raw JSON.
    private String details;

    @Column(columnDefinition = "TEXT", nullable = false)
    private String payload;

    @Column(name = "consumed_at", nullable = false, updatable = false)
    private Instant consumedAt;

    @PrePersist
    void onCreate() {
        consumedAt = Instant.now();
    }
}
