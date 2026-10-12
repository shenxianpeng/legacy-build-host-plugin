# Testing on real Linux and AIX hosts

## 1. Build and install

```bash
mvn -B verify                       # target/legacy-build-host.hpi
```

Install it under *Manage Jenkins → Plugins → Advanced → Deploy Plugin*. The plugin needs Jenkins 2.568.3 or newer.

## 2. Prepare the legacy host

1. Create the build user with the same uid as the agent user on the proxy:
   `id jenkins` on the proxy, then `mkuser id=<uid> jenkins` (AIX) or `useradd -u <uid> jenkins` (Linux).
2. Export the proxy's workspace over NFS and mount it **at the same path** on the legacy host, for example:

   ```bash
   # proxy (/etc/exports)
   /home/jenkins/agent/workspace  aix71(rw,sync,no_subtree_check)
   # AIX 7.1
   mkdir -p /home/jenkins/agent/workspace
   mount -o vers=3 proxy:/home/jenkins/agent/workspace /home/jenkins/agent/workspace
   ```

   Keep the NFS attribute cache short (`actimeo=1` on Linux clients, `actimeo=1` / `noac` on AIX if needed): durable-task watches the log file's modification time.
3. Put the proxy agent user's public key, or the key of the Jenkins credential, in `~jenkins/.ssh/authorized_keys` on the legacy host.

## 3. Prepare the proxy agent

1. A Linux agent with Java 21+, connected to Jenkins as usual, label `legacy`.
2. `ssh-keyscan aix71 >> ~/.ssh/known_hosts` as the agent user.
3. In the agent configuration, enable **Run builds on a legacy host over SSH**, set the host and credentials, save, and reconnect the agent.
4. The agent log must show:
   `Legacy build host aix71 (AIX 1) is reachable and shares /home/jenkins/agent/workspace.`

## 4. Run the test pipelines

Create one Pipeline job per file in [test-pipelines](test-pipelines):

| Pipeline | Expected |
|---|---|
| `build.groovy` | SUCCESS. `uname -a` shows the legacy host, `java -version` fails, the test report has 1 test, the artifact and the stash contain `built 1.2.3 on AIX …` |
| `abort.groovy` | Abort it after `started`. ABORTED within ~10 s, and `ps -ef \| grep 'sleep 60[01]'` on the legacy host finds nothing |
| `durable.groovy` | While it sleeps, stop the proxy agent, start it again. SUCCESS with `survived agent restart …` |

Things to look at on AIX in particular:

- Abort: AIX `ksh` must accept `kill -TERM -<pgid>`. If processes survive, run on the legacy host as the build user:
  `sh -c 'sleep 300 & echo $$; wait' &` then `kill -TERM -<printed pid>` and check `ps`.
- `~/.legacy-build-host/` of the build user holds one small file per running step and is cleaned up automatically.
