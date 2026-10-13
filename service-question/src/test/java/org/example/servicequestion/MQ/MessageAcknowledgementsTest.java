package org.example.servicequestion.MQ;

import com.rabbitmq.client.Channel;
import java.io.IOException;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class MessageAcknowledgementsTest {
    @Test
    void ackSettlesOnlyOneDelivery() throws Exception {
        Channel channel = mock(Channel.class);
        assertTrue(MessageAcknowledgements.ack(channel, 1L));
        verify(channel).basicAck(1L, false);
        verifyNoMoreInteractions(channel);
    }

    @Test
    void nackSettlesOnlyOneDeliveryAndPreservesRequeueFlag() throws Exception {
        Channel channel = mock(Channel.class);
        MessageAcknowledgements.nack(channel, 2L, true);
        verify(channel).basicNack(2L, false, true);
        verifyNoMoreInteractions(channel);
    }

    @Test
    void ackFailureAbortsWithoutContradictoryNack() throws Exception {
        Channel channel = mock(Channel.class);
        doThrow(new IOException("ack")).when(channel).basicAck(3L, false);
        assertFalse(MessageAcknowledgements.ack(channel, 3L));
        verify(channel).basicAck(3L, false);
        verify(channel).abort();
        verifyNoMoreInteractions(channel);
    }

    @Test
    void nackFailureAbortsWithoutRepeatedSettlement() throws Exception {
        Channel channel = mock(Channel.class);
        doThrow(new IOException("nack")).when(channel).basicNack(4L, false, false);
        assertDoesNotThrow(() -> MessageAcknowledgements.nack(channel, 4L, false));
        verify(channel).basicNack(4L, false, false);
        verify(channel).abort();
        verifyNoMoreInteractions(channel);
    }

    @Test
    void abortFailureDoesNotEscapeAndCauseOuterDoubleNack() throws Exception {
        Channel channel = mock(Channel.class);
        doThrow(new IOException("ack")).when(channel).basicAck(5L, false);
        doThrow(new IOException("abort")).when(channel).abort();
        assertFalse(MessageAcknowledgements.ack(channel, 5L));
        verify(channel).basicAck(5L, false);
        verify(channel).abort();
        verifyNoMoreInteractions(channel);
    }
}
