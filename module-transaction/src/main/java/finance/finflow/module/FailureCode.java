package finance.finflow.module;

// Short, fixed set of reasons a Transaction or OutboxEvent can end up FAILED. Keeping this as an
// enum (not a free-text-only failureMessage) means code that reads failures later (dashboards,
// reconciliation, support tooling) can group/filter by reason instead of parsing strings.
public enum FailureCode {
    WALLET_NOT_FOUND,
    WALLET_INACTIVE,
    INSUFFICIENT_BALANCE,
    CURRENCY_MISMATCH,
    PAYMENT_GATEWAY_DECLINED,
    KAFKA_PUBLISH_FAILED,
    UNKNOWN_ERROR
}
