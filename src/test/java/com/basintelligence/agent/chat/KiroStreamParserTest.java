package com.basintelligence.agent.chat;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.databind.ObjectMapper;

/** The event shapes below are copied from a real `kiro-cli chat --v3 --output-format stream-json` run (2.36). */
class KiroStreamParserTest {

    static final String SID = "sess_74a467ae-ae38-419e-8821-a1df6041b470";

    @Test
    void readsSessionIdCreditsAndTheFinalReply() {
        var parser = new KiroStreamParser(new ObjectMapper(), 1000);
        parser.accept("{\"type\":\"runStarted\",\"data\":{\"payloadSchema\":\"acp\",\"acpProtocolVersion\":1,\"engine\":\"v3\"}}");
        parser.accept("[INFO] kas.server.starting");
        parser.accept("{\"type\":\"sessionUpdate\",\"data\":{\"sessionId\":\"" + SID + "\",\"update\":{\"sessionUpdate\":"
                + "\"agent_message_chunk\",\"content\":{\"type\":\"text\",\"text\":\"PUMP-02 looks \"}}}}");
        parser.accept("{\"type\":\"sessionUpdate\",\"data\":{\"sessionId\":\"" + SID + "\",\"update\":{\"sessionUpdate\":"
                + "\"session_info_update\",\"_meta\":{\"kiro\":{\"promptTurnSummaries\":[{\"unit\":\"credit\","
                + "\"unitPlural\":\"credits\",\"usage\":0.01815999800995025}],\"elapsedTime\":2042,\"status\":\"success\","
                + "\"kind\":\"turn_complete\"}}}}}");
        parser.accept("{\"type\":\"runFinished\",\"data\":{\"sessionId\":\"" + SID + "\",\"status\":\"success\","
                + "\"stopReason\":\"end_turn\",\"finalText\":\"PUMP-02 looks healthy.\",\"finalTextTruncated\":false}}");

        assertThat(parser.sessionId()).isEqualTo(SID);
        assertThat(parser.succeeded()).isTrue();
        assertThat(parser.reply()).isEqualTo("PUMP-02 looks healthy.");
        assertThat(parser.streamedText()).isEqualTo("PUMP-02 looks ");
        assertThat(parser.credits()).isEqualTo(0.01815999800995025);
    }

    /** As a real resumed run: the stored model is reported first, then the requested one before the turn starts. */
    @Test
    void acceptsTheAutoModelWhenItIsSetBeforeTheTurnStarts() {
        var parser = new KiroStreamParser(new ObjectMapper(), 1000);
        parser.accept(model("claude-haiku-5.5"));
        parser.accept(model("auto"));
        parser.accept(TURN_START);

        assertThat(parser.modelViolation()).isNull();
        assertThat(parser.modelConfirmed()).isTrue();
        assertThat(parser.model()).isEqualTo("auto");
    }

    @Test
    void anyOtherModelAtTheTurnStartOrDuringTheTurnIsAViolation() {
        var other = new KiroStreamParser(new ObjectMapper(), 1000);
        other.accept(model("claude-opus-5.5"));
        other.accept(TURN_START);
        assertThat(other.modelViolation()).isEqualTo("claude-opus-5.5");
        assertThat(other.modelConfirmed()).isFalse();

        var switched = new KiroStreamParser(new ObjectMapper(), 1000);
        switched.accept(model("auto"));
        switched.accept(TURN_START);
        switched.accept(model("gpt-5.6-sol"));
        assertThat(switched.modelViolation()).isEqualTo("gpt-5.6-sol");

        var silent = new KiroStreamParser(new ObjectMapper(), 1000);
        silent.accept(TURN_START);
        assertThat(silent.modelViolation()).isEqualTo("unreported");
    }

    static final String TURN_START = "{\"type\":\"sessionUpdate\",\"data\":{\"sessionId\":\"" + SID + "\",\"update\":"
            + "{\"sessionUpdate\":\"session_info_update\",\"_meta\":{\"kiro\":{\"turnStart\":true,\"kind\":\"turn_start\"}}}}}";

    static String model(String value) {
        return "{\"type\":\"sessionUpdate\",\"data\":{\"sessionId\":\"" + SID + "\",\"update\":{\"sessionUpdate\":"
                + "\"config_option_update\",\"configOptions\":[{\"id\":\"mode\",\"currentValue\":\"bas-web-assistant\"},"
                + "{\"id\":\"model\",\"name\":\"Model\",\"category\":\"model\",\"type\":\"select\",\"currentValue\":\""
                + value + "\"}]}}}";
    }

    @Test
    void withoutAFinalEventTheStreamedTextIsKeptButTheRunDidNotSucceed() {
        var parser = new KiroStreamParser(new ObjectMapper(), 10);
        parser.accept("{\"type\":\"sessionUpdate\",\"data\":{\"sessionId\":\"not-a-session --trust-all-tools\",\"update\":"
                + "{\"sessionUpdate\":\"agent_message_chunk\",\"content\":{\"type\":\"text\",\"text\":\"0123456789abcdef\"}}}}");
        parser.accept("{not json");
        parser.accept("[1,2]");

        assertThat(parser.sessionId()).as("only well-formed session ids are kept").isNull();
        assertThat(parser.succeeded()).isFalse();
        assertThat(parser.reply()).isEqualTo("0123456789");
    }
}
