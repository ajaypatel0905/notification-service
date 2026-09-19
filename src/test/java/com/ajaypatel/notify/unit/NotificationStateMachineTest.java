package com.ajaypatel.notify.unit;

import com.ajaypatel.notify.common.error.InvalidStateTransitionException;
import com.ajaypatel.notify.notification.NotificationStateMachine;
import com.ajaypatel.notify.notification.NotificationStatus;
import org.junit.jupiter.api.Test;

import static com.ajaypatel.notify.notification.NotificationStatus.CANCELLED;
import static com.ajaypatel.notify.notification.NotificationStatus.DELIVERED;
import static com.ajaypatel.notify.notification.NotificationStatus.FAILED;
import static com.ajaypatel.notify.notification.NotificationStatus.PROCESSING;
import static com.ajaypatel.notify.notification.NotificationStatus.QUEUED;
import static com.ajaypatel.notify.notification.NotificationStatus.SCHEDULED;
import static com.ajaypatel.notify.notification.NotificationStatus.SENT;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class NotificationStateMachineTest {

    @Test
    void happyPathTransitionsAreAllowed() {
        assertThat(NotificationStateMachine.canTransition(SCHEDULED, QUEUED)).isTrue();
        assertThat(NotificationStateMachine.canTransition(QUEUED, PROCESSING)).isTrue();
        assertThat(NotificationStateMachine.canTransition(PROCESSING, SENT)).isTrue();
        assertThat(NotificationStateMachine.canTransition(PROCESSING, DELIVERED)).isTrue();
        assertThat(NotificationStateMachine.canTransition(SENT, DELIVERED)).isTrue();
        assertThat(NotificationStateMachine.canTransition(PROCESSING, QUEUED)).as("retry / lease expiry").isTrue();
    }

    @Test
    void cancelOnlyBeforeWorkStarts() {
        assertThat(NotificationStateMachine.canTransition(SCHEDULED, CANCELLED)).isTrue();
        assertThat(NotificationStateMachine.canTransition(QUEUED, CANCELLED)).isTrue();
        assertThat(NotificationStateMachine.canTransition(PROCESSING, CANCELLED)).isFalse();
        assertThat(NotificationStateMachine.canTransition(SENT, CANCELLED)).isFalse();
    }

    @Test
    void terminalStatesAreAbsorbing() {
        for (NotificationStatus terminal : new NotificationStatus[]{DELIVERED, FAILED, CANCELLED}) {
            assertThat(terminal.isTerminal()).isTrue();
            for (NotificationStatus to : NotificationStatus.values()) {
                assertThat(NotificationStateMachine.canTransition(terminal, to)).isFalse();
            }
        }
        assertThat(SENT.isTerminal()).isFalse();
    }

    @Test
    void assertTransitionThrowsWithBothStatesInMessage() {
        assertThatThrownBy(() -> NotificationStateMachine.assertTransition(DELIVERED, QUEUED))
                .isInstanceOf(InvalidStateTransitionException.class)
                .hasMessageContaining("DELIVERED").hasMessageContaining("QUEUED");
    }
}
