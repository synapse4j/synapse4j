package io.github.synapse4j.util;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.Reader;
import java.util.Base64;
import java.util.Random;

import org.junit.jupiter.api.Test;

class Base64ReaderTest {

    @Test
    void theTextIsThePrefixFollowedByTheBase64OfTheBytes() throws IOException {
        byte[] bytes = "ping".getBytes(UTF_8);

        String text = read(new Base64Reader("data:image/png;base64,", InputStreamSupplier.of(bytes)));

        assertEquals("data:image/png;base64," + Base64.getEncoder().encodeToString(bytes), text);
    }

    @Test
    void aReaderWithoutAPrefixIsTheBase64Alone() throws IOException {
        byte[] bytes = "ping".getBytes(UTF_8);

        String text = read(new Base64Reader(null, InputStreamSupplier.of(bytes)));

        assertEquals(Base64.getEncoder().encodeToString(bytes), text);
    }

    @Test
    void everyRemainderIsPaddedTheWayTheJdkPadsIt() throws IOException {
        for (int length = 0; length <= 6; length++) {
            byte[] bytes = new byte[length];
            for (int position = 0; position < length; position++) {
                bytes[position] = (byte) (position + 1);
            }

            String text = read(new Base64Reader(InputStreamSupplier.of(bytes)));

            assertEquals(Base64.getEncoder().encodeToString(bytes), text, "length " + length);
        }
    }

    @Test
    void aPayloadLargerThanOneChunkIsEncodedTheSameWay() throws IOException {
        byte[] bytes = new byte[10_000];
        new Random(42).nextBytes(bytes);

        String text = read(new Base64Reader("data:image/png;base64,", InputStreamSupplier.of(bytes)));

        assertEquals("data:image/png;base64," + Base64.getEncoder().encodeToString(bytes), text);
    }

    @Test
    void onlyAsMuchOfTheSourceIsReadAsIsBeingAskedFor() throws IOException {
        byte[] bytes = new byte[10_000];
        CountingSource source = new CountingSource(bytes);

        char[] first = new char[8];
        try (Base64Reader reader = new Base64Reader(source)) {
            assertEquals(8, reader.read(first, 0, 8));
        }

        assertTrue(source.bytesRead < bytes.length,
                "read " + source.bytesRead + " bytes of " + bytes.length);
    }

    @Test
    void theSourceIsOpenedOnlyWhenTheFirstCharacterIsAskedFor() throws IOException {
        CountingSource source = new CountingSource("ping".getBytes(UTF_8));
        try (Base64Reader ignored = new Base64Reader(source)) {
            // Nothing is read; the assertion is that opening the source was deferred.
        }

        assertEquals(0, source.opened);
    }

    @Test
    void theSourceIsClosedOnceItsContentHasBeenReadOut() throws IOException {
        CountingSource source = new CountingSource("ping".getBytes(UTF_8));
        Base64Reader reader = new Base64Reader(source);

        read(reader);

        assertTrue(source.closed);
    }

    @Test
    void closingTheReaderClosesTheSource() throws IOException {
        // Larger than one chunk, so the source is still open after the first characters are read out.
        CountingSource source = new CountingSource(new byte[10_000]);
        Base64Reader reader = new Base64Reader(source);
        reader.read(new char[1], 0, 1);

        assertFalse(source.closed);
        reader.close();

        assertTrue(source.closed);
    }

    @Test
    void aSourceThatFailsToOpenFailsWhenTheFirstCharacterIsAskedFor() throws IOException {
        InputStreamSupplier source = () -> {
            throw new IOException("no bytes today");
        };
        Base64Reader reader = new Base64Reader(source);
        try (reader) {
            IOException thrown = assertThrows(IOException.class, () -> reader.read(new char[1], 0, 1));

            assertEquals("no bytes today", thrown.getMessage());
        }
    }

    private static String read(Reader reader) throws IOException {
        StringBuilder text = new StringBuilder();
        char[] buffer = new char[64];
        try (reader) {
            for (int count = reader.read(buffer); count != -1; count = reader.read(buffer)) {
                text.append(buffer, 0, count);
            }
        }
        return text.toString();
    }

    /** A source that remembers being opened and closed, and how much of it has been read. */
    private static class CountingSource implements InputStreamSupplier {

        private final byte[] bytes;

        private int opened;

        private int bytesRead;

        private boolean closed;

        CountingSource(byte[] bytes) {
            this.bytes = bytes;
        }

        @Override
        public InputStream get() {
            opened++;
            return new ByteArrayInputStream(bytes) {

                @Override
                public synchronized int read(byte[] target, int offset, int length) {
                    int count = super.read(target, offset, length);
                    if (count > 0) {
                        bytesRead += count;
                    }
                    return count;
                }

                @Override
                public void close() throws IOException {
                    closed = true;
                    super.close();
                }
            };
        }
    }

}
