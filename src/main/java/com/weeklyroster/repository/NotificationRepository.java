package com.weeklyroster.repository;

import com.weeklyroster.entity.Notification;
import com.weeklyroster.entity.NotificationType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.Collection;
import java.util.List;

@Repository
public interface NotificationRepository extends JpaRepository<Notification, Long> {

    List<Notification> findByRecipientUsernameOrderByCreatedAtDesc(String recipientUsername);

    List<Notification> findByRecipientUsernameAndReadStatusOrderByCreatedAtDesc(String recipientUsername, boolean readStatus);

    List<Notification> findByRecipientUsernameAndTypeInOrderByCreatedAtDesc(String recipientUsername, Collection<NotificationType> types);

    long countByRecipientUsernameAndReadStatusFalse(String recipientUsername);

    boolean existsByRecipientUsernameAndTypeAndLinkIdAndCreatedAtAfter(
            String recipientUsername, NotificationType type, Long linkId, LocalDateTime after);

    @org.springframework.data.jpa.repository.Modifying(clearAutomatically = true, flushAutomatically = true)
    @org.springframework.data.jpa.repository.Query("UPDATE Notification n SET n.readStatus = true WHERE n.recipientUsername = :username AND n.readStatus = false")
    int markAllAsReadForUser(@org.springframework.data.repository.query.Param("username") String username);
}
