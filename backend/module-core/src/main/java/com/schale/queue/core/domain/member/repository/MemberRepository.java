package com.schale.queue.core.domain.member.repository;

import com.schale.queue.core.domain.member.Member;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

/**
 * 회원 Repository.
 */
public interface MemberRepository extends JpaRepository<Member, Long> {

    /** 로그인/인증용 이메일 조회. */
    Optional<Member> findByEmail(String email);

    /** 회원가입 시 이메일 중복 검사. */
    boolean existsByEmail(String email);
}
