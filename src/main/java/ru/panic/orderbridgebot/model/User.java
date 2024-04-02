package ru.panic.orderbridgebot.model;

import lombok.Builder;
import lombok.Data;
import org.springframework.data.annotation.Id;
import org.springframework.data.relational.core.mapping.Column;
import org.springframework.data.relational.core.mapping.Table;
import ru.panic.orderbridgebot.model.type.UserRole;

@Table(name = "users_table")
@Data
@Builder
public class User {
    @Id
    private Long id;

    @Column("telegram_id")
    private Long telegramId;

    @Column("executor_status")
    private String executorStatus;

    @Column("executor_prefixes")
    private String executorPrefixes;

    private UserRole role;

    private Double balance;

    @Column("registered_at")
    private Long registeredAt;
}
