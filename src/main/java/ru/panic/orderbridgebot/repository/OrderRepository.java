package ru.panic.orderbridgebot.repository;

import org.springframework.data.jdbc.repository.query.Modifying;
import org.springframework.data.jdbc.repository.query.Query;
import org.springframework.data.repository.CrudRepository;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;
import ru.panic.orderbridgebot.model.Order;

import java.util.List;

@Repository
public interface OrderRepository extends CrudRepository<Order, Long> {
    @Query("SELECT COUNT(*) FROM orders_table WHERE customer_user_id = :customerUserId OR executor_user_id = :executorUserId")
    long countByCustomerUserIdOrExecutorUserId(@Param("customerUserId") long customerUserId,
                                               @Param("executorUserId") long executorUserId);

    @Query("SELECT o.* FROM orders_table o WHERE o.customer_user_id = :customerUserId OR o.executor_user_id = :executorUserId"
    + " ORDER BY o.created_at DESC LIMIT :limit OFFSET :offset")
    List<Order> findAllByCustomerUserIdOrExecutorUserIdWithOffsetOrderByCreatedAtDesc(@Param("customerUserId") long customerUserId,
                                                              @Param("executorUserId") long executorUserId,
                                                                   @Param("limit") int limit,
                                                                   @Param("offset") int offset);

    @Query("UPDATE orders_table SET last_upped_at = :lastUppedAt WHERE id = :id")
    @Modifying
    void updateLastUppedAtById(@Param("lastUppedAt") long lastUppedAt, @Param("id") long id);

    @Query("UPDATE orders_table SET telegram_channel_message_id = :telegramChannelMessageId WHERE id = :id")
    @Modifying
    void updateTelegramChannelMessageIdById(@Param("telegramChannelMessageId") long telegramChannelMessageId, @Param("id") long id);
}
