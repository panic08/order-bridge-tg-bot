package ru.panic.orderbridgebot.repository;

import org.springframework.data.jdbc.repository.query.Query;
import org.springframework.data.repository.CrudRepository;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;
import ru.panic.orderbridgebot.model.Order;

@Repository
public interface OrderRepository extends CrudRepository<Order, Long> {
    @Query("SELECT COUNT(*) FROM orders_table WHERE user_id = :userId")
    long countByUserId(@Param("userId") long userId);
}
