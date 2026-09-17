package com.weeklyroster.repository;

import com.weeklyroster.entity.DeviceToken;
import com.weeklyroster.entity.Employee;
import com.weeklyroster.entity.User;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

@Repository
public interface DeviceTokenRepository extends JpaRepository<DeviceToken, Long> {

    Optional<DeviceToken> findByToken(String token);

    List<DeviceToken> findByUserAndActiveTrue(User user);

    List<DeviceToken> findByEmployeeAndActiveTrue(Employee employee);

    List<DeviceToken> findByUserIdAndActiveTrue(Long userId);

    @Query("SELECT d FROM DeviceToken d WHERE d.employee.id = :employeeId AND d.active = true")
    List<DeviceToken> findByEmployeeIdAndActiveTrue(@Param("employeeId") Long employeeId);

    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("UPDATE DeviceToken d SET d.active = false, d.updatedAt = :now WHERE d.token = :token")
    int deactivateToken(@Param("token") String token, @Param("now") LocalDateTime now);

    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("UPDATE DeviceToken d SET d.active = false, d.updatedAt = :now WHERE d.token = :token AND d.user = :user")
    int deactivateTokenForUser(@Param("token") String token, @Param("user") User user, @Param("now") LocalDateTime now);

    long countByUserAndActiveTrue(User user);
}
