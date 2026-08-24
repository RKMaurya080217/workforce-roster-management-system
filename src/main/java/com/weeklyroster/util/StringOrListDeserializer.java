package com.weeklyroster.util;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.JsonToken;
import com.fasterxml.jackson.databind.DeserializationContext;
import com.fasterxml.jackson.databind.JsonDeserializer;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

public class StringOrListDeserializer extends JsonDeserializer<String> {

    @Override
    public String deserialize(JsonParser p, DeserializationContext ctxt) throws IOException {
        JsonToken token = p.currentToken();
        if (token == JsonToken.VALUE_STRING) {
            String val = p.getText();
            return val != null ? val.trim() : null;
        } else if (token == JsonToken.START_ARRAY) {
            List<String> items = new ArrayList<>();
            while (p.nextToken() != JsonToken.END_ARRAY) {
                if (p.currentToken() == JsonToken.VALUE_STRING) {
                    String s = p.getText();
                    if (s != null && !s.isBlank()) {
                        items.add(s.trim());
                    }
                }
            }
            return String.join(", ", items);
        } else if (token == JsonToken.VALUE_NULL) {
            return null;
        }
        return p.getValueAsString();
    }
}