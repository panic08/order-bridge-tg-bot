CREATE TABLE IF NOT EXISTS withdrawals_table(
    id SERIAL PRIMARY KEY,
    user_id BIGINT NOT NULL,
    status VARCHAR(255) NOT NULL,
    payment_method VARCHAR(255) NOT NULL,
    amount VARCHAR(255) NOT NULL,
    address VARCHAR(255) NOT NULL,
    payment_amount DOUBLE PRECISION NOT NULL,
    created_at BIGINT NOT NULL,

    FOREIGN KEY (user_id) REFERENCES users_table(id)
);