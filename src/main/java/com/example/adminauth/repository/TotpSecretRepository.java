package com.example.adminauth.repository;

import com.example.adminauth.entity.TotpSecret;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;

@Repository
public interface TotpSecretRepository extends JpaRepository<TotpSecret, Long> {
    Optional<TotpSecret> findByAdminId(String adminId);
}
