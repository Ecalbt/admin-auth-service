package com.example.adminauth.messaging;

import com.example.adminauth.repository.OutboxEventRepository;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.Spy;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class OutboxCleanerTest {

    @Mock
    private OutboxEventRepository outboxEventRepository;

    @Spy
    private SimpleMeterRegistry meterRegistry = new SimpleMeterRegistry();

    @InjectMocks
    private OutboxCleaner outboxCleaner;

    @BeforeEach
    void setUp() {
        ReflectionTestUtils.setField(outboxCleaner, "retentionDays", 14);
    }

    @Test
    @DisplayName("OutboxCleaner deletes published events older than retention cutoff in batches")
    void testCleanupPublishedEvents() {
        when(outboxEventRepository.deletePublishedOlderThan(any(LocalDateTime.class), eq(1000)))
                .thenReturn(1000)
                .thenReturn(250);

        outboxCleaner.cleanupPublishedEvents();

        verify(outboxEventRepository, times(2)).deletePublishedOlderThan(any(LocalDateTime.class), eq(1000));
        assertThat(meterRegistry.counter("outbox.cleaned").count()).isEqualTo(1250.0);
    }

    @Test
    @DisplayName("OutboxCleaner does not increment metric when no events are eligible")
    void testCleanupNoEvents() {
        when(outboxEventRepository.deletePublishedOlderThan(any(LocalDateTime.class), eq(1000)))
                .thenReturn(0);

        outboxCleaner.cleanupPublishedEvents();

        verify(outboxEventRepository, times(1)).deletePublishedOlderThan(any(LocalDateTime.class), eq(1000));
        assertThat(meterRegistry.counter("outbox.cleaned").count()).isEqualTo(0.0);
    }
}
