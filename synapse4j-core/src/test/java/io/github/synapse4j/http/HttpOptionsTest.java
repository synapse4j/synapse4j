package io.github.synapse4j.http;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
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
    void effectiveCoversEveryField() throws ReflectiveOperationException {
        HttpOptions defaults = new HttpOptions();
        HttpOptions request = new HttpOptions();
        for (Field field : HttpOptions.class.getDeclaredFields()) {
            if (Modifier.isStatic(field.getModifiers())) {
                continue;
            }
            Object marker = markerFor(field.getType());
            field.setAccessible(true);
            field.set(defaults, marker);
            field.set(request, marker);

            HttpOptions effective = HttpOptions.effective(request, defaults);
            assertEquals(marker, field.get(effective), field.getName() + " should come from the request");

            field.set(request, null);
            effective = HttpOptions.effective(request, defaults);
            assertEquals(marker, field.get(effective), field.getName() + " should come from the defaults");
        }
    }

    private static Object markerFor(Class<?> type) {
        if (type == String.class) {
            return "marker";
        }
        if (type == Duration.class) {
            return Duration.ofSeconds(1);
        }
        if (type == Integer.class) {
            return 64 * 1024;
        }
        throw new IllegalStateException("this test needs a marker for " + type.getName());
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

    @Test
    void defaultsCarryTheStandardFrameBudget() {
        assertEquals(256 * 1024, HttpOptions.defaults().getMaxFrameBytes());
    }

}
