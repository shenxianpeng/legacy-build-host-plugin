package io.jenkins.plugins.legacybuildhost;

import edu.umd.cs.findbugs.annotations.NonNull;
import hudson.Extension;
import hudson.Launcher;
import hudson.LauncherDecorator;
import hudson.Proc;
import hudson.model.Node;
import hudson.model.TaskListener;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Sends every process started on an agent with a {@link LegacyBuildHostProperty}
 * to the legacy host over SSH, except the commands configured to stay local.
 */
@Extension
public class LegacyLauncherDecorator extends LauncherDecorator {

    @NonNull
    @Override
    public Launcher decorate(@NonNull Launcher launcher, @NonNull Node node) {
        LegacyBuildHostProperty prop = node.getNodeProperty(LegacyBuildHostProperty.class);
        return prop == null ? launcher : new LegacyLauncher(launcher, node, prop);
    }

    static final class LegacyLauncher extends Launcher.DecoratedLauncher {

        private static final Logger LOGGER = Logger.getLogger(LegacyLauncher.class.getName());

        /** Seconds between TERM and KILL when a step is aborted. */
        static final int KILL_GRACE_SECONDS = 10;

        private final Node node;
        private final LegacyBuildHostProperty prop;

        LegacyLauncher(Launcher inner, Node node, LegacyBuildHostProperty prop) {
            super(inner);
            this.node = node;
            this.prop = prop;
        }

        @Override
        public Proc launch(@NonNull ProcStarter starter) throws IOException {
            List<String> cmds = starter.cmds();
            if (cmds.isEmpty() || prop.localCommandList().contains(baseName(cmds.get(0)))) {
                return getInner().launch(starter);
            }
            String remote = RemoteCommand.build(
                    cmds, starter.envs(), starter.pwd() == null ? null : starter.pwd().getRemote());
            List<String> ssh = new ArrayList<>(ssh());
            ssh.add(remote);
            LOGGER.log(Level.FINE, "{0}: {1}", new Object[] {prop.getHost(), remote});

            // Mask the remote command: it can carry build variables, and ssh options are not interesting.
            boolean[] masks = new boolean[ssh.size()];
            masks[ssh.size() - 1] = true;
            starter.cmds(ssh).masks(masks).envs(new String[0]);
            return getInner().launch(starter);
        }

        @Override
        public void kill(Map<String, String> modelEnvVars) throws IOException, InterruptedException {
            String script = RemoteCommand.kill(modelEnvVars.get("JENKINS_SERVER_COOKIE"), KILL_GRACE_SECONDS);
            if (script != null) {
                List<String> ssh = new ArrayList<>(ssh());
                ssh.add(script);
                int rc = getInner()
                        .launch()
                        .cmds(ssh)
                        .quiet(true)
                        .stdout(TaskListener.NULL)
                        .join();
                LOGGER.log(Level.FINE, "killed step on {0}: rc={1}", new Object[] {prop.getHost(), rc});
            }
            getInner().kill(modelEnvVars);
        }

        private List<String> ssh() throws IOException {
            try {
                return SshCommand.prefix(prop, node);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IOException(e);
            }
        }

        private static String baseName(String path) {
            int slash = Math.max(path.lastIndexOf('/'), path.lastIndexOf('\\'));
            return slash < 0 ? path : path.substring(slash + 1);
        }
    }
}
