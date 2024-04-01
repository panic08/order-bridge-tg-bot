CREATE TABLE IF NOT EXISTS orders_table(
    id SERIAL PRIMARY KEY,
    user_id BIGINT NOT NULL,
    order_status VARCHAR(50) NOT NULL,
    prefixes VARCHAR(255),
    title VARCHAR(50) NOT NULL,
    description TEXT NOT NULL,
    budget DOUBLE PRECISION,
    telegram_channel_message_id INT NOT NULL,
    created_at BIGINT NOT NULL,

    FOREIGN KEY (user_id) REFERENCES users_table(id)
);
