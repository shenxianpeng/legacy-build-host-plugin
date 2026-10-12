package io.jenkins.plugins.legacybuildhost;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.nullValue;
import static org.hamcrest.Matchers.startsWith;

import java.util.List;
import org.junit.jupiter.api.Test;

class RemoteCommandTest {

    private static final String DURABLE_WRAPPER = "{ while [ -d '/ws@tmp/durable-1' ]; do sleep 3; done } & "
            + "jsc=durable-0123abcd; JENKINS_SERVER_COOKIE=$jsc 'sh' -xe '/ws@tmp/durable-1/script.sh'";

    @Test
    void quotesForPosixShell() {
        assertThat(RemoteCommand.quote("it's"), is("'it'\\''s'"));
        assertThat(RemoteCommand.quote("a b $HOME"), is("'a b $HOME'"));
    }

    @Test
    void changesDirectoryThenExecs() {
        String cmd = RemoteCommand.build(List.of("make", "all"), null, "/srv/ws/my job");
        assertThat(cmd, is("cd '/srv/ws/my job' || exit 1; exec 'make' 'all'"));
    }

    @Test
    void exportsBuildVariablesButNotProxyOnes() {
        String cmd = RemoteCommand.build(
                List.of("true"),
                new String[] {
                    "BUILD_NUMBER=7", "GREETING=it's ok", "PATH=/usr/lib/jvm/bin:/usr/bin", "JAVA_HOME=/opt/jdk",
                    "PATH+MAVEN=/opt/maven", "=broken"
                },
                null);
        assertThat(cmd, containsString("export BUILD_NUMBER='7'; "));
        assertThat(cmd, containsString("export GREETING='it'\\''s ok'; "));
        assertThat(cmd, not(containsString("PATH")));
        assertThat(cmd, not(containsString("JAVA_HOME")));
        assertThat(cmd, not(containsString("broken")));
    }

    @Test
    void recordsProcessGroupOfDurableSteps() {
        List<String> cmds = List.of("sh", "-c", "(" + DURABLE_WRAPPER + ") >&- 2>&- &");
        assertThat(RemoteCommand.findCookie(cmds), is("durable-0123abcd"));
        String cmd = RemoteCommand.build(cmds, null, "/ws");
        assertThat(cmd, containsString("echo $$ > " + RemoteCommand.PID_DIR + "/durable-0123abcd; exec 'sh' '-c' "));
    }

    @Test
    void doesNotRecordOtherCommands() {
        assertThat(RemoteCommand.findCookie(List.of("uname", "-a")), nullValue());
        assertThat(RemoteCommand.build(List.of("uname", "-a"), null, null), is("exec 'uname' '-a'"));
    }

    @Test
    void killsTheRecordedProcessGroup() {
        String kill = RemoteCommand.kill("durable-0123abcd", 10);
        assertThat(kill, startsWith("p=$(cat " + RemoteCommand.PID_DIR + "/durable-0123abcd 2>/dev/null) || exit 0; "));
        assertThat(kill, containsString("kill -TERM -$p"));
        assertThat(kill, containsString("kill -KILL -$p"));
    }

    @Test
    void refusesCookiesThatCouldInjectCommands() {
        assertThat(RemoteCommand.kill(null, 10), nullValue());
        assertThat(RemoteCommand.kill("x; rm -rf /", 10), nullValue());
    }
}
