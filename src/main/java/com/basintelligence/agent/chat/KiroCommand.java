package com.basintelligence.agent.chat;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * The one command the Kiro assistant runs: Kiro CLI, headless, with the web-assistant agent and the Auto model
 * ({@link #MODEL}, not configurable). No shell is involved.
 * The engineer's message is a single argument after {@code --}, so it is never read as an option, and no option that
 * widens Kiro's permissions (such as {@code --trust-all-tools}) is ever passed. The child process gets a minimal
 * environment: what Kiro needs to find its login and temporary files, plus the run token. None of the app's own
 * variables (database URL, agent key, passwords) reach it. Plain class, so it can be tested without a process.
 */
public final class KiroCommand {

    static final Pattern AGENT = Pattern.compile("[a-z0-9][a-z0-9-]{0,63}");
    static final Pattern SESSION_ID = Pattern.compile("sess_[0-9a-f-]{36}");
    /** The only model the Kiro assistant may use: Kiro's router, which picks a model per task at 1.0x credits. */
    public static final String MODEL = "auto";
    /** Taken from the app's environment when set; Kiro may need them to find its login on Linux. */
    static final List<String> PASSED_THROUGH = List.of("TMPDIR", "XDG_CONFIG_HOME", "XDG_DATA_HOME", "XDG_CACHE_HOME",
            "XDG_RUNTIME_DIR", "DBUS_SESSION_BUS_ADDRESS", "KIRO_API_KEY");
    static final String TOKEN_VARIABLE = "BAS_RUN_TOKEN";

    private final List<String> arguments;
    private final Map<String, String> environment;

    private KiroCommand(List<String> arguments, Map<String, String> environment) {
        this.arguments = arguments;
        this.environment = environment;
    }

    /**
     * @param executable      absolute path of kiro-cli
     * @param resumeSessionId Kiro's session id to continue, or null for a new conversation
     */
    public static KiroCommand of(Path executable, String agent, String message, String resumeSessionId, String runToken,
            Map<String, String> parentEnvironment, String home, String user) {
        if (!executable.isAbsolute()) {
            throw new IllegalArgumentException("kiro-cli must be resolved to an absolute path");
        }
        if (!AGENT.matcher(agent).matches()) {
            throw new IllegalArgumentException("invalid agent name");
        }
        if (message == null || message.isBlank() || message.indexOf('\0') >= 0) {
            throw new IllegalArgumentException("invalid message");
        }
        // --model on every run, also when resuming: it replaces the model a resumed session was stored with
        List<String> args = new ArrayList<>(List.of(executable.toString(), "chat", "--v3", "--agent", agent,
                "--model", MODEL, "--no-interactive", "--output-format", "stream-json", "--require-mcp-startup"));
        if (resumeSessionId != null) {
            if (!SESSION_ID.matcher(resumeSessionId).matches()) {
                throw new IllegalArgumentException("invalid Kiro session id");
            }
            args.add("--resume-id");
            args.add(resumeSessionId);
        }
        args.add("--");
        args.add(message);

        Map<String, String> env = new LinkedHashMap<>();
        env.put("HOME", home);
        env.put("USER", user);
        env.put("LOGNAME", user);
        env.put("PATH", executable.getParent() + ":/usr/bin:/bin:/usr/sbin:/sbin");
        env.put("LANG", "en_US.UTF-8");
        for (String name : PASSED_THROUGH) {
            String value = parentEnvironment.get(name);
            if (value != null && !value.isBlank()) {
                env.put(name, value);
            }
        }
        env.put(TOKEN_VARIABLE, runToken);
        return new KiroCommand(List.copyOf(args), Map.copyOf(env));
    }

    public List<String> arguments() {
        return arguments;
    }

    public Map<String, String> environment() {
        return environment;
    }

    /** Never prints the run token. */
    @Override
    public String toString() {
        return String.join(" ", arguments.subList(0, arguments.size() - 1)) + " <message>";
    }
}
