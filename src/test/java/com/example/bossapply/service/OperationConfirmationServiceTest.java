package com.example.bossapply.service;

import com.example.bossapply.config.AppSecurityProperties;
import com.example.bossapply.model.ConfirmationTokenView;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * 验证人工确认令牌只能使用一次，并且与具体操作资源绑定。
 */
class OperationConfirmationServiceTest {

    @Test
    void shouldConsumeQueueTokenOnlyOnce() {
        OperationConfirmationService service = new OperationConfirmationService(new AppSecurityProperties());
        ConfirmationTokenView token = service.issueQueueConfirmation(List.of(2L, 1L));

        assertDoesNotThrow(() -> service.consumeQueueConfirmation(token.token(), List.of(1L, 2L)));
        assertThrows(IllegalArgumentException.class,
                () -> service.consumeQueueConfirmation(token.token(), List.of(1L, 2L)));
    }

    @Test
    void shouldRejectTokenBoundToAnotherQueueOrOperation() {
        OperationConfirmationService service = new OperationConfirmationService(new AppSecurityProperties());
        ConfirmationTokenView queueToken = service.issueQueueConfirmation(List.of(1L));
        ConfirmationTokenView applyToken = service.issueSingleApply(8L);

        assertThrows(IllegalArgumentException.class,
                () -> service.consumeQueueConfirmation(queueToken.token(), List.of(2L)));
        assertThrows(IllegalArgumentException.class,
                () -> service.consumeSingleApply(applyToken.token(), 9L));
    }
}
