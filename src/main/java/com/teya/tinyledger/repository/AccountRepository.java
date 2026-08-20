package com.teya.tinyledger.repository;

import com.teya.tinyledger.domain.Account;
import java.util.Optional;
import java.util.UUID;

/**
 * Storage boundary for accounts. Only the in-memory implementation exists here, but keeping the
 * interface means swapping in a real database is a change of adapter and nothing else.
 */
public interface AccountRepository {

    void save(Account account);

    Optional<Account> findById(UUID id);
}
