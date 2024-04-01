package ru.panic.orderbridgebot.repository;

import org.springframework.data.repository.CrudRepository;
import org.springframework.stereotype.Repository;
import ru.panic.orderbridgebot.model.Prefix;

@Repository
public interface PrefixRepository extends CrudRepository<Prefix, Long> {
}
