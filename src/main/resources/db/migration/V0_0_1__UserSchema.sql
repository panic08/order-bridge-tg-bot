CREATE TABLE IF NOT EXISTS users_table(
    id SERIAL PRIMARY KEY,
    telegram_id BIGINT UNIQUE NOT NULL,
    executor_status VARCHAR(255),
    executor_prefixes VARCHAR(255),
    role VARCHAR(50) NOT NULL,
    balance DOUBLE PRECISION NOT NULL,
    registered_at BIGINT NOT NULL
);