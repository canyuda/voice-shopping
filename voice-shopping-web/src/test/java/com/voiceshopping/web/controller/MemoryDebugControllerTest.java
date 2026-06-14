package com.voiceshopping.web.controller;

import com.voiceshopping.business.memory.LongTermMemoryWriter;
import com.voiceshopping.business.session.SessionService;
import com.voiceshopping.common.dto.ApiResult;
import com.voiceshopping.common.exception.NotFoundException;
import com.voiceshopping.web.dto.MemoryFlushRequest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Unit-level tests for {@link MemoryDebugController}. Exercises the userId-resolution
 * branch + fail-fast on blank sessionId + 404 propagation when session row is missing.
 */
class MemoryDebugControllerTest {

    private LongTermMemoryWriter writer;
    private SessionService sessionService;
    private MemoryDebugController controller;

    @BeforeEach
    void setup() {
        writer = mock(LongTermMemoryWriter.class);
        sessionService = mock(SessionService.class);
        controller = new MemoryDebugController(writer, sessionService);
    }

    @Test
    void flush_withExplicitUserId_skipsLookupAndDispatches() {
        ApiResult<String> result = controller.flush(new MemoryFlushRequest("sess-1", 100L));

        assertThat(result.data()).isEqualTo("ok");
        verify(writer).flushOnSessionEnd("sess-1", 100L);
        verify(sessionService, never()).findUserId("sess-1");
    }

    @Test
    void flush_withoutUserId_resolvesFromSession() {
        when(sessionService.findUserId("sess-1")).thenReturn(200L);

        ApiResult<String> result = controller.flush(new MemoryFlushRequest("sess-1", null));

        assertThat(result.data()).isEqualTo("ok");
        verify(writer).flushOnSessionEnd("sess-1", 200L);
    }

    @Test
    void flush_blankSessionId_throwsIllegalArgument() {
        assertThatThrownBy(() -> controller.flush(new MemoryFlushRequest("  ", 1L)))
                .isInstanceOf(IllegalArgumentException.class);
        verify(writer, never()).flushOnSessionEnd(java.util.Objects.requireNonNull(""), 0L);
    }

    @Test
    void flush_nullSessionId_throwsIllegalArgument() {
        assertThatThrownBy(() -> controller.flush(new MemoryFlushRequest(null, 1L)))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void flush_unknownSession_propagatesNotFound() {
        when(sessionService.findUserId("missing"))
                .thenThrow(new NotFoundException("会话不存在: missing"));

        assertThatThrownBy(() -> controller.flush(new MemoryFlushRequest("missing", null)))
                .isInstanceOf(NotFoundException.class)
                .hasMessageContaining("missing");
    }
}
