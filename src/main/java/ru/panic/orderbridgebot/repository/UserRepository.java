package ru.panic.orderbridgebot.repository;

import org.springframework.data.jdbc.repository.query.Modifying;
import org.springframework.data.jdbc.repository.query.Query;
import org.springframework.data.repository.CrudRepository;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;
import ru.panic.orderbridgebot.model.User;

import java.util.Optional;

@Repository
public interface UserRepository extends CrudRepository<User, Long> {
    @Query("SELECT u.* FROM users_table u WHERE u.telegram_id = :telegramId")
    Optional<User> findByTelegramId(@Param("telegramId") long telegramId);

    @Query("SELECT u.telegram_id FROM users_table u WHERE u.id = :id")
    long findTelegramIdById(@Param("id") long id);


    @Query("UPDATE users_table SET balance = :balance WHERE id = :id")
    @Modifying
    void updateBalanceById(@Param("balance") double balance, @Param("id") long id);
}
