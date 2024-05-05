package ru.panic.orderbridgebot.model.event;

import lombok.Builder;
import lombok.Data;
import org.springframework.data.annotation.Id;
import org.springframework.data.relational.core.mapping.Column;
import org.springframework.data.relational.core.mapping.Table;

@Table(name = "delete_replenishment_after_time_events_table")
@Data
@Builder
public class DeleteReplenishmentAfterTimeEvent {
    @Id
    private Long id;

    @Column("replenishment_id")
    private Long replenishmentId;

    @Column("telegram_chat_id")
    private Long telegramChatId;

    @Column("telegram_message_id")
    private Integer telegramMessageId;

    @Column("deleted_at")
    private Long deletedAt;
}
