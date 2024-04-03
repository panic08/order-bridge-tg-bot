package ru.panic.orderbridgebot.model;

import lombok.Builder;
import lombok.Data;
import org.springframework.data.annotation.Id;
import org.springframework.data.relational.core.mapping.Column;
import org.springframework.data.relational.core.mapping.Table;
import ru.panic.orderbridgebot.model.type.PaymentMethod;
import ru.panic.orderbridgebot.model.type.ReplenishmentStatus;

@Table(name = "replenishments_table")
@Data
@Builder
public class Replenishment {
    @Id
    private Long id;

    @Column("user_id")
    private Long userId;

    private ReplenishmentStatus status;

    @Column("method")
    private PaymentMethod method;

    private String amount;

    @Column("created_at")
    private Long createdAt;
}
