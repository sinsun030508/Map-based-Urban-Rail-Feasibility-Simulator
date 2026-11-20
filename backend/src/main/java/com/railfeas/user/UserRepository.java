package com.railfeas.user;

import jakarta.persistence.LockModeType;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface UserRepository extends JpaRepository<User, Long> {
    Optional<User> findByEmail(String email);

    Optional<User> findByProviderAndProviderUid(AuthProvider provider, String providerUid);

    boolean existsByEmail(String email);

    /**
     * 그 권한의 회원을 **잠그고** 읽는다(MySQL `SELECT … FOR UPDATE`) — 관리자 변경을 한 줄로 세운다(11-21).
     * 잠그지 않으면 두 관리자가 동시에 서로를 내릴 때 둘 다 「나는 관리자」 를 보고 통과해 관리자가 0명이 됐다.
     * 잠근 읽기는 먼저 들어온 요청이 끝난 뒤의 값을 본다
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select u from User u where u.role = :role")
    List<User> lockByRole(@Param("role") Role role);
}
