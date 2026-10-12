# Legacy Build Host

Run Jenkins builds on hosts that cannot run a Jenkins agent anymore — AIX 7.1, RHEL/CentOS 6, HP-UX, Solaris — without installing Java on them.

Jenkins requires Java 21 or newer on the controller **and on every agent**. Many enterprise build hosts cannot run it, so they get stuck on old, unsupported Jenkins versions. With this plugin, such a host keeps building on current Jenkins as an ordinary node:

```groovy
node('aix71') {
    checkout scm                  // git stays on the proxy agent
    sh 'make all && make test'    // runs on AIX 7.1
    junit '**/TEST-*.xml'         // reads the shared workspace
    archiveArtifacts 'dist/**'
}
```

## How it works

```
Jenkins controller (Java 21+)
        │ remoting
proxy agent "aix71" (Linux, Java 21+) ── shared workspace, same path (NFS) ── legacy host (sshd + sh, no Java)
        │                                                                          ▲
        └── every process Jenkins starts on this agent ─────────── ssh ───────────┘
```

1. A normal Linux agent acts as the proxy of one legacy host. In Jenkins it is a regular node with labels, executors and a workspace.
2. The proxy's `workspace` directory is shared with the legacy host **at the same path**, for example over NFS.
3. The plugin decorates the agent's launcher: every process Jenkins starts there — including Pipeline `sh` steps — runs on the legacy host over SSH, in the same directory and with the build's environment variables. File operations (`junit`, `archiveArtifacts`, `stash`, `readFile` …) keep running on the proxy and see the same files.

Pipeline `sh` keeps its durable behaviour: the step runs detached on the legacy host and writes its log and exit code into the shared workspace, so a build survives a restart of the proxy agent. When a build is aborted, the plugin kills the step's whole process group on the legacy host.

## Setup

On the legacy host:

- `sshd`, a POSIX shell, and your build tools. No Java.
- A build user with the **same uid** as the agent user on the proxy.
- Mount the proxy's workspace directory at the same path, for example `/home/jenkins/agent/workspace`. Share only `workspace`, not the whole agent root: the plugin keeps the SSH private key under `legacy-build-host/` in the agent root.

On the proxy agent:

- A Linux agent with Java 21+ and an `ssh` client.
- The legacy host's key in the agent user's `known_hosts` (`ssh-keyscan legacy-host >> ~/.ssh/known_hosts`).

In Jenkins, configure the agent and enable **Run builds on a legacy host over SSH** under *Node Properties*:

| Field | Meaning |
|---|---|
| Legacy host | Host name of the legacy build host |
| SSH port | Default 22 |
| Credentials | *SSH Username with private key*. Empty: use the agent user's SSH configuration |
| Extra ssh options | Advanced, for example `-o ConnectTimeout=10` |
| Commands that stay on this agent | Advanced, default `git` |

When the agent comes online, its log shows whether the legacy host is reachable and sees the shared workspace:

```
Legacy build host aix71.example.com (AIX 1) is reachable and shares /home/jenkins/agent/workspace.
```

Configuration as Code:

```yaml
jenkins:
  nodes:
    - permanent:
        name: aix71
        labelString: aix71
        remoteFS: /home/jenkins/agent
        launcher: inbound
        nodeProperties:
          - legacyBuildHost:
              host: aix71.example.com
              credentialsId: aix71-ssh
```

## Limitations

- Anything that runs Java code on the agent still runs on the proxy, for example tool installers. Install build tools on the legacy host itself.
- Custom workspaces (`ws`, `customWorkspace`) must also be on the shared file system.
- Private keys with a passphrase are not supported yet.
- Do not enable durable-task's binary wrapper (`org.jenkinsci.plugins.durabletask.BourneShellScript.USE_BINARY_WRAPPER`): it starts a Linux binary, which cannot run on the legacy host.

## Testing

See [docs/TESTING.md](docs/TESTING.md) for testing on real Linux and AIX hosts.

## License

[MIT](LICENSE.md)
