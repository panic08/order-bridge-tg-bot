package ru.panic.orderbridgebot.repository;

import org.springframework.data.jdbc.repository.query.Modifying;
import org.springframework.data.jdbc.repository.query.Query;
import org.springframework.data.repository.CrudRepository;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;
import ru.panic.orderbridgebot.model.User;
import ru.panic.orderbridgebot.model.type.UserRole;

import java.util.List;
import java.util.Optional;

@Repository
public interface UserRepository extends CrudRepository<User, Long> {
    @Query("SELECT u.* FROM users_table u WHERE u.telegram_id = :telegramId")
    Optional<User> findByTelegramId(@Param("telegramId") long telegramId);

    @Query("SELECT u.telegram_id FROM users_table u WHERE u.id = :id")
    long findTelegramIdById(@Param("id") long id);

    @Query("SELECT u.id FROM users_table u WHERE u.role = :role")
    List<Long> findAllIdByRole(@Param("role") UserRole role);

    @Query("UPDATE users_table SET balance = :balance WHERE id = :id")
    @Modifying
    void updateBalanceById(@Param("balance") double balance, @Param("id") long id);

    @Query("UPDATE users_table SET role = :role WHERE telegram_id = :telegramId")
    @Modifying
    void updateRoleByTelegramId(@Param("role") UserRole role, @Param("telegramId") long telegramId);

    @Query("UPDATE users_table SET executor_prefixes = :executorPrefixes WHERE telegram_id = :telegramId")
    @Modifying
    void updateExecutorPrefixesByTelegramId(@Param("executorPrefixes") String executorPrefixes, @Param("telegramId") long telegramId);

    @Query("UPDATE users_table SET account_non_locked = :isAccountNonLocked WHERE telegram_id = :telegramId")
    @Modifying
    void updateIsAccountNonLockedByTelegramId(@Param("isAccountNonLocked") boolean isAccountNonLocked,
                                              @Param("telegramId") long telegramId);

    @Query("SELECT u.role FROM users_table u WHERE u.id = :id")
    UserRole findRoleById(@Param("id") long id);

    @Query("SELECT u.telegram_id FROM users_table u WHERE u.role = :role")
    List<Long> findAllTelegramIdByRole(@Param("role") UserRole role);
}
