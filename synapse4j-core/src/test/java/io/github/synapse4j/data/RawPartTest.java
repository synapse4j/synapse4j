package io.github.synapse4j.data;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.Map;

import org.junit.jupiter.api.Test;

class RawPartTest {

    @Test
    void membersRoundTripWhateverTheyAreNamed() {
        // A JSON object names its members with strings, and "" or "a.b" is a name like any other: the
        // part holds the element as it was read, so it goes back out spelled the same way.
        Map<String, Object> members = Map.of("", 1, "a.b", 2);

        RawPart part = RawPart.of(members);

        assertEquals(members, part.members());
    }

}
