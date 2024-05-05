package ru.panic.orderbridgebot.repository;

import org.springframework.data.jdbc.repository.query.Modifying;
import org.springframework.data.jdbc.repository.query.Query;
import org.springframework.data.repository.CrudRepository;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;
import ru.panic.orderbridgebot.model.event.DeleteReplenishmentAfterTimeEvent;

@Repository
public interface DeleteReplenishmentAfterTimeEventRepository extends CrudRepository<DeleteReplenishmentAfterTimeEvent, Long> {
    @Query("DELETE FROM delete_replenishment_after_time_events_table WHERE replenishment_id = :replenishmentId")
    @Modifying
    void deleteByReplenishmentId(@Param("replenishmentId") long replenishmentId);
}
