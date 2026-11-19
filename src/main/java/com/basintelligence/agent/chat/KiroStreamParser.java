package com.basintelligence.agent.chat;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * Reads the events Kiro CLI prints with {@code --output-format stream-json}, one JSON object per line: the session id
 * (to continue the conversation), the reply text as it streams, the credits used, the model and the final result.
 * Lines that are not events are ignored. Which tools ran is not taken from here but from the server's own audit, which
 * records what actually executed. Plain class; one instance per run, safe to read while the run writes.
 * <p>
 * Kiro reports its model in {@code config_option_update} events (option {@code model}). The run must use
 * {@link KiroCommand#MODEL}: if the model is anything else, or unreported, when a turn starts, or changes to anything
 * else during a turn, {@link #modelViolation()} says which, and the run must be stopped.
 */
public final class KiroStreamParser {

    private final ObjectMapper json;
    private final int maxText;
    private final StringBuilder streamed = new StringBuilder();
    private String sessionId;
    private String finalText;
    private String runStatus;
    private String model;
    private boolean turnStarted;
    private String modelViolation;
    private double credits;
    private boolean finished;

    public KiroStreamParser(ObjectMapper json, int maxText) {
        this.json = json;
        this.maxText = maxText;
    }

    public synchronized void accept(String line) {
        JsonNode event;
        try {
            event = json.readTree(line);
        } catch (JsonProcessingException e) {
            return;
        }
        if (event == null || !event.isObject()) {
            return;
        }
        JsonNode data = event.path("data");
        String sid = data.path("sessionId").asText(null);
        if (sid != null && KiroCommand.SESSION_ID.matcher(sid).matches()) {
            sessionId = sid;
        }
        if ("runFinished".equals(event.path("type").asText())) {
            finished = true;
            runStatus = data.path("status").asText(null);
            finalText = data.path("finalText").isTextual() ? cap(data.path("finalText").asText()) : null;
            return;
        }
        JsonNode update = data.path("update");
        switch (update.path("sessionUpdate").asText("")) {
            case "agent_message_chunk" -> {
                JsonNode content = update.path("content");
                if ("text".equals(content.path("type").asText()) && streamed.length() < maxText) {
                    streamed.append(content.path("text").asText(""));
                    if (streamed.length() > maxText) {
                        streamed.setLength(maxText);
                    }
                }
            }
            case "session_info_update" -> {
                JsonNode kiro = update.path("_meta").path("kiro");
                if ("turn_start".equals(kiro.path("kind").asText())) {
                    turnStarted = true;
                    checkModel();
                }
                for (JsonNode summary : kiro.path("promptTurnSummaries")) {
                    if ("credit".equals(summary.path("unit").asText()) && summary.path("usage").isNumber()) {
                        credits += summary.path("usage").asDouble();
                    }
                }
            }
            case "config_option_update" -> {
                for (JsonNode option : update.path("configOptions")) {
                    if ("model".equals(option.path("id").asText()) && option.path("currentValue").isTextual()) {
                        model = option.path("currentValue").asText();
                        if (turnStarted) {
                            checkModel();
                        }
                    }
                }
            }
            default -> {
                // tool calls, plans and status updates: not needed here
            }
        }
    }

    public synchronized String sessionId() {
        return sessionId;
    }

    /** True once Kiro reported the run finished with status success. */
    public synchronized boolean succeeded() {
        return finished && "success".equals(runStatus);
    }

    /** The final reply, or the text streamed so far if Kiro never sent one. */
    public synchronized String reply() {
        if (finalText != null && !finalText.isBlank()) {
            return finalText;
        }
        return streamed.isEmpty() ? null : streamed.toString();
    }

    public synchronized String streamedText() {
        return streamed.toString();
    }

    public synchronized double credits() {
        return credits;
    }

    /** The model Kiro last reported, or null. */
    public synchronized String model() {
        return model;
    }

    /** True once Kiro reported {@link KiroCommand#MODEL} at the start of a turn and never anything else. */
    public synchronized boolean modelConfirmed() {
        return turnStarted && modelViolation == null && KiroCommand.MODEL.equals(model);
    }

    /** The model that was not allowed ("unreported" if none was reported by the start of a turn), or null. */
    public synchronized String modelViolation() {
        return modelViolation;
    }

    private void checkModel() {
        if (modelViolation == null && !KiroCommand.MODEL.equals(model)) {
            modelViolation = model == null ? "unreported" : model;
        }
    }

    private String cap(String text) {
        return text.length() <= maxText ? text : text.substring(0, maxText);
    }
}
