package finance.finflow.repository;

import finance.finflow.module.DltResolution;
import org.springframework.data.jpa.repository.JpaRepository;

public interface DltResolutionRepository extends JpaRepository<DltResolution, Long> {
    boolean existsByTopicAndPartitionNumberAndMessageOffset(String topic, int partitionNumber, long messageOffset);
}
