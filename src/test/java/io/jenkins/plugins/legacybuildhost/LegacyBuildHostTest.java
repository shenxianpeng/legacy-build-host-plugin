package io.jenkins.plugins.legacybuildhost;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.not;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.cloudbees.jenkins.plugins.sshcredentials.impl.BasicSSHUserPrivateKey;
import com.cloudbees.plugins.credentials.CredentialsScope;
import com.cloudbees.plugins.credentials.SystemCredentialsProvider;
import hudson.model.Label;
import hudson.model.Result;
import hudson.slaves.DumbSlave;
import hudson.util.StreamTaskListener;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.TimeUnit;
import org.jenkinsci.plugins.workflow.cps.CpsFlowDefinition;
import org.jenkinsci.plugins.workflow.job.WorkflowJob;
import org.jenkinsci.plugins.workflow.job.WorkflowRun;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.DisabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.io.TempDir;
import org.jvnet.hudson.test.JenkinsRule;
import org.jvnet.hudson.test.junit.jupiter.WithJenkins;

/**
 * Runs real Pipeline builds on an agent whose "legacy host" is a fake ssh
 * client: it records its arguments and runs the remote command locally in a
 * new session, the way sshd does. The workspace is trivially shared.
 */
@WithJenkins
@DisabledOnOs(OS.WINDOWS)
class LegacyBuildHostTest {

    private static final String FAKE_SSH = """
            #!/bin/sh
            # Fake ssh: parse the options like ssh, then run the remote command
            # (the last argument) locally as the leader of a new session.
            key=; target=
            while [ $# -gt 1 ]; do
              case "$1" in
                -i) key=$2; shift 2 ;;
                -o|-p) shift 2 ;;
                *) target=$1; shift ;;
              esac
            done
            printf '%s\\n' "$1" >> "$(dirname "$0")/invocations.log"
            LEGACY_FAKE_SSH=1 LEGACY_FAKE_TARGET=$target
            export LEGACY_FAKE_SSH LEGACY_FAKE_TARGET
            if [ -n "$key" ]; then
              LEGACY_FAKE_KEY=$(cat "$key") LEGACY_FAKE_KEY_MODE=$(stat -c %a "$key")
              export LEGACY_FAKE_KEY LEGACY_FAKE_KEY_MODE
            fi
            exec setsid -w sh -c "$1"
            """;

    @TempDir
    Path fakeDir;

    private JenkinsRule j;
    private DumbSlave agent;
    private LegacyBuildHostProperty prop;

    @BeforeEach
    void setUp(JenkinsRule j) throws Exception {
        this.j = j;
        Path ssh = fakeDir.resolve("ssh");
        Files.writeString(ssh, FAKE_SSH);
        assertTrue(ssh.toFile().setExecutable(true));
        System.setProperty(SshCommand.class.getName() + ".executable", ssh.toString());

        agent = j.createOnlineSlave(Label.get("legacy"));
        prop = new LegacyBuildHostProperty("legacy.example.com");
        agent.getNodeProperties().add(prop);
    }

    @AfterEach
    void tearDown() {
        System.clearProperty(SshCommand.class.getName() + ".executable");
    }

    private WorkflowRun run(String script) throws Exception {
        WorkflowJob p = j.createProject(WorkflowJob.class);
        p.setDefinition(new CpsFlowDefinition(script, true));
        return p.scheduleBuild2(0).waitForStart();
    }

    private String invocations() throws Exception {
        File log = fakeDir.resolve("invocations.log").toFile();
        return log.exists() ? Files.readString(log.toPath()) : "";
    }

    @Test
    void shRunsThroughSsh() throws Exception {
        WorkflowRun b = j.waitForCompletion(run(
                """
                node('legacy') {
                  withEnv(["GREETING=it's ok"]) {
                    sh 'echo "fake=$LEGACY_FAKE_SSH target=$LEGACY_FAKE_TARGET greeting=$GREETING"'
                  }
                  echo "rc=${sh(script: 'exit 3', returnStatus: true)}"
                  echo "out=${sh(script: 'echo remote-out', returnStdout: true).trim()}"
                  writeFile file: 'shared.txt', text: 'written by the agent'
                  sh 'cat shared.txt'
                }
                """));
        j.assertBuildStatusSuccess(b);
        j.assertLogContains("fake=1 target=legacy.example.com greeting=it's ok", b);
        j.assertLogContains("rc=3", b);
        j.assertLogContains("out=remote-out", b);
        j.assertLogContains("written by the agent", b);
    }

    @Test
    void failingCommandFailsTheBuild() throws Exception {
        WorkflowRun b = j.waitForCompletion(run("node('legacy') { sh 'echo before; false; echo after' }"));
        j.assertBuildStatus(Result.FAILURE, b);
        j.assertLogContains("before", b);
        j.assertLogNotContains("after", b);
    }

    @Test
    void abortKillsTheRemoteProcessGroup() throws Exception {
        WorkflowRun b = run("node('legacy') { sh 'echo started; sleep 1234 & sleep 1235' }");
        j.waitForMessage("started", b);
        Thread.sleep(1000);
        b.doStop();
        j.assertBuildStatus(Result.ABORTED, j.waitForCompletion(b));
        assertThat(invocations(), containsString("kill -TERM -$p"));
        long deadline = System.currentTimeMillis() + TimeUnit.SECONDS.toMillis(30);
        while (sleepersAlive() && System.currentTimeMillis() < deadline) {
            Thread.sleep(500);
        }
        assertThat("remote processes were killed", sleepersAlive(), is(false));
    }

    private static boolean sleepersAlive() throws Exception {
        return new ProcessBuilder("pgrep", "-f", "sleep 123[45]").start().waitFor() == 0;
    }

    @Test
    void usesSshCredentials() throws Exception {
        SystemCredentialsProvider.getInstance()
                .getCredentials()
                .add(new BasicSSHUserPrivateKey(
                        CredentialsScope.SYSTEM,
                        "legacy-key",
                        "builder",
                        new BasicSSHUserPrivateKey.DirectEntryPrivateKeySource("dummy-private-key"),
                        null,
                        "legacy host key"));
        SystemCredentialsProvider.getInstance().save();
        prop.setCredentialsId("legacy-key");

        WorkflowRun b = j.waitForCompletion(run(
                "node('legacy') { sh 'echo \"target=$LEGACY_FAKE_TARGET key=$LEGACY_FAKE_KEY mode=$LEGACY_FAKE_KEY_MODE\"' }"));
        j.assertBuildStatusSuccess(b);
        j.assertLogContains("target=builder@legacy.example.com key=dummy-private-key mode=600", b);
    }

    @Test
    void missingCredentialsFailTheStep() throws Exception {
        prop.setCredentialsId("does-not-exist");
        WorkflowRun b = j.waitForCompletion(run("node('legacy') { sh 'true' }"));
        j.assertBuildStatus(Result.FAILURE, b);
        j.assertLogContains("does-not-exist", b);
    }

    @Test
    void localCommandsStayOnTheAgent() throws Exception {
        assertThat(launch("printenv LEGACY_FAKE_SSH || true"), containsString("1"));
        prop.setLocalCommands("sh");
        assertThat(launch("printenv LEGACY_FAKE_SSH || true"), not(containsString("1")));
    }

    private String launch(String script) throws Exception {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        agent.createLauncher(StreamTaskListener.fromStdout())
                .launch()
                .cmds("sh", "-c", script)
                .stdout(out)
                .join();
        return out.toString(StandardCharsets.UTF_8);
    }

    @Test
    void checkFindsSharedWorkspace() throws Exception {
        assertTrue(LegacyBuildHostCheck.check(agent, StreamTaskListener.fromStdout()));
    }

    @Test
    void configRoundtrip() throws Exception {
        prop.setPort(2222);
        prop.setSshOptions("-o ConnectTimeout=10");
        prop.setLocalCommands("git hg");
        agent = j.configRoundtrip(agent);
        j.assertEqualDataBoundBeans(prop, agent.getNodeProperty(LegacyBuildHostProperty.class));
    }
}
