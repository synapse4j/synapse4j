package io.github.synapse4j.http;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.time.Duration;

import org.junit.jupiter.api.Test;

class HttpOptionsTest {

    @Test
    void aRequestWithNoOptionsGetsTheDefaultsAsTheyAre() {
        HttpOptions defaults = new HttpOptions();
        defaults.setResponseTimeout(Duration.ofSeconds(30));
        defaults.setBodyWriteMode(HttpOptions.BUFFERED);

        HttpOptions effective = HttpOptions.effective(null, defaults);

        assertSame(defaults, effective);
        assertEquals(Duration.ofSeconds(30), effective.getResponseTimeout());
        assertEquals(HttpOptions.BUFFERED, effective.getBodyWriteMode());
    }

    @Test
    void whatTheRequestSetsWinsAndWhatItLeavesOutComesFromTheDefaults() {
        HttpOptions defaults = new HttpOptions();
        defaults.setResponseTimeout(Duration.ofSeconds(30));
        defaults.setBodyWriteMode(HttpOptions.BUFFERED);

        HttpOptions request = new HttpOptions();
        request.setResponseTimeout(Duration.ofSeconds(5));

        HttpOptions effective = HttpOptions.effective(request, defaults);

        assertEquals(Duration.ofSeconds(5), effective.getResponseTimeout());
        assertEquals(HttpOptions.BUFFERED, effective.getBodyWriteMode());
    }

    @Test
    void mergingLeavesBothSidesAsTheyWere() {
        HttpOptions defaults = new HttpOptions();
        defaults.setBodyWriteMode(HttpOptions.STREAMED);
        HttpOptions request = new HttpOptions();
        request.setResponseTimeout(Duration.ofSeconds(5));

        HttpOptions effective = HttpOptions.effective(request, defaults);

        assertNull(defaults.getResponseTimeout());
        assertNull(request.getBodyWriteMode());
        assertEquals(Duration.ofSeconds(5), request.getResponseTimeout());
        assertEquals(HttpOptions.STREAMED, effective.getBodyWriteMode());
        assertEquals(Duration.ofSeconds(5), effective.getResponseTimeout());
    }

    @Test
    void defaultsAreRequired() {
        assertThrows(NullPointerException.class, () -> HttpOptions.effective(new HttpOptions(), null));
    }

}
