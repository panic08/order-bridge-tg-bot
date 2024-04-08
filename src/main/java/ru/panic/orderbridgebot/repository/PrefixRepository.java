package ru.panic.orderbridgebot.repository;

import org.springframework.data.jdbc.repository.query.Modifying;
import org.springframework.data.jdbc.repository.query.Query;
import org.springframework.data.repository.CrudRepository;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;
import ru.panic.orderbridgebot.model.Prefix;

@Repository
public interface PrefixRepository extends CrudRepository<Prefix, Long> {
    @Query("DELETE FROM prefixes_table WHERE name = :name")
    @Modifying
    void deleteByName(@Param("name") String name);
}
