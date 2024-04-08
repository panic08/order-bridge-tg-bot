CREATE TABLE IF NOT EXISTS users_table(
    id SERIAL PRIMARY KEY,
    telegram_id BIGINT UNIQUE NOT NULL,
    executor_prefixes VARCHAR(255),
    role VARCHAR(50) NOT NULL,
    balance DOUBLE PRECISION NOT NULL,
    account_non_locked BOOL NOT NULL,
    registered_at BIGINT NOT NULL
);