package ru.panic.orderbridgebot.repository;

import org.springframework.data.jdbc.repository.query.Modifying;
import org.springframework.data.jdbc.repository.query.Query;
import org.springframework.data.repository.CrudRepository;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;
import ru.panic.orderbridgebot.model.Withdrawal;
import ru.panic.orderbridgebot.model.type.WithdrawalStatus;

@Repository
public interface WithdrawalRepository extends CrudRepository<Withdrawal, Long> {
    @Query("UPDATE withdrawals_table SET status = :status WHERE id = :id")
    @Modifying
    void updateStatusById(@Param("status") WithdrawalStatus status, @Param("id") long id);
}
