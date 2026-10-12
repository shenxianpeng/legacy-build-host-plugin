package io.jenkins.plugins.legacybuildhost;

import com.cloudbees.jenkins.plugins.sshcredentials.SSHUserPrivateKey;
import edu.umd.cs.findbugs.annotations.NonNull;
import hudson.FilePath;
import hudson.Util;
import hudson.model.Node;
import hudson.util.Secret;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import jenkins.util.SystemProperties;

/** The ssh command line, up to and including the target, that runs on the proxy agent. */
final class SshCommand {

    /** The ssh client on the proxy agent. Tests replace it with a fake. */
    static String executable() {
        return SystemProperties.getString(SshCommand.class.getName() + ".executable", "ssh");
    }

    /** Directory under the agent root that holds private keys of credentials. It must not be shared. */
    static final String KEY_DIR = "legacy-build-host";

    private SshCommand() {}

    @NonNull
    static List<String> prefix(@NonNull LegacyBuildHostProperty prop, @NonNull Node node)
            throws IOException, InterruptedException {
        if (prop.getHost() == null) {
            throw new IOException(Messages.LegacyBuildHostProperty_HostRequired());
        }
        List<String> cmd = new ArrayList<>(List.of(
                executable(),
                "-o",
                "BatchMode=yes",
                "-o",
                "ServerAliveInterval=30",
                "-o",
                "ServerAliveCountMax=4",
                "-p",
                Integer.toString(prop.getPort())));
        String user = null;
        if (prop.getCredentialsId() != null) {
            SSHUserPrivateKey cred = prop.lookupCredentials();
            if (cred == null) {
                throw new IOException(Messages.SshCommand_CredentialsNotFound(prop.getCredentialsId()));
            }
            if (!Secret.toString(cred.getPassphrase()).isEmpty()) {
                throw new IOException(Messages.SshCommand_PassphraseNotSupported(prop.getCredentialsId()));
            }
            user = Util.fixEmptyAndTrim(cred.getUsername());
            FilePath key = writeKey(node, cred);
            cmd.addAll(List.of("-i", key.getRemote(), "-o", "IdentitiesOnly=yes"));
        }
        cmd.addAll(prop.sshOptionList());
        cmd.add(user == null ? prop.getHost() : user + "@" + prop.getHost());
        return cmd;
    }

    /** Puts the private key on the proxy agent, readable only by the agent user, and only when it changed. */
    private static FilePath writeKey(Node node, SSHUserPrivateKey cred) throws IOException, InterruptedException {
        FilePath root = node.getRootPath();
        if (root == null) {
            throw new IOException(Messages.SshCommand_AgentOffline(node.getNodeName()));
        }
        List<String> keys = cred.getPrivateKeys();
        if (keys.isEmpty()) {
            throw new IOException(Messages.SshCommand_NoPrivateKey(cred.getId()));
        }
        String text = keys.get(0).trim() + "\n";
        FilePath dir = root.child(KEY_DIR);
        dir.mkdirs();
        dir.chmod(0700);
        FilePath key = dir.child(Util.getDigestOf(cred.getId()) + ".key");
        if (!key.exists() || !Util.getDigestOf(text).equals(key.digest())) {
            key.write(text, StandardCharsets.UTF_8.name());
            key.chmod(0600);
        }
        return key;
    }
}
