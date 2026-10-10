package io.github.synapse4j.data;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;

class ListPartTest {

    @Test
    void copiesPartsItIsGiven() {
        List<ContentPart> parts = new ArrayList<>(List.of(new TextPart("hello")));

        ListPart part = new ListPart(parts);
        parts.clear();

        assertEquals("hello", ((TextPart) part.getSubParts().get(0)).getText());
    }

}
