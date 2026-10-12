package io.jenkins.plugins.legacybuildhost;

import edu.umd.cs.findbugs.annotations.NonNull;
import hudson.Extension;
import hudson.FilePath;
import hudson.Launcher;
import hudson.model.Computer;
import hudson.model.Node;
import hudson.model.TaskListener;
import hudson.slaves.ComputerListener;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

/**
 * When a proxy agent comes online, checks that the legacy host is reachable
 * and sees the agent's workspace directory at the same path. The result goes
 * to the agent log, so a wrong NFS mount shows up before the first build.
 */
@Extension
public class LegacyBuildHostCheck extends ComputerListener {

    static final long TIMEOUT_SECONDS = 60;

    @Override
    public void onOnline(Computer c, TaskListener listener) throws IOException, InterruptedException {
        Node node = c.getNode();
        if (node == null || node.getNodeProperty(LegacyBuildHostProperty.class) == null) {
            return;
        }
        try {
            check(node, listener);
        } catch (IOException e) {
            // Report, but never keep the agent offline because of the check.
            e.printStackTrace(listener.error(Messages.LegacyBuildHostCheck_Error()));
        }
    }

    /** @return whether the check passed */
    static boolean check(@NonNull Node node, @NonNull TaskListener listener)
            throws IOException, InterruptedException {
        LegacyBuildHostProperty prop = node.getNodeProperty(LegacyBuildHostProperty.class);
        FilePath root = node.getRootPath();
        if (prop == null || root == null) {
            return false;
        }
        PrintStream log = listener.getLogger();
        FilePath workspaces = root.child("workspace");
        workspaces.mkdirs();
        String token = UUID.randomUUID().toString();
        FilePath marker = workspaces.child(".legacy-build-host-check-" + token);
        marker.write(token, StandardCharsets.UTF_8.name());
        try {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            Launcher launcher = node.createLauncher(listener);
            int rc = launcher.launch()
                    .cmds("sh", "-c", "uname -sr; cat \"$1\"", "check", marker.getRemote())
                    .stdout(out)
                    .stderr(log)
                    .quiet(true)
                    .start()
                    .joinWithTimeout(TIMEOUT_SECONDS, TimeUnit.SECONDS, listener);
            String output = out.toString(StandardCharsets.UTF_8);
            String system = output.lines().findFirst().orElse("?");
            if (rc == 0 && output.contains(token)) {
                log.println(Messages.LegacyBuildHostCheck_Ok(prop.getHost(), system, workspaces.getRemote()));
                return true;
            }
            log.println(Messages.LegacyBuildHostCheck_Failed(prop.getHost(), workspaces.getRemote(), rc));
            return false;
        } finally {
            marker.delete();
        }
    }
}
