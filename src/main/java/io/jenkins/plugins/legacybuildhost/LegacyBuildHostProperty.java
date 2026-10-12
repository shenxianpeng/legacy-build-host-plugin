package io.jenkins.plugins.legacybuildhost;

import com.cloudbees.jenkins.plugins.sshcredentials.SSHUserPrivateKey;
import com.cloudbees.plugins.credentials.CredentialsMatchers;
import com.cloudbees.plugins.credentials.CredentialsProvider;
import com.cloudbees.plugins.credentials.common.StandardListBoxModel;
import edu.umd.cs.findbugs.annotations.CheckForNull;
import edu.umd.cs.findbugs.annotations.NonNull;
import hudson.Extension;
import hudson.Util;
import hudson.model.Computer;
import hudson.model.Node;
import hudson.security.ACL;
import hudson.slaves.NodeProperty;
import hudson.slaves.NodePropertyDescriptor;
import hudson.util.FormValidation;
import hudson.util.ListBoxModel;
import java.util.Arrays;
import java.util.List;
import jenkins.model.Jenkins;
import org.jenkinsci.Symbol;
import org.kohsuke.stapler.DataBoundConstructor;
import org.kohsuke.stapler.DataBoundSetter;
import org.kohsuke.stapler.QueryParameter;
import org.kohsuke.stapler.verb.POST;

/**
 * Marks an agent as the proxy of a legacy build host. Every process Jenkins
 * starts on this agent runs on the legacy host over SSH instead, in the same
 * directory. The agent's root directory must be shared with the legacy host
 * at the same path (for example over NFS).
 */
public class LegacyBuildHostProperty extends NodeProperty<Node> {

    static final int DEFAULT_PORT = 22;
    static final String DEFAULT_LOCAL_COMMANDS = "git";

    private final String host;
    private int port = DEFAULT_PORT;
    private String credentialsId;
    private String sshOptions;
    private String localCommands = DEFAULT_LOCAL_COMMANDS;

    @DataBoundConstructor
    public LegacyBuildHostProperty(String host) {
        this.host = Util.fixEmptyAndTrim(host);
    }

    public String getHost() {
        return host;
    }

    public int getPort() {
        return port;
    }

    @DataBoundSetter
    public void setPort(int port) {
        this.port = port <= 0 ? DEFAULT_PORT : port;
    }

    @CheckForNull
    public String getCredentialsId() {
        return credentialsId;
    }

    /** An "SSH Username with private key" credential. Without it, the ssh configuration of the proxy agent is used. */
    @DataBoundSetter
    public void setCredentialsId(String credentialsId) {
        this.credentialsId = Util.fixEmptyAndTrim(credentialsId);
    }

    @CheckForNull
    public String getSshOptions() {
        return sshOptions;
    }

    /** Extra ssh options, separated by spaces. */
    @DataBoundSetter
    public void setSshOptions(String sshOptions) {
        this.sshOptions = Util.fixEmptyAndTrim(sshOptions);
    }

    @NonNull
    public String getLocalCommands() {
        return localCommands;
    }

    /** Commands that keep running on the proxy agent, separated by spaces, such as {@code git} for checkout. */
    @DataBoundSetter
    public void setLocalCommands(String localCommands) {
        this.localCommands = Util.fixNull(localCommands).trim();
    }

    @NonNull
    List<String> localCommandList() {
        return localCommands.isEmpty() ? List.of() : Arrays.asList(localCommands.split("\\s+"));
    }

    @NonNull
    List<String> sshOptionList() {
        return sshOptions == null ? List.of() : Arrays.asList(sshOptions.split("\\s+"));
    }

    @CheckForNull
    SSHUserPrivateKey lookupCredentials() {
        if (credentialsId == null) {
            return null;
        }
        return CredentialsMatchers.firstOrNull(
                CredentialsProvider.lookupCredentialsInItemGroup(
                        SSHUserPrivateKey.class, Jenkins.get(), ACL.SYSTEM2, List.of()),
                CredentialsMatchers.withId(credentialsId));
    }

    @Extension
    @Symbol("legacyBuildHost")
    public static class DescriptorImpl extends NodePropertyDescriptor {

        @NonNull
        @Override
        public String getDisplayName() {
            return Messages.LegacyBuildHostProperty_DisplayName();
        }

        @POST
        public FormValidation doCheckHost(@QueryParameter String value) {
            if (!Jenkins.get().hasPermission(Computer.CONFIGURE)) {
                return FormValidation.ok();
            }
            return Util.fixEmptyAndTrim(value) == null
                    ? FormValidation.error(Messages.LegacyBuildHostProperty_HostRequired())
                    : FormValidation.ok();
        }

        @POST
        public ListBoxModel doFillCredentialsIdItems(@QueryParameter String credentialsId) {
            StandardListBoxModel result = new StandardListBoxModel();
            if (!Jenkins.get().hasPermission(Computer.CONFIGURE)) {
                return result.includeCurrentValue(credentialsId);
            }
            return result.includeEmptyValue()
                    .includeMatchingAs(
                            ACL.SYSTEM2,
                            Jenkins.get(),
                            SSHUserPrivateKey.class,
                            List.of(),
                            CredentialsMatchers.always())
                    .includeCurrentValue(credentialsId);
        }
    }
}
