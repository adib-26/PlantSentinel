package com.basintelligence.agent.chat;

import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import com.basintelligence.agent.AgentAuditService;
import com.basintelligence.agent.BasMcpTools;
import com.basintelligence.agent.McpToolExposure;
import com.basintelligence.agent.chat.AssistantRepository.Conversation;
import com.basintelligence.agent.chat.AssistantRepository.Message;
import com.basintelligence.agent.chat.AssistantRepository.ReportLink;
import com.basintelligence.agent.chat.AssistantRepository.Role;
import com.basintelligence.agent.chat.AssistantRepository.Status;
import com.basintelligence.common.AgentRun;
import com.basintelligence.common.AuditService;
import com.basintelligence.common.BasMetrics;
import com.basintelligence.common.InvalidStateException;
import com.basintelligence.common.NotFoundException;
import com.basintelligence.common.ServiceUnavailableException;
import com.basintelligence.common.Text;
import com.basintelligence.config.BasProperties;
import com.fasterxml.jackson.databind.ObjectMapper;

import io.micrometer.core.instrument.MeterRegistry;
import jakarta.annotation.PreDestroy;

/**
 * The Kiro assistant: an engineer's message starts one Kiro CLI run on this server ({@link KiroCommand}), with the
 * web-assistant agent and a run token that limits Kiro's MCP calls to the engineer's machines. Kiro answers through
 * the same audited MCP tools as in the terminal; this class only starts the run, watches it, and stores the reply.
 * <p>
 * Safeguards: engineers only and rate limited (controller); one run per engineer and at most
 * {@code bas.assistant.max-concurrent-runs} on the server; a time limit after which the whole process tree is stopped;
 * bounded output; the token is revoked when the run ends. A run never creates maintenance drafts
 * ({@code AgentToolPolicy}). Runs use only the Auto model: it is passed on every run, and a run that reports any other
 * model is stopped and its reply discarded.
 */
@Service
public class KiroAssistantService {

    private static final Logger log = LoggerFactory.getLogger(KiroAssistantService.class);
    static final int MAX_REPLY_CHARS = 20_000;
    static final int MAX_LINE_CHARS = 1_000_000;
    static final long MAX_OUTPUT_CHARS = 20_000_000;
    private static final int MESSAGES_SHOWN = 60;
    private static final int CONVERSATIONS_SHOWN = 30;
    /** A run token outlives the run's time limit by this much, so a slow last tool call still authenticates. */
    private static final Duration TOKEN_GRACE = Duration.ofMinutes(1);
    private static final Pattern MACHINE_CODE = Pattern.compile("[A-Za-z0-9-]{1,40}");

    public record Availability(boolean available, String reason) {
    }

    /** One MCP tool call of a run, as audited by the server. */
    public record Step(String tool, String machineCode, String status, Long durationMs, String error) {
    }

    public record MessageView(Message message, List<Step> steps, List<ReportLink> reports) {
    }

    public record ConversationView(Conversation conversation, List<MessageView> messages, boolean running) {
    }

    /** What the page polls while Kiro works. {@code text} is the reply streamed so far. */
    public record RunStatus(long messageId, String status, boolean finished, long elapsedSeconds, String text,
            List<Step> steps) {
    }

    private record Outcome(Status status, String content, String error) {
    }

    private static final class Run {
        final long messageId;
        final long conversationId;
        final String username;
        final String token;
        final Instant started;
        final Process process;
        final KiroStreamParser parser;
        volatile boolean timedOut;
        volatile boolean cancelled;
        volatile boolean overflow;
        volatile String wrongModel;
        volatile String stderrTail = "";
        volatile Thread pump;

        Run(long messageId, long conversationId, String username, String token, Instant started, Process process,
                KiroStreamParser parser) {
            this.messageId = messageId;
            this.conversationId = conversationId;
            this.username = username;
            this.token = token;
            this.started = started;
            this.process = process;
            this.parser = parser;
        }
    }

    private final BasProperties.Assistant config;
    private final AssistantRepository repository;
    private final AgentRunTokens tokens;
    private final AgentAuditService agentAudit;
    private final AuditService audit;
    private final ObjectMapper json;
    private final Clock clock;
    private final MeterRegistry meters;
    private final TransactionTemplate transactions;
    private final Path workspace;
    private final Path executable;
    private final Availability availability;
    private final Semaphore slots;
    private final Set<String> busyUsers = ConcurrentHashMap.newKeySet();
    private final Map<Long, Run> runs = new ConcurrentHashMap<>();

    public KiroAssistantService(BasProperties properties, AssistantRepository repository, AgentRunTokens tokens,
            AgentAuditService agentAudit, AuditService audit, ObjectMapper json, Clock clock, MeterRegistry meters,
            PlatformTransactionManager transactionManager) {
        this.config = properties.assistant();
        this.repository = repository;
        this.tokens = tokens;
        this.agentAudit = agentAudit;
        this.audit = audit;
        this.json = json;
        this.clock = clock;
        this.meters = meters;
        this.transactions = new TransactionTemplate(transactionManager);
        this.slots = new Semaphore(config.maxConcurrentRuns());
        this.workspace = Path.of(config.workspace()).toAbsolutePath().normalize();
        this.executable = config.enabled() ? resolve(config.command(), workspace) : null;
        this.availability = check();
        log.info(availability.available() ? "Kiro assistant: {} with agent {} in {}" : "Kiro assistant unavailable: {}",
                availability.available() ? executable : availability.reason(), config.agent(), workspace);
    }

    public Availability availability() {
        return availability;
    }

    public int maxMessageChars() {
        return config.maxMessageChars();
    }

    /**
     * Stores the message and starts Kiro on it. Returns the conversation id; the reply arrives asynchronously.
     *
     * @param conversationId null to start a new conversation
     */
    public long send(String username, Long conversationId, String text) {
        if (!availability.available()) {
            throw new ServiceUnavailableException("The Kiro assistant is not available: " + availability.reason());
        }
        String message = clean(text);
        Conversation conversation = conversationId == null ? null : owned(conversationId, username);
        if (!busyUsers.add(username)) {
            throw new InvalidStateException("Kiro is still answering your previous message. Wait until it has finished.");
        }
        boolean slot = false;
        Long kiroMessageId = null;
        String token = null;
        try {
            slot = slots.tryAcquire();
            if (!slot) {
                throw new InvalidStateException("Kiro is already answering " + config.maxConcurrentRuns()
                        + " messages on this server. Try again in a minute.");
            }
            if (conversation != null && repository.hasRunningMessage(conversation.id())) {
                throw new InvalidStateException("Kiro is still answering in this conversation.");
            }
            Instant now = clock.instant();
            long[] ids = transactions.execute(s -> {
                long c = conversation != null ? conversation.id() : repository.insertConversation(username, title(message), now);
                repository.insertMessage(c, Role.USER, message, Status.SENT, now);
                long k = repository.insertMessage(c, Role.KIRO, null, Status.RUNNING, now);
                repository.updateConversation(c, null, now);
                return new long[] {c, k};
            });
            kiroMessageId = ids[1];
            audit.record(username, "KIRO_ASSISTANT_ASKED", "assistant-message:" + ids[1], Text.truncate(message, 200));
            token = tokens.issue(new AgentRun(username, ids[1]), config.timeout().plus(TOKEN_GRACE));
            KiroCommand command = KiroCommand.of(executable, config.agent(), message,
                    conversation == null ? null : conversation.kiroSessionId(), token, System.getenv(),
                    System.getProperty("user.home"), System.getProperty("user.name"));
            Process process = start(command);
            Run run = new Run(ids[1], ids[0], username, token, now, process,
                    new KiroStreamParser(json, MAX_REPLY_CHARS));
            runs.put(run.messageId, run);
            run.pump = Thread.ofVirtual().name("kiro-run-" + run.messageId).start(() -> pump(run));
            return ids[0];
        } catch (RuntimeException e) {
            if (token != null) {
                tokens.revoke(token);
            }
            if (kiroMessageId != null) {
                repository.finishMessage(kiroMessageId, Status.FAILED, null, e.getMessage(), null, clock.instant());
            }
            if (slot) {
                slots.release();
            }
            busyUsers.remove(username);
            throw e;
        }
    }

    public List<Conversation> conversations(String username) {
        return repository.conversations(username, CONVERSATIONS_SHOWN);
    }

    /** One of the engineer's own conversations; anyone else's answers 404. */
    public ConversationView conversation(String username, long id) {
        Conversation conversation = owned(id, username);
        List<MessageView> messages = repository.messages(id, MESSAGES_SHOWN).stream()
                .map(m -> m.role() == Role.KIRO ? new MessageView(m, steps(m.id()), repository.reports(m.id()))
                        : new MessageView(m, List.of(), List.of()))
                .toList();
        return new ConversationView(conversation, messages,
                messages.stream().anyMatch(v -> v.message().status() == Status.RUNNING));
    }

    public RunStatus status(String username, long messageId) {
        Message message = repository.findMessage(messageId).filter(m -> m.role() == Role.KIRO)
                .orElseThrow(() -> new NotFoundException("Message not found: " + messageId));
        owned(message.conversationId(), username);
        Run run = runs.get(messageId);
        Instant end = message.finishedAt() != null ? message.finishedAt() : clock.instant();
        return new RunStatus(messageId, message.status().name(), message.status() != Status.RUNNING,
                Math.max(0, Duration.between(message.createdAt(), end).toSeconds()),
                run == null ? null : Text.truncate(run.parser.streamedText(), 4000), steps(messageId));
    }

    /** No run survives a restart; their messages end as FAILED instead of waiting forever. */
    @EventListener(ApplicationReadyEvent.class)
    public void failInterruptedRuns() {
        int failed = repository.failRunning("The server restarted while Kiro was answering.", clock.instant());
        if (failed > 0) {
            log.info("Marked {} interrupted Kiro assistant run(s) as failed", failed);
        }
    }

    @PreDestroy
    void stopAll() {
        List<Run> active = new ArrayList<>(runs.values());
        for (Run run : active) {
            run.cancelled = true;
            stop(run.process);
        }
        for (Run run : active) {
            try {
                if (run.pump != null) {
                    run.pump.join(Duration.ofSeconds(5));
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            }
        }
    }

    private Process start(KiroCommand command) {
        ProcessBuilder builder = new ProcessBuilder(command.arguments()).directory(workspace.toFile());
        builder.environment().clear();
        builder.environment().putAll(command.environment());
        builder.redirectInput(ProcessBuilder.Redirect.from(new File("/dev/null")));
        try {
            return builder.start();
        } catch (IOException e) {
            throw new ServiceUnavailableException("Kiro CLI could not be started: " + e.getMessage());
        }
    }

    private void pump(Run run) {
        Thread stderr = Thread.ofVirtual().start(() -> run.stderrTail = tail(run.process.getErrorStream(), 4096));
        Thread watchdog = Thread.ofVirtual().start(() -> {
            try {
                Thread.sleep(config.timeout());
                if (run.process.isAlive()) {
                    run.timedOut = true;
                    stop(run.process);
                }
            } catch (InterruptedException e) {
                // the run ended in time
            }
        });
        try (Reader reader = new BufferedReader(new InputStreamReader(run.process.getInputStream(), StandardCharsets.UTF_8))) {
            BoundedLineReader lines = new BoundedLineReader(reader, MAX_LINE_CHARS);
            String line;
            while ((line = lines.next()) != null) {
                run.parser.accept(line);
                if (run.parser.modelViolation() != null) {
                    run.wrongModel = run.parser.modelViolation();
                    stop(run.process);
                    break;
                }
                if (lines.charsRead() > MAX_OUTPUT_CHARS) {
                    run.overflow = true;
                    stop(run.process);
                    break;
                }
            }
        } catch (IOException e) {
            log.debug("Kiro run {} output ended: {}", run.messageId, e.getMessage());
        } finally {
            try {
                if (!run.process.waitFor(10, TimeUnit.SECONDS)) {
                    stop(run.process);
                }
                stderr.join(Duration.ofSeconds(2));
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                stop(run.process);
            }
            watchdog.interrupt();
            finish(run);
        }
    }

    private void finish(Run run) {
        try {
            Instant now = clock.instant();
            Outcome outcome = outcome(run);
            double credits = run.parser.credits();
            repository.finishMessage(run.messageId, outcome.status(), outcome.content(), outcome.error(),
                    credits > 0 ? credits : null, now);
            repository.updateConversation(run.conversationId, run.parser.sessionId(), now);
            if (outcome.status() != Status.DONE) {
                log.warn("Kiro run {} ended {}: {} {}", run.messageId, outcome.status(), outcome.error(),
                        run.stderrTail.replace(run.token, "<run token>"));
            }
            meters.counter(BasMetrics.ASSISTANT_MESSAGES, "outcome", outcome.status().name()).increment();
            meters.timer(BasMetrics.ASSISTANT_DURATION).record(Duration.between(run.started, now));
            if (credits > 0) {
                meters.summary(BasMetrics.ASSISTANT_CREDITS).record(credits);
            }
            audit.record(BasMcpTools.AGENT, "KIRO_ASSISTANT_ANSWERED", "assistant-message:" + run.messageId,
                    outcome.status() + " for " + run.username + (credits > 0 ? ", " + Text.num(credits, 3) + " credits" : ""));
        } catch (RuntimeException e) {
            log.warn("Could not store the result of Kiro run {}: {}", run.messageId, e.getMessage());
        } finally {
            tokens.revoke(run.token);
            runs.remove(run.messageId);
            busyUsers.remove(run.username);
            slots.release();
        }
    }

    private Outcome outcome(Run run) {
        String reply = run.parser.reply();
        if (run.cancelled) {
            return new Outcome(Status.CANCELLED, reply, "The server stopped while Kiro was answering.");
        }
        if (run.timedOut) {
            return new Outcome(Status.TIMED_OUT, reply, "Kiro did not finish within " + config.timeout().toMinutes()
                    + " min; the run was stopped.");
        }
        if (run.overflow) {
            return new Outcome(Status.FAILED, reply, "Kiro's output exceeded the size limit; the run was stopped.");
        }
        if (run.wrongModel != null) {
            return new Outcome(Status.FAILED, null, ("unreported".equals(run.wrongModel)
                    ? "Kiro did not report which model it used"
                    : "Kiro reported the model " + run.wrongModel)
                    + ", but the Kiro assistant only runs with the Auto model. The run was stopped and its reply discarded.");
        }
        int exit = run.process.isAlive() ? -1 : run.process.exitValue();
        if (exit == 0 && run.parser.succeeded() && reply != null && !run.parser.modelConfirmed()) {
            return new Outcome(Status.FAILED, null, "Kiro did not confirm that it used the Auto model, so its reply "
                    + "was discarded.");
        }
        if (exit == 0 && run.parser.succeeded() && reply != null) {
            return new Outcome(Status.DONE, reply, null);
        }
        if (exit == 3) {
            return new Outcome(Status.FAILED, reply, "Kiro could not connect to the PlantSentinel MCP server at "
                    + "http://localhost:8080/mcp. The Kiro assistant needs the app on port 8080.");
        }
        if (exit == 0) {
            return new Outcome(Status.FAILED, reply, "Kiro ended without an answer.");
        }
        return new Outcome(Status.FAILED, reply, "Kiro stopped with exit code " + exit + ". Check that Kiro CLI is "
                + "signed in (kiro-cli login) and that Kiro trusts this folder.");
    }

    private List<Step> steps(long messageId) {
        return agentAudit.callsForAssistantMessage(messageId).stream()
                .map(c -> new Step(mcpName(c.toolName()), machineCode(c.argumentsJson()), c.status(), c.durationMs(),
                        c.errorMessage() == null ? null : Text.truncate(c.errorMessage(), 200)))
                .toList();
    }

    private Conversation owned(long conversationId, String username) {
        return repository.findConversation(conversationId).filter(c -> c.username().equals(username))
                .orElseThrow(() -> new NotFoundException("Conversation not found: " + conversationId));
    }

    private String clean(String text) {
        String message = text == null ? "" : text.replace("\r\n", "\n").replaceAll("[\\p{Cntrl}&&[^\\n\\t]]", "").strip();
        if (message.isEmpty()) {
            throw new IllegalArgumentException("Type a message");
        }
        if (message.length() > config.maxMessageChars()) {
            throw new IllegalArgumentException("The message is longer than " + config.maxMessageChars() + " characters");
        }
        return message;
    }

    private static String title(String message) {
        String first = message.lines().findFirst().orElse(message).strip();
        return first.length() <= 80 ? first : first.substring(0, 79) + "…";
    }

    private static String mcpName(String javaName) {
        McpToolExposure.Exposed exposed = McpToolExposure.NAMES.get(javaName);
        return exposed == null ? javaName : exposed.mcpName();
    }

    private String machineCode(String argumentsJson) {
        try {
            String code = argumentsJson == null ? null : json.readTree(argumentsJson).path("machineCode").asText(null);
            return code != null && MACHINE_CODE.matcher(code).matches() ? code.toUpperCase(java.util.Locale.ROOT) : null;
        } catch (IOException | RuntimeException e) {
            return null; // truncated or not JSON
        }
    }

    private Availability check() {
        if (!config.enabled()) {
            return new Availability(false, "it is switched off (bas.assistant.enabled).");
        }
        if (File.separatorChar != '/') {
            return new Availability(false, "it runs Kiro CLI as a child process, which is supported on macOS and Linux only.");
        }
        if (executable == null) {
            return new Availability(false, "Kiro CLI (" + config.command() + ") was not found. Install Kiro CLI or set "
                    + "bas.assistant.command to its path.");
        }
        Path agentFile = workspace.resolve(".kiro/agents/" + config.agent() + ".json");
        if (!Files.isRegularFile(agentFile)) {
            return new Availability(false, "the Kiro agent file .kiro/agents/" + config.agent() + ".json is missing in "
                    + workspace + ".");
        }
        return new Availability(true, null);
    }

    /** An absolute path, or the first match on the app's PATH and the usual Kiro CLI install folders. */
    static Path resolve(String command, Path workspace) {
        if (command.contains("/")) {
            Path path = Path.of(command);
            path = path.isAbsolute() ? path : workspace.resolve(path).normalize();
            return Files.isRegularFile(path) && Files.isExecutable(path) ? path : null;
        }
        List<String> dirs = new ArrayList<>();
        String pathVariable = System.getenv("PATH");
        if (pathVariable != null) {
            dirs.addAll(List.of(pathVariable.split(File.pathSeparator)));
        }
        String home = System.getProperty("user.home");
        dirs.addAll(List.of(home + "/.local/bin", "/opt/homebrew/bin", "/usr/local/bin"));
        for (String dir : dirs) {
            if (dir.isBlank()) {
                continue;
            }
            Path candidate = Path.of(dir, command);
            if (candidate.isAbsolute() && Files.isRegularFile(candidate) && Files.isExecutable(candidate)) {
                return candidate;
            }
        }
        return null;
    }

    /** Stops a process and everything it started (Kiro CLI runs a separate agent server). */
    static void stop(Process process) {
        List<ProcessHandle> tree = process.descendants().toList();
        tree.forEach(ProcessHandle::destroy);
        process.destroy();
        try {
            if (!process.waitFor(3, TimeUnit.SECONDS)) {
                process.destroyForcibly();
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            process.destroyForcibly();
        }
        tree.stream().filter(ProcessHandle::isAlive).forEach(ProcessHandle::destroyForcibly);
    }

    /** The last {@code max} bytes of a stream, as text. Reads it to the end so the child never blocks on a full pipe. */
    private static String tail(InputStream in, int max) {
        byte[] ring = new byte[max];
        long total = 0;
        byte[] buffer = new byte[8192];
        try (in) {
            int n;
            while ((n = in.read(buffer)) != -1) {
                for (int i = 0; i < n; i++) {
                    ring[(int) (total++ % max)] = buffer[i];
                }
            }
        } catch (IOException e) {
            // the process ended
        }
        int size = (int) Math.min(total, max);
        byte[] out = new byte[size];
        for (int i = 0; i < size; i++) {
            out[i] = ring[(int) ((total - size + i) % max)];
        }
        return new String(out, StandardCharsets.UTF_8);
    }
}
