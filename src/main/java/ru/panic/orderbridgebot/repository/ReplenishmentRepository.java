package ru.panic.orderbridgebot.repository;

import org.springframework.data.jdbc.repository.query.Modifying;
import org.springframework.data.jdbc.repository.query.Query;
import org.springframework.data.repository.CrudRepository;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;
import ru.panic.orderbridgebot.model.Replenishment;
import ru.panic.orderbridgebot.model.type.ReplenishmentStatus;

@Repository
public interface ReplenishmentRepository extends CrudRepository<Replenishment, Long> {
    @Query("UPDATE replenishments_table SET status = :status WHERE id = :id")
    @Modifying
    void updateStatusById(@Param("status") ReplenishmentStatus status, @Param("id") long id);
}
