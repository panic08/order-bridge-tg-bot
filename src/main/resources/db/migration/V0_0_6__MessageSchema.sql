CREATE TABLE IF NOT EXISTS messages_table(
    id SERIAL PRIMARY KEY,
    sender_id BIGINT NOT NULL,
    order_id BIGINT NOT NULL,
    text TEXT NOT NULL,
    executor_seen BOOL NOT NULL,
    customer_seen BOOL NOT NULL,
    sent_at BIGINT NOT NULL,

    FOREIGN KEY (sender_id) REFERENCES users_table(id),
    FOREIGN KEY (order_id) REFERENCES orders_table(id)
);