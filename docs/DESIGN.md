# Design and limitations

## Why it works

Jenkins already separates two things an agent does:

| What | Jenkins API | Where it runs with this plugin |
|---|---|---|
| Java code sent by the controller: file operations (`FilePath`), `junit`, `stash`, `archiveArtifacts`, `readFile`, remote callables of plugins | remoting `Callable` | the proxy agent (Java 21+) |
| Native processes: Pipeline `sh`, freestyle *Execute shell*, tools launched by plugins | `Launcher` | the legacy host, over SSH |

The plugin keeps the first on the proxy and moves the second to the legacy host with a
`LauncherDecorator`, the extension point Jenkins offers to wrap every process started on a node.
A shared file system at the same path makes both sides see the same workspace, so the split
is invisible to the pipeline.

Pipeline `sh` fits this split well because of how durable-task works: the step is started
detached, writes its log and exit code into files under `workspace@tmp`, and the agent polls
those files. Nothing in that protocol needs Java on the machine that runs the script. This is
also why a build survives a restart of the proxy agent.

Abort: sshd makes the remote shell a session and process group leader. The plugin records its
pid per durable step (keyed by durable-task's cookie) and, when Jenkins kills the step, sends
`TERM` and then `KILL` to the whole process group on the legacy host.

## Alternatives considered

| Approach | Native? | Main problem |
|---|---|---|
| Keep Jenkins on an old version with Java 8 | yes | no security fixes, plugins stop updating |
| Java 8 agent: fork remoting and downgrade the controller's bytecode to Java 8 at class loading | most native, no shared file system | every Jenkins or plugin release can break it (class file versions, missing APIs, serialization of newer JDK classes); permanent maintenance |
| Pipeline library running commands over SSH | no: no node, only `sh` | file steps and the Jenkins UI do not see the legacy host |
| **This plugin** | node, labels, `sh`, file steps, abort, durability | needs a shared file system (see below) |

Where a supported Java exists, a normal agent is still better than this plugin:
AIX 7.2 TL5+ and 7.3 run IBM Semeru 21, and RHEL/CentOS 6 builds can usually move into a
`centos:6` container on a current Linux agent. This plugin is for hosts with no such option,
such as AIX 7.1, HP-UX and Solaris.

## Limitations

1. **Shared file system.** The workspace must be shared at the same path, with the same uid
   on both sides. This is the main setup cost.
   - Build I/O goes over NFS, which can make large compiles slower. A build script can
     work in a local directory on the legacy host and copy results back into the workspace.
   - durable-task detects a dead step by the log file's modification time; NFS attribute caching
     must be short (`actimeo`).
   - Custom workspaces (`ws`, `customWorkspace`) must be on the shared file system too.
2. **One proxy agent per legacy host.** Several proxy agents can run on one Linux machine.
3. **Java code still runs on the proxy.** Tool installers install Linux tools; plugins that run
   processes from Java code with `ProcessBuilder` instead of a `Launcher` run them on the proxy.
   Install build tools on the legacy host itself.
4. **Environment.** The proxy's `PATH`, `HOME`, `JAVA_HOME`, `LD_LIBRARY_PATH` and similar
   variables are not sent; the legacy host's login environment is used. `withEnv(['PATH+X=…'])`
   therefore does not change `PATH` on the legacy host yet.
5. **Abort covers Pipeline `sh`.** Killing the remote process group relies on durable-task's
   cookie. A freestyle *Execute shell* step that is aborted stops locally, but its remote
   processes are not killed yet. durable-task's binary wrapper must stay disabled.
6. **POSIX hosts only.** The legacy host needs `sshd` and a POSIX shell; Windows hosts are not
   supported.
7. **SSH.** The legacy host's key must be in the proxy's `known_hosts`. Keys with a passphrase
   are not supported yet. The private key is written under the agent root, outside the
   shared `workspace`.
8. **Verified so far** on Linux (CentOS 6.10 container, Jenkins 2.580.1); not yet on a real
   AIX host with NFS.
