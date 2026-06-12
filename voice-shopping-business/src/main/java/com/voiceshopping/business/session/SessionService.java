package com.voiceshopping.business.session;

import com.voiceshopping.infrastructure.repository.SessionRepository;
import com.voiceshopping.infrastructure.repository.entity.Session;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Session lifecycle management.
 * Idempotent creation guarantees session_state writes always have a parent session.
 */
@Service
public class SessionService {

    private static final Logger log = LoggerFactory.getLogger(SessionService.class);

    private final SessionRepository sessionRepository;

    public SessionService(SessionRepository sessionRepository) {
        this.sessionRepository = sessionRepository;
    }

    /**
     * Get an existing session or create a new one. Idempotent — safe to call
     * multiple times with the same sessionId (e.g. WebSocket reconnection).
     */
    public Session getOrCreate(UUID sessionId, Long merchantId, Long userId, String channel) {
        return sessionRepository.findById(sessionId).orElseGet(() -> {
            Session session = new Session();
            session.setId(sessionId);
            session.setMerchantId(merchantId);
            session.setUserId(userId);
            session.setChannel(channel != null ? channel : "HOME_ENTRY");
            session.setTotalTokens(0);
            session.setStartedAt(Instant.now());
            session.setCreatedAt(Instant.now());
            session.setUpdatedAt(Instant.now());
            Session saved = sessionRepository.save(session);
            log.info("Created new session: id={}, userId={}, channel={}", sessionId, userId, channel);
            return saved;
        });
    }

    /**
     * Find all sessions for a user, ordered by most recent first.
     */
    public List<Session> findByUserId(Long userId) {
        return sessionRepository.findByUserIdOrderByStartedAtDesc(userId);
    }
}
