package finance.finflow.module;

import com.modulejpaaudit.AbstractBaseEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import lombok.Data;
import lombok.EqualsAndHashCode;

// Marks one specific dead-lettered message (topic + partition + offset) as "handled" - either an
// admin retried it or explicitly deleted it. The message can easily still be sitting physically on
// the Kafka partition after this row exists, because Kafka can only delete a contiguous chunk from
// the FRONT of a partition, not one arbitrary record picked from the middle (see DltAdminService).
// This row is what lets the message disappear from the admin list right away regardless, and what
// DltCleanupScheduler uses later to know which messages at the front of the partition are now safe
// to actually purge from Kafka.
@Data
@EqualsAndHashCode(callSuper = true)
@Entity
@Table(name = "dlt_resolutions",
        uniqueConstraints = @UniqueConstraint(columnNames = {"topic", "partition_number", "message_offset"}))
public class DltResolution extends AbstractBaseEntity {

    @Column(nullable = false)
    private String topic;

    @Column(name = "partition_number", nullable = false)
    private int partitionNumber;

    @Column(name = "message_offset", nullable = false)
    private long messageOffset;

    @Enumerated(EnumType.STRING)
    @Column(name = "resolution_type", nullable = false)
    private DltResolutionType resolutionType;

    @Column(name = "message_key")
    private String messageKey;

    @Column(name = "message_value", columnDefinition = "TEXT")
    private String messageValue;
}
