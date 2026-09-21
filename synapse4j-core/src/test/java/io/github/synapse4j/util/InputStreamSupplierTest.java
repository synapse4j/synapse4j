package io.github.synapse4j.util;

import static java.nio.charset.StandardCharsets.ISO_8859_1;
import static java.nio.charset.StandardCharsets.UTF_8;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class InputStreamSupplierTest {

    @Test
    void aSourceOfBytesOpensAFreshStreamEveryTime() throws IOException {
        InputStreamSupplier source = InputStreamSupplier.of("ping".getBytes(UTF_8));

        assertEquals("ping", read(source.get()));
        assertEquals("ping", read(source.get()));
    }

    @Test
    void aSourceOfBytesUsesTheArrayItWasGivenRatherThanACopy() throws IOException {
        byte[] bytes = "ping".getBytes(UTF_8);
        InputStreamSupplier source = InputStreamSupplier.of(bytes);

        bytes[0] = 'k';

        assertEquals("king", read(source.get()));
    }

    @Test
    void aSourceOfTextIsEncodedInTheGivenCharset() throws IOException {
        InputStreamSupplier source = InputStreamSupplier.of("héllo", ISO_8859_1);

        assertArrayEquals("h\u00E9llo".getBytes(ISO_8859_1), source.get().readAllBytes());
    }

    @Test
    void aSourceOfAFileOpensItAgainForEveryCall(@TempDir Path directory) throws IOException {
        Path file = directory.resolve("image.bin");
        Files.write(file, new byte[] { 1, 2, 3 });
        InputStreamSupplier source = InputStreamSupplier.of(file);

        assertArrayEquals(new byte[] { 1, 2, 3 }, source.get().readAllBytes());

        Files.write(file, new byte[] { 4, 5 });

        assertArrayEquals(new byte[] { 4, 5 }, source.get().readAllBytes());
    }

    @Test
    void aFileThatIsNotThereFailsWhenItIsOpened(@TempDir Path directory) {
        InputStreamSupplier source = InputStreamSupplier.of(directory.resolve("missing.bin"));

        assertThrows(IOException.class, source::get);
    }

    @Test
    void aSourceAlreadyInTheSuppliersShapeIsAdapted() throws IOException {
        InputStreamSupplier source = InputStreamSupplier
                .of(() -> new ByteArrayInputStream("ping".getBytes(UTF_8)));

        assertEquals("ping", read(source.get()));
    }

    @Test
    void aStreamThatCanOnlyBeOpenedOnceAnswersOnceAndThenRefuses() throws IOException {
        InputStream stream = new ByteArrayInputStream("ping".getBytes(UTF_8));
        InputStreamSupplier source = InputStreamSupplier.once(stream);

        assertSame(stream, source.get());

        IllegalStateException thrown = assertThrows(IllegalStateException.class, source::get);

        assertEquals("this source opens once, and it has already been opened", thrown.getMessage());
    }

    @Test
    void aFailureToOpenArrivesAsItIs() {
        InputStreamSupplier source = () -> {
            throw new IOException("no bytes today");
        };

        IOException thrown = assertThrows(IOException.class, source::get);

        assertEquals("no bytes today", thrown.getMessage());
    }

    private static String read(InputStream stream) throws IOException {
        try (stream) {
            return new String(stream.readAllBytes(), UTF_8);
        }
    }

}
