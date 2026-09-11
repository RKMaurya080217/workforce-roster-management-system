package com.weeklyroster.repository;

import com.weeklyroster.entity.ApiClient;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface ApiClientRepository extends JpaRepository<ApiClient, Long> {
    Optional<ApiClient> findByApiKeyHash(String apiKeyHash);
    Optional<ApiClient> findByClientName(String clientName);
    List<ApiClient> findAllByOrderByCreatedAtDesc();
}
