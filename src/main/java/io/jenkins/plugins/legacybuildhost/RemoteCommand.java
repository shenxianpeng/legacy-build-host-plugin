package io.jenkins.plugins.legacybuildhost;

import edu.umd.cs.findbugs.annotations.CheckForNull;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Builds the shell command that runs on the legacy host. It recreates the
 * working directory and the build environment of the original launch, and
 * records the process group so that an abort can kill the whole build.
 */
final class RemoteCommand {

    /** Where the remote side records the process group of each durable step, per ssh user. */
    static final String PID_DIR = "\"${HOME:-/tmp}/.legacy-build-host\"";

    /** durable-task puts its cookie in the wrapper script: {@code jsc=durable-...;}. */
    private static final Pattern DURABLE_COOKIE = Pattern.compile("jsc=([A-Za-z0-9_-]+);");

    private static final Pattern COOKIE_VALUE = Pattern.compile("[A-Za-z0-9_-]+");

    private static final Pattern ENV_NAME = Pattern.compile("[A-Za-z_][A-Za-z0-9_]*");

    /** Variables of the proxy agent that would be wrong on the legacy host. */
    static final Set<String> SKIPPED_ENV = Set.of(
            "PATH",
            "HOME",
            "SHELL",
            "USER",
            "LOGNAME",
            "PWD",
            "OLDPWD",
            "TMPDIR",
            "JAVA_HOME",
            "LD_LIBRARY_PATH",
            "LIBPATH",
            "HOSTNAME",
            "TERM",
            "SHLVL",
            "_");

    private RemoteCommand() {}

    /**
     * @param cmds the command Jenkins wanted to start
     * @param envs the environment overrides of the launch, as {@code NAME=value}
     * @param pwd the working directory, or {@code null}
     */
    @NonNull
    static String build(@NonNull List<String> cmds, @CheckForNull String[] envs, @CheckForNull String pwd) {
        StringBuilder remote = new StringBuilder();
        if (pwd != null) {
            remote.append("cd ").append(quote(pwd)).append(" || exit 1; ");
        }
        if (envs != null) {
            for (String kv : envs) {
                int eq = kv.indexOf('=');
                if (eq <= 0) {
                    continue;
                }
                String name = kv.substring(0, eq);
                if (SKIPPED_ENV.contains(name) || !ENV_NAME.matcher(name).matches()) {
                    continue;
                }
                remote.append("export ")
                        .append(name)
                        .append('=')
                        .append(quote(kv.substring(eq + 1)))
                        .append("; ");
            }
        }
        String cookie = findCookie(cmds);
        if (cookie != null) {
            // sshd makes the remote shell a session and process group leader,
            // so $$ is the process group of everything this step starts.
            // Records of finished steps are dropped on the way.
            remote.append("mkdir -p ")
                    .append(PID_DIR)
                    .append(" && for f in ")
                    .append(PID_DIR)
                    .append("/durable-*; do [ -f \"$f\" ] && { kill -0 -$(cat \"$f\") 2>/dev/null || rm -f \"$f\"; }; done; ")
                    .append("echo $$ > ")
                    .append(PID_DIR)
                    .append('/')
                    .append(cookie)
                    .append("; ");
        }
        remote.append("exec");
        for (String c : cmds) {
            remote.append(' ').append(quote(c));
        }
        return remote.toString();
    }

    /** The command that kills the process group recorded for a durable step. */
    @CheckForNull
    static String kill(@CheckForNull String cookie, int graceSeconds) {
        if (cookie == null || !COOKIE_VALUE.matcher(cookie).matches()) {
            return null;
        }
        String f = PID_DIR + "/" + cookie;
        return "p=$(cat " + f + " 2>/dev/null) || exit 0; "
                + "kill -TERM -$p 2>/dev/null; i=0; "
                + "while [ $i -lt " + graceSeconds + " ] && kill -0 -$p 2>/dev/null; do sleep 1; i=$((i+1)); done; "
                + "kill -KILL -$p 2>/dev/null; rm -f " + f + "; exit 0";
    }

    @CheckForNull
    static String findCookie(@NonNull List<String> cmds) {
        for (String c : cmds) {
            Matcher m = DURABLE_COOKIE.matcher(c);
            if (m.find()) {
                return m.group(1);
            }
        }
        return null;
    }

    /** Quotes a string for a POSIX shell. */
    @NonNull
    static String quote(@NonNull String s) {
        return "'" + s.replace("'", "'\\''") + "'";
    }
}
