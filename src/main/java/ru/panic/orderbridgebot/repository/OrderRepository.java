package ru.panic.orderbridgebot.repository;

import org.springframework.data.jdbc.repository.query.Modifying;
import org.springframework.data.jdbc.repository.query.Query;
import org.springframework.data.repository.CrudRepository;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;
import ru.panic.orderbridgebot.model.Order;
import ru.panic.orderbridgebot.model.type.OrderStatus;

import java.util.List;

@Repository
public interface OrderRepository extends CrudRepository<Order, Long> {
    @Query("SELECT COUNT(*) FROM orders_table WHERE customer_user_id = :customerUserId OR executor_user_id = :executorUserId")
    long countByCustomerUserIdOrExecutorUserId(@Param("customerUserId") long customerUserId,
                                               @Param("executorUserId") long executorUserId);

    @Query("SELECT COUNT(*) FROM orders_table WHERE customer_user_id = :customerUserId AND order_status = :orderStatus")
    long countByCustomerUserIdAndOrderStatus(@Param("customerUserId") long customerUserId,
                                               @Param("orderStatus") OrderStatus orderStatus);

    @Query("SELECT COUNT(*) FROM orders_table WHERE order_status = :orderStatus")
    long countByOrderStatus(@Param("orderStatus") OrderStatus orderStatus);

    @Query("SELECT o.* FROM orders_table o WHERE o.customer_user_id = :customerUserId OR o.executor_user_id = :executorUserId"
    + " ORDER BY o.created_at DESC LIMIT :limit OFFSET :offset")
    List<Order> findAllByCustomerUserIdOrExecutorUserIdWithOffsetOrderByCreatedAtDesc(@Param("customerUserId") long customerUserId,
                                                              @Param("executorUserId") long executorUserId,
                                                                   @Param("limit") int limit,
                                                                   @Param("offset") int offset);

    @Query("SELECT o.* FROM orders_table o WHERE o.customer_user_id = :customerUserId AND o.order_status = :orderStatus"
            + " ORDER BY o.created_at DESC LIMIT :limit OFFSET :offset")
    List<Order> findAllByCustomerUserIdAndOrderStatusWithOffsetOrderByCreatedAtDesc(@Param("customerUserId") long customerUserId,
                                                                                    @Param("orderStatus") OrderStatus orderStatus,
                                                                                    @Param("limit") int limit,
                                                                                    @Param("offset") int offset);

    @Query("SELECT o.* FROM orders_table o WHERE o.order_status = :orderStatus"
            + " ORDER BY o.created_at DESC LIMIT :limit OFFSET :offset")
    List<Order> findAllByOrderStatusWithOffsetOrderByCreatedAtDesc(@Param("orderStatus") OrderStatus orderStatus,
                                                                   @Param("limit") int limit,
                                                                   @Param("offset") int offset);

    @Query("SELECT o.* FROM orders_table o ORDER BY o.created_at DESC LIMIT :limit OFFSET :offset")
    List<Order> findAllWithOffsetOrderByCreatedAtDesc(@Param("limit") int limit,
                                                      @Param("offset") int offset);

    @Query("SELECT o.title FROM orders_table o WHERE o.id = :id")
    String findTitleById(@Param("id") long id);

    @Query("UPDATE orders_table SET last_upped_at = :lastUppedAt WHERE id = :id")
    @Modifying
    void updateLastUppedAtById(@Param("lastUppedAt") long lastUppedAt, @Param("id") long id);

    @Query("UPDATE orders_table SET telegram_channel_message_id = :telegramChannelMessageId WHERE id = :id")
    @Modifying
    void updateTelegramChannelMessageIdById(@Param("telegramChannelMessageId") long telegramChannelMessageId, @Param("id") long id);

    @Query("UPDATE orders_table SET order_status = :orderStatus WHERE id = :id")
    @Modifying
    void updateOrderStatusById(@Param("orderStatus") OrderStatus orderStatus, @Param("id") long id);
}
