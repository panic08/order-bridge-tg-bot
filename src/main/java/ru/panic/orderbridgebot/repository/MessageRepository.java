package ru.panic.orderbridgebot.repository;

import org.springframework.data.jdbc.repository.query.Query;
import org.springframework.data.repository.CrudRepository;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;
import ru.panic.orderbridgebot.model.Message;

import java.util.List;

@Repository
public interface MessageRepository extends CrudRepository<Message, Long> {
    @Query("SELECT m.* FROM messages_table m WHERE m.order_id = :orderId AND m.executor_seen = :isExecutorSeen")
    List<Message> findAllByOrderIdAndIsExecutorSeen(@Param("orderId") long orderId, @Param("isExecutorSeen") boolean isExecutorSeen);

    @Query("SELECT m.* FROM messages_table m WHERE m.order_id = :orderId AND m.customer_seen = :isCustomerSeen")
    List<Message> findAllByOrderIdAndIsCustomerSeen(@Param("orderId") long orderId, @Param("isCustomerSeen") boolean isCustomerSeen);

    @Query("SELECT m.* FROM messages_table m WHERE m.order_id = :orderId")
    List<Message> findAllByOrderId(@Param("orderId") long orderId);

    @Query("SELECT m.id FROM messages_table m WHERE m.order_id = :orderId")
    List<Long> findAllIdByOrderId(@Param("orderId") long orderId);
}
