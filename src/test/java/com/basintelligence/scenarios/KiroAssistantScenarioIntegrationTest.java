package com.basintelligence.scenarios;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.flash;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.Arrays;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

import com.basintelligence.agent.chat.AssistantRepository;
import com.basintelligence.agent.chat.AssistantRepository.Message;

/**
 * "An engineer talks to Kiro in the browser." The server runs a fake Kiro CLI (src/test/resources/fake-kiro) in place of
 * the real one, which reports what it received. Each message runs it once with a run token and none of the app's
 * environment; follow-ups resume Kiro's session; conversations are private; a run past its time limit is stopped
 * with its child processes; one run per engineer at a time; failures and bad input are explained.
 */
@SpringBootTest(properties = {"bas.assistant.enabled=true", "bas.assistant.workspace=src/test/resources/fake-kiro",
        "bas.assistant.command=./kiro-cli", "bas.assistant.timeout=3s", "bas.assistant.max-message-chars=200"})
@AutoConfigureMockMvc
@ActiveProfiles("test")
class KiroAssistantScenarioIntegrationTest {

    static final RequestPostProcessor ENGINEER = user("engineer").roles("ENGINEER", "VIEWER");
    static final RequestPostProcessor ENGINEER2 = user("engineer2").roles("ENGINEER", "VIEWER");
    static final RequestPostProcessor ADMIN = user("admin").roles("ADMIN", "ENGINEER", "VIEWER");
    static final RequestPostProcessor VIEWER = user("viewer").roles("VIEWER");
    static final Pattern CONVERSATION = Pattern.compile("/assistant/conversations/(\\d+)");
    /** Variables KiroCommand passes, plus those a shell sets for itself. */
    static final Set<String> ALLOWED_ENV = Set.of("BAS_RUN_TOKEN", "HOME", "USER", "LOGNAME", "PATH", "LANG", "TMPDIR",
            "XDG_CONFIG_HOME", "XDG_DATA_HOME", "XDG_CACHE_HOME", "XDG_RUNTIME_DIR", "DBUS_SESSION_BUS_ADDRESS",
            "PWD", "OLDPWD", "SHLVL", "_");

    @Autowired MockMvc mvc;
    @Autowired AssistantRepository conversations;
    @Autowired JdbcClient jdbc;

    @Test
    void aConversationContinuesKirosSessionAndStaysPrivate() throws Exception {
        long id = send(ENGINEER, null, "Why is PUMP-02 healthy today?");
        Message first = awaitFinished(id);
        assertThat(first.status()).isEqualTo(AssistantRepository.Status.DONE);
        assertThat(first.content()).contains("answered: Why is PUMP-02 healthy today?", "model=auto", "resume=none",
                "token=ok", "trust=no");
        assertThat(first.credits()).isEqualTo(0.25);
        Matcher env = Pattern.compile("env=(.*)$").matcher(first.content());
        assertThat(env.find()).isTrue();
        List<String> names = Arrays.stream(env.group(1).trim().split(" ")).toList();
        assertThat(names).contains("BAS_RUN_TOKEN", "HOME", "PATH").allMatch(ALLOWED_ENV::contains);

        String page = page("/assistant/conversations/" + id, ENGINEER);
        assertThat(page).contains("Why is PUMP-02 healthy today?", "<strong>Fake Kiro</strong> answered", "AI interpretation",
                "0.25 credits");

        send(ENGINEER, id, "And the vibration spikes?");
        assertThat(awaitFinished(id).content()).contains("resume=sess_11111111-2222-3333-4444-555555555555", "model=auto");
        assertThat(conversations.findConversation(id).orElseThrow().kiroSessionId())
                .isEqualTo("sess_11111111-2222-3333-4444-555555555555");

        // private: another engineer gets 404 for the page, the status and a reply; viewers cannot send at all
        mvc.perform(get("/assistant/conversations/" + id).with(ENGINEER2)).andExpect(status().isNotFound());
        mvc.perform(get("/assistant/messages/" + first.id() + "/status.json").with(ENGINEER2)).andExpect(status().isNotFound());
        mvc.perform(post("/assistant/messages").with(ENGINEER2).with(csrf()).param("conversationId", String.valueOf(id))
                .param("message", "hello")).andExpect(status().isNotFound());
        mvc.perform(post("/assistant/messages").with(VIEWER).with(csrf()).param("message", "hello"))
                .andExpect(status().isForbidden());
        assertThat(page("/assistant", ENGINEER2)).doesNotContain("Why is PUMP-02 healthy today?");

        assertThat(jdbc.sql("SELECT COUNT(*) FROM audit_log WHERE action = 'KIRO_ASSISTANT_ASKED' AND actor = 'engineer'")
                .query(Long.class).single()).isGreaterThanOrEqualTo(2);
        assertThat(jdbc.sql("SELECT COUNT(*) FROM audit_log WHERE action = 'KIRO_ASSISTANT_ANSWERED' AND detail LIKE 'DONE for engineer%'")
                .query(Long.class).single()).isGreaterThanOrEqualTo(2);
    }

    @Test
    void aRunPastItsTimeLimitIsStoppedAndAnEngineerHasOneRunAtATime() throws Exception {
        long started = System.nanoTime();
        long id = send(ENGINEER2, null, "SLEEP please");
        mvc.perform(post("/assistant/messages").with(ENGINEER2).with(csrf()).param("conversationId", String.valueOf(id))
                        .param("message", "second question"))
                .andExpect(status().is3xxRedirection())
                .andExpect(flash().attribute("flashError", containsString("still answering")));
        long kiroMessage = conversations.messages(id, 10).getLast().id();
        mvc.perform(get("/assistant/messages/" + kiroMessage + "/status.json").with(ENGINEER2))
                .andExpect(status().isOk()).andExpect(jsonPath("$.finished").value(false))
                .andExpect(jsonPath("$.status").value("RUNNING"));

        Message timedOut = awaitFinished(id);
        // The fake starts `sleep 30`, which holds the output open: finishing this fast means the whole tree was stopped.
        assertThat((System.nanoTime() - started) / 1_000_000_000).isLessThan(15);
        assertThat(timedOut.status()).isEqualTo(AssistantRepository.Status.TIMED_OUT);
        assertThat(timedOut.errorMessage()).contains("did not finish within");
        mvc.perform(get("/assistant/messages/" + kiroMessage + "/status.json").with(ENGINEER2))
                .andExpect(jsonPath("$.finished").value(true)).andExpect(jsonPath("$.status").value("TIMED_OUT"));
    }

    @Test
    void aRunThatReportsAnyModelButAutoIsStoppedAndItsReplyDiscarded() throws Exception {
        for (String trigger : List.of("WRONGMODEL", "NOMODEL")) {
            Message refused = awaitFinished(send(ADMIN, null, trigger + " question"));
            assertThat(refused.status()).as(trigger).isEqualTo(AssistantRepository.Status.FAILED);
            assertThat(refused.content()).as(trigger).isNull();
            assertThat(refused.errorMessage()).as(trigger).contains("Auto model");
        }

        long started = System.nanoTime();
        Message switched = awaitFinished(send(ADMIN, null, "SWITCHMODEL question"));
        // the fake switches model mid-turn and then waits 30 s: finishing fast means the run was stopped at the switch
        assertThat((System.nanoTime() - started) / 1_000_000_000).isLessThan(15);
        assertThat(switched.status()).isEqualTo(AssistantRepository.Status.FAILED);
        assertThat(switched.errorMessage()).contains("claude-opus-5.5", "only runs with the Auto model");
    }

    @Test
    void failuresAreExplainedAndBadMessagesRefused() throws Exception {
        long id = send(ADMIN, null, "MCPDOWN test");
        Message failed = awaitFinished(id);
        assertThat(failed.status()).isEqualTo(AssistantRepository.Status.FAILED);
        assertThat(failed.errorMessage()).contains("could not connect to the PlantSentinel MCP server");
        assertThat(page("/assistant/conversations/" + id, ADMIN)).contains("could not connect");

        mvc.perform(post("/assistant/messages").with(ADMIN).with(csrf()).param("message", "x".repeat(201)))
                .andExpect(status().is3xxRedirection())
                .andExpect(flash().attribute("flashError", containsString("longer than 200")));
        mvc.perform(post("/assistant/messages").with(ADMIN).with(csrf()).param("message", " \u0000 "))
                .andExpect(flash().attribute("flashError", containsString("Type a message")));
    }

    private long send(RequestPostProcessor who, Long conversationId, String message) throws Exception {
        var request = post("/assistant/messages").with(who).with(csrf()).param("message", message);
        if (conversationId != null) {
            request.param("conversationId", String.valueOf(conversationId));
        }
        String location = mvc.perform(request).andExpect(status().is3xxRedirection()).andReturn().getResponse()
                .getRedirectedUrl();
        Matcher m = CONVERSATION.matcher(location);
        assertThat(m.matches()).as("redirect to the conversation, not " + location).isTrue();
        return Long.parseLong(m.group(1));
    }

    private Message awaitFinished(long conversationId) throws InterruptedException {
        for (int i = 0; i < 150; i++) {
            Message last = conversations.messages(conversationId, 10).getLast();
            if (last.role() == AssistantRepository.Role.KIRO && last.status() != AssistantRepository.Status.RUNNING) {
                return last;
            }
            Thread.sleep(100);
        }
        throw new AssertionError("the Kiro run did not finish");
    }

    private String page(String path, RequestPostProcessor who) throws Exception {
        return mvc.perform(get(path).with(who)).andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
    }
}
