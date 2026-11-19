package uk.gov.moj.cp.ai.client;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.List;

import com.azure.core.credential.AccessToken;
import com.azure.core.credential.TokenCredential;
import com.azure.core.credential.TokenRequestContext;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

class CachingTokenSupplierTest {

    private static final Instant NOW = Instant.parse("2026-11-19T12:00:00Z");
    private static final String SCOPE = "api://gateway-app/.default";

    private static AccessToken tokenExpiringIn(final String value, final Duration validity) {
        return new AccessToken(value, OffsetDateTime.ofInstant(NOW.plus(validity), ZoneOffset.UTC));
    }

    private static final class MutableClock extends Clock {
        private Instant instant = NOW;

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(final ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return instant;
        }
    }

    @Test
    void requestsTheConfiguredScope() {
        final TokenCredential credential = mock(TokenCredential.class);
        when(credential.getTokenSync(any())).thenReturn(tokenExpiringIn("t1", Duration.ofHours(1)));

        new CachingTokenSupplier(credential, SCOPE, new MutableClock()).get();

        final ArgumentCaptor<TokenRequestContext> context = ArgumentCaptor.forClass(TokenRequestContext.class);
        verify(credential).getTokenSync(context.capture());
        assertEquals(List.of(SCOPE), context.getValue().getScopes());
    }

    @Test
    void reusesTokenWhileItIsValid() {
        final TokenCredential credential = mock(TokenCredential.class);
        when(credential.getTokenSync(any())).thenReturn(tokenExpiringIn("t1", Duration.ofHours(1)));
        final MutableClock clock = new MutableClock();
        final CachingTokenSupplier supplier = new CachingTokenSupplier(credential, SCOPE, clock);

        assertEquals("t1", supplier.get());
        clock.instant = NOW.plus(Duration.ofMinutes(50));
        assertEquals("t1", supplier.get());

        verify(credential, times(1)).getTokenSync(any());
    }

    @Test
    void refreshesTokenShortlyBeforeExpiry() {
        final TokenCredential credential = mock(TokenCredential.class);
        when(credential.getTokenSync(any()))
                .thenReturn(tokenExpiringIn("t1", Duration.ofHours(1)))
                .thenReturn(tokenExpiringIn("t2", Duration.ofHours(2)));
        final MutableClock clock = new MutableClock();
        final CachingTokenSupplier supplier = new CachingTokenSupplier(credential, SCOPE, clock);

        assertEquals("t1", supplier.get());
        clock.instant = NOW.plus(Duration.ofMinutes(56));
        assertEquals("t2", supplier.get());

        verify(credential, times(2)).getTokenSync(any());
    }
}
