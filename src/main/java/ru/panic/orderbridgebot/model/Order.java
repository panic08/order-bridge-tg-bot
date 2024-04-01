package ru.panic.orderbridgebot.model;

import lombok.Builder;
import lombok.Data;
import org.springframework.data.annotation.Id;
import org.springframework.data.relational.core.mapping.Column;
import org.springframework.data.relational.core.mapping.Table;
import ru.panic.orderbridgebot.model.type.OrderStatus;

@Table(name = "orders_table")
@Data
@Builder
public class Order {
    @Id
    private Long id;

    @Column("user_id")
    private Long userId;

    @Column("order_status")
    private OrderStatus orderStatus;

    // may null
    private String prefixes;

    private String title;

    private String description;

    // may null
    private Double budget;

    @Column("telegram_channel_message_id")
    private Integer telegramChannelMessageId;

    @Column("created_at")
    private Long createdAt;
}
