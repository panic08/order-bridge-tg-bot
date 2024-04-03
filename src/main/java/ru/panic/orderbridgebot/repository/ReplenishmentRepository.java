package ru.panic.orderbridgebot.repository;

import org.springframework.data.repository.CrudRepository;
import org.springframework.stereotype.Repository;
import ru.panic.orderbridgebot.model.Replenishment;

@Repository
public interface ReplenishmentRepository extends CrudRepository<Replenishment, Long> {
}
