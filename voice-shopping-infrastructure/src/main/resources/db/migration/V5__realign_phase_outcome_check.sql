-- ============================================================
-- V5: Realign CHECK constraints + DEFAULT for session_state.phase
-- and session.outcome to the new enum vocabulary.
--
-- session_state.phase old: IDLE / INTENT_PARSED / CLARIFYING /
--                          READY_TO_RECOMMEND / GENERATING_SPEECH / ORDER_CONFIRMING
-- session_state.phase new: INTENT / CLARIFY / RECOMMEND / ORDER_CONFIRM / ENDED
--
-- session.outcome old: RECOMMENDATION / ORDER / FAQ_ANSWERED /
--                      CHITCHAT / FOLLOWUP / ABANDONED / ERROR
-- session.outcome new: ORDERED / ABANDONED / FOLLOWUP
--
-- session.channel was already on the new vocabulary (HOME_ENTRY /
-- PRODUCT_PAGE / SEARCH_FALLBACK), no change needed.
-- ============================================================

-- session_state.phase ------------------------------------------------
ALTER TABLE session_state DROP CONSTRAINT IF EXISTS session_state_phase_check;

-- Migrate any pre-existing rows from the old vocabulary so the new CHECK
-- can be enforced. Best-effort mapping; orchestrator owns these states now.
UPDATE session_state SET phase = 'INTENT'        WHERE phase = 'IDLE';
UPDATE session_state SET phase = 'INTENT'        WHERE phase = 'INTENT_PARSED';
UPDATE session_state SET phase = 'CLARIFY'       WHERE phase = 'CLARIFYING';
UPDATE session_state SET phase = 'RECOMMEND'     WHERE phase = 'READY_TO_RECOMMEND';
UPDATE session_state SET phase = 'RECOMMEND'     WHERE phase = 'GENERATING_SPEECH';
UPDATE session_state SET phase = 'ORDER_CONFIRM' WHERE phase = 'ORDER_CONFIRMING';

ALTER TABLE session_state ALTER COLUMN phase SET DEFAULT 'INTENT';

ALTER TABLE session_state
    ADD CONSTRAINT session_state_phase_check
    CHECK (phase IN ('INTENT', 'CLARIFY', 'RECOMMEND', 'ORDER_CONFIRM', 'ENDED'));

-- session.outcome ----------------------------------------------------
ALTER TABLE session DROP CONSTRAINT IF EXISTS session_outcome_check;

-- Migrate pre-existing rows. Old CHITCHAT / RECOMMENDATION / FAQ_ANSWERED
-- have no direct equivalent in the new vocabulary — folded into FOLLOWUP
-- so the row survives the new CHECK; ERROR maps to ABANDONED.
UPDATE session SET outcome = 'ORDERED'   WHERE outcome = 'ORDER';
UPDATE session SET outcome = 'ABANDONED' WHERE outcome IN ('ERROR');
UPDATE session SET outcome = 'FOLLOWUP'  WHERE outcome IN ('RECOMMENDATION', 'FAQ_ANSWERED', 'CHITCHAT');

ALTER TABLE session
    ADD CONSTRAINT session_outcome_check
    CHECK (outcome IN ('ORDERED', 'ABANDONED', 'FOLLOWUP'));
