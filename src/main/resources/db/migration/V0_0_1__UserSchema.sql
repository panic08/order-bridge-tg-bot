CREATE TABLE IF NOT EXISTS users_table(
    id SERIAL PRIMARY KEY,
    telegram_id BIGINT UNIQUE NOT NULL,
    telegram_username VARCHAR(255) NOT NULL,
    executor_prefixes VARCHAR(255),
    role VARCHAR(50) NOT NULL,
    balance DOUBLE PRECISION NOT NULL,
    account_non_locked BOOL NOT NULL,
    registered_at BIGINT NOT NULL
);