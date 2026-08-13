package finance.finflow.service;

import finance.finflow.module.OutboxEvent;
import finance.finflow.module.OutboxEventStatus;
import finance.finflow.repository.OutboxEventRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class OutboxEventService {

    private final OutboxEventRepository outboxEventRepository;

    public void save(String aggregateId, String eventType, String topic, String payload) {
        OutboxEvent event = new OutboxEvent();
        event.setAggregateId(aggregateId);
        event.setEventType(eventType);
        event.setTopic(topic);
        event.setPayload(payload);
        event.setStatus(OutboxEventStatus.PENDING);
        outboxEventRepository.save(event);
    }
}
