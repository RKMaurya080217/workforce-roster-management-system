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
}
