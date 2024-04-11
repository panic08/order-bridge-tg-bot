package ru.panic.orderbridgebot.model;

import lombok.Builder;
import lombok.Data;
import org.springframework.data.annotation.Id;
import org.springframework.data.relational.core.mapping.Column;
import org.springframework.data.relational.core.mapping.Table;

@Table(name = "messages_table")
@Data
@Builder
public class Message {
    @Id
    private Long id;

    @Column("sender_id")
    private Long senderId;

    @Column("order_id")
    private Long orderId;

    private String text;

    @Column("executor_seen")
    private Boolean isExecutorSeen;

    @Column("customer_seen")
    private Boolean isCustomerSeen;

    @Column("sent_at")
    private Long sentAt;
}
