CREATE TABLE IF NOT EXISTS replenishments_table(
    id SERIAL PRIMARY KEY,
    user_id BIGINT NOT NULL,
    order_id BIGINT NOT NULL,
    status VARCHAR(255) NOT NULL,
    method VARCHAR(255) NOT NULL,
    amount VARCHAR(255) NOT NULL,
    payment_amount VARCHAR(255) NOT NULL,
    created_at BIGINT NOT NULL,

    FOREIGN KEY (user_id) REFERENCES users_table(id),
    FOREIGN KEY (order_id) REFERENCES orders_table(id)
);
