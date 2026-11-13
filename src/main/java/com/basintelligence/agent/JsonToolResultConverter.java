package com.basintelligence.agent;

import java.lang.reflect.Type;

import org.springframework.ai.tool.execution.ToolCallResultConverter;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;

/**
 * Serialises tool results as JSON with ISO-8601 timestamps (e.g. "2026-11-12T05:20:00Z") instead of epoch
 * numbers, so the agent reads times directly.
 */
public class JsonToolResultConverter implements ToolCallResultConverter {

    private static final ObjectMapper MAPPER = JsonMapper.builder()
            .addModule(new JavaTimeModule())
            .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS)
            .build();

    @Override
    public String convert(Object result, Type returnType) {
        if (returnType == Void.TYPE) {
            return "\"Done\"";
        }
        try {
            return MAPPER.writeValueAsString(result);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Could not serialise tool result", e);
        }
    }
}
