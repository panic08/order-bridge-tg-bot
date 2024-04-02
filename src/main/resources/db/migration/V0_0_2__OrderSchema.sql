CREATE TABLE IF NOT EXISTS orders_table(
    id SERIAL PRIMARY KEY,
    customer_user_id BIGINT NOT NULL,
    executor_user_id BIGINT,
    order_status VARCHAR(50) NOT NULL,
    prefixes VARCHAR(255) NOT NULL,
    title VARCHAR(50) NOT NULL,
    description TEXT NOT NULL,
    budget DOUBLE PRECISION,
    telegram_channel_message_id INT,
    last_upped_at BIGINT NOT NULL,
    created_at BIGINT NOT NULL,

    FOREIGN KEY (customer_user_id) REFERENCES users_table(id),
    FOREIGN KEY (executor_user_id) REFERENCES users_table(id)
);
