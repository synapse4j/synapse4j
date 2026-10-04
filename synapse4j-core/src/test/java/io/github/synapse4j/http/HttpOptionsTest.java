package io.github.synapse4j.http;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertNull;

import java.time.Duration;

import org.junit.jupiter.api.Test;

class HttpOptionsTest {

    @Test
    void aRequestWithNoOptionsGetsTheDefaultsAsTheyAre() {
        HttpOptions defaults = new HttpOptions();
        defaults.setResponseTimeout(Duration.ofSeconds(30));
        defaults.setBodyWriteMode(HttpOptions.BUFFERED);

        HttpOptions effective = HttpOptions.effective(null, defaults);

        assertNotSame(defaults, effective);
        assertEquals(Duration.ofSeconds(30), effective.getResponseTimeout());
        assertEquals(HttpOptions.BUFFERED, effective.getBodyWriteMode());
    }

    @Test
    void theAnswerIsACallerOwnedCopyChangingItLeavesBothSidesAlone() {
        HttpOptions defaults = new HttpOptions();
        defaults.setBodyWriteMode(HttpOptions.STREAMED);
        HttpOptions request = new HttpOptions();
        request.setResponseTimeout(Duration.ofSeconds(5));

        HttpOptions effective = HttpOptions.effective(request, defaults);
        effective.setBodyWriteMode(HttpOptions.BUFFERED);
        effective.setResponseTimeout(Duration.ofMinutes(1));

        assertEquals(HttpOptions.STREAMED, defaults.getBodyWriteMode());
        assertNull(defaults.getResponseTimeout());
        assertNull(request.getBodyWriteMode());
        assertEquals(Duration.ofSeconds(5), request.getResponseTimeout());
    }

    @Test
    void whatTheRequestSetsWinsAndWhatItLeavesOutComesFromTheDefaults() {
        HttpOptions defaults = new HttpOptions();
        defaults.setResponseTimeout(Duration.ofSeconds(30));
        defaults.setBodyWriteMode(HttpOptions.BUFFERED);
        defaults.setMaxFrameBytes(4096);

        HttpOptions request = new HttpOptions();
        request.setResponseTimeout(Duration.ofSeconds(5));
        request.setMaxFrameBytes(8192);

        HttpOptions effective = HttpOptions.effective(request, defaults);

        assertEquals(Duration.ofSeconds(5), effective.getResponseTimeout());
        assertEquals(HttpOptions.BUFFERED, effective.getBodyWriteMode());
        assertEquals(8192, effective.getMaxFrameBytes());

        HttpOptions onlyDefaults = HttpOptions.effective(new HttpOptions(), defaults);
        assertEquals(Duration.ofSeconds(30), onlyDefaults.getResponseTimeout());
        assertEquals(4096, onlyDefaults.getMaxFrameBytes());
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
    void defaultsCarryTheStandardFrameBudget() {
        assertEquals(256 * 1024, HttpOptions.defaults().getMaxFrameBytes());
    }

}
