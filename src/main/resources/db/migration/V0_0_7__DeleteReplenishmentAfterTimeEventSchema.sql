CREATE TABLE IF NOT EXISTS delete_replenishment_after_time_events_table(
    id SERIAL PRIMARY KEY,
    replenishment_id BIGINT UNIQUE NOT NULL,
    telegram_chat_id BIGINT NOT NULL,
    telegram_message_id BIGINT NOT NULL,
    deleted_at BIGINT NOT NULL,

    FOREIGN KEY (replenishment_id) REFERENCES replenishments_table(id)
);