# Bashkit4j

### Give any script a safe, disposable computer — without giving up your own.

**Bashkit4j lets your Java app run arbitrary, even untrusted, bash scripts inside a
self-contained sandbox.** No real bash, no host filesystem, no host processes —
just a virtual terminal that exists only in memory, from one line of Java.

```java
try (Bash bash = Bash.builder().build()) {
    ExecResult r = bash.exec("echo hello world");
    System.out.println(r.stdout()); // "hello world\n"
}
```

The real OS never sees the script. Nothing leaks out. Nothing can be deleted,
read, or reached that you didn't put in the box yourself.

---

## Quick start

### Clone and run the demo

```bash
git clone https://github.com/tersePrompts/bashkit4j.git
cd bashkit4j
mvn -q compile exec:java
```

You'll see sandboxed `echo`, `whoami`, `sort | tr`, arithmetic, and file reads —
all inside the virtual environment.

### Add it to your project

```xml
<dependency>
    <groupId>io.github.terseprompts</groupId>
    <artifactId>bashkit4j</artifactId>
    <version>0.1.0-SNAPSHOT</version>
</dependency>
```

> Not on Maven Central yet (roadmap M4) — build from source or add the jar directly
> to your classpath until it is published.

### Your first sandboxed script

```java
import io.github.terseprompts.Bash;
import io.github.terseprompts.ExecResult;

try (Bash bash = Bash.builder()      // one sandbox
        .username("agent")
        .file("/notes.txt", "hi")
        .build()) {

    ExecResult r = bash.exec("echo hello && cat /notes.txt");
    System.out.println(r.stdout()); // hello\nhi
}
```

Java 17+. The native library for your OS is bundled — nothing to configure.

---

## Who this is for

- **AI/LLM engineers** — give a coding agent a real "terminal" to operate
  (`ls`, `cat`, `grep`, `tar`, `jq`, ...) without letting it touch the
  machine running it.
- **SaaS and multi-tenant platforms** — run user-supplied scripts or
  "serverless functions" in complete isolation from one tenant to the next.
- **Security-conscious teams** — anything that used to shell out to `ProcessBuilder`
  or `Runtime.exec()` now gets a sandbox for free.
- **CI / automation tooling** — replace flaky shell-in-Docker setups with an
  in-process, dependency-free shell that starts in milliseconds.

---

## The problem

Virtually every app needs to run bash. But the standard answer has a fatal flaw:

> **Before:** every bash command = a new OS process. One rogue agent = node down.
> **After:** zero OS processes. Zero blast radius.

- `ProcessBuilder` / `Runtime.exec()` give the script **full access to the host** —
  real files, real processes, real secrets.
- One `rm -rf /`, one `curl | sh`, one escaped `cat /etc/passwd` and your machine —
  or worse, your customers' data — is gone.
- `--privileged` Docker containers, VM pools, and sanitizer hacks are heavy, slow,
  and still not airtight.

Running **untrusted code** with **trusted permissions** is a bet you'll eventually lose.

## The solution

Bashkit4j flips that model: **the script gets a computer that doesn't exist.**

- **In-process virtual shell** — a full POSIX-ish bash (164 built-in commands:
  `grep`, `sed`, `awk`, `jq`, `find`, `curl`... even `tar`) re-implemented in Rust,
  compiled as a tiny native library for Windows, Linux, and macOS. No `fork`/`exec`,
  no real bash binary, no Docker.
- **In-memory virtual filesystem** — scripts freely `mkdir`, `cd`, `cat`, `sort`,
  `mktemp` — it all lives in a sandbox the host can't see, and the host is a
  filesystem the sandbox can't see.
- **Network deny-by-default** — `curl`/`wget` fail unless you explicitly allow them.
- **Resource limits** — cap command count, timeout, input/output size.
- **One `Bash` = one tenant** — every instance is fully isolated; nothing is shared.

> Hand a model, a user, or a stranger a terminal — without handing over the machine.

---

## Why teams choose Bashkit4j

| | `ProcessBuilder` / Docker | **Bashkit4j** |
|---|---|---|
| Real bash on the host | ✅ yes — risk | **❌ no — impossible** |
| Host filesystem visible | ✅ yes — risk | **❌ no — invisible** |
| Spawns OS processes | ✅ yes | **❌ no — in-process only** |
| Network by default | ✅ yes — risk | **❌ deny by default** |
| Startup cost | seconds (container) | **milliseconds (in-process)** |
| Dependency footprint | bash image / VM | **single native lib** |
| Multi-tenant isolation | manual/Luck | **built-in, per instance** |

## Proof, not promises

Bashkit4j ships with **40 passing tests** (`mvn test`) that verify real behavior
against the actual native library — including sandbox escape attempts:

| Area | Verified behavior |
|---|---|
| Variables / expansion | `$X`, `${VAR:-default}`, `${X^^}`, `${#VAR}` |
| Arithmetic / substitution | `$((3+4))`, `$(cmd)` |
| Pipelines / redirection | `|`, `>`, heredocs (`<<EOF`) |
| Control flow | `for`, `if/elif/else`, `case`, functions |
| Arrays | indexed arrays `${a[@]}`, `${#a[@]}` |
| Text tools | `wc`, `head`, `rev`, `tr`, `cut` |
| File tools | `mkdir -p`, `touch`, `mv`, `rm -r`, `test -f` |
| Archives | `tar -cf` / `tar -xf` round-trip |
| Data tools | `jq -r`, `bc`, `expr`, `cut -c` |
| Checksums / enc | `base64`, `md5sum`, `sha256sum` |
| Binary VFS | arbitrary `byte[]` (incl. `0xff`) round-trip |
| Virtual identity | `whoami`, `hostname`, `id`, `$USER` |
| Stateful session | vars/files persist across calls on one instance |
| Multi-tenant isolation | separate instances share nothing |
| **Sandbox** | **host paths invisible; no `..` escape; network denied** |
| Limits | `maxCommands` enforced |

### What the sandbox actually does (measured, not claimed)

| Probe | Result |
|---|---|
| `ls /` | Only the virtual `dev home tmp` |
| `test -e /etc/passwd` | `no-passwd` — host files unreachable |
| `test -f C:/Windows/win.ini` | `no` — host (Windows) paths unreachable |
| `ls / ../..` | Same listing — `..` cannot escape |
| `curl https://example.com` | `network access not configured` |
| `cat /etc/hostname` | `file not found` — no host processes/files |
| `id` | `uid=1000(sandbox)` — a virtual identity, not your OS user |
| Two instances, different env | Each sees only its own variables |

---

## Get started in 60 seconds

```bash
git clone https://github.com/tersePrompts/bashkit4j.git
cd bashkit4j
mvn -q compile exec:java
```

Sample output:

```
bashkit version : 0.17.1
abi version     : 1
echo            : stdout=hello bashkit\n exit=0
whoami/hostname : stdout=agent\nsandbox\n exit=0
sort|tr         : stdout=HELLO\nWORLD\n exit=0
arithmetic      : 42
readFile        : hello\nworld\n
OK
```

### The API in three lines

```java
import io.github.terseprompts.Bash;
import io.github.terseprompts.BashkitRuntime;
import io.github.terseprompts.ExecResult;

BashkitRuntime.library(); // loads the native lib, guards the ABI version

try (Bash bash = Bash.builder()
        .username("agent")
        .hostname("sandbox")
        .env("PROJECT", "bashkit")
        .file("/notes.txt", "hello\nworld\n")
        .build()) {

    ExecResult r = bash.exec("sort /notes.txt | tr a-z A-Z");
    System.out.println(r.stdout()); // HELLO\nWORLD\n
    System.out.println(r.exitCode()); // 0

    // Non-zero exit is a normal result (not an exception):
    ExecResult fail = bash.exec("exit 7");
    System.out.println(fail.exitCode()); // 7

    // execOrThrow throws BashException when the script exits non-zero:
    try {
        bash.execOrThrow("exit 1");
    } catch (BashException e) {
        System.out.println(e.status);
    }

    // Direct virtual-FS access:
    bash.writeFile("/data/config.json", "{\"debug\":true}");
    String cfg = bash.readFile("/data/config.json");
}
// 'close()' releases the native instance deterministically.
```

---

## Requirements & platforms

Just **Java 17+** (and Maven 3.6+ to build). The native library is **bundled with
every build and auto-detects your OS** — zero configuration:

| Platform | Library |
|---|---|
| Windows x86-64 | `bashkit.dll` |
| Linux x86-64 | `libbashkit.so` |
| Linux ARM64 | `libbashkit.so` |
| macOS x86-64 | `libbashkit.dylib` |
| macOS ARM64 | `libbashkit.dylib` |

If the bundled library isn't on disk, the loader extracts it from the jar's
classpath automatically. Resolution order: system property `-Dbashkit.native.path` →
env var `BASHKIT_NATIVE_PATH` → bundled auto-detect → platform `java.library.path`.

---

## API at a glance

| Member | What it does |
|---|---|
| `Bash.builder().build()` | Start an isolated virtual shell with your identity, env, files, limits |
| `bash.exec(script)` | Run a script → `ExecResult` (stdout, stderr, exit code, truncation flags) |
| `bash.execOrThrow(script)` | Same, but throws `BashException` on non-zero exit |
| `bash.writeFile(path, ...)` | Write a file into the virtual filesystem |
| `bash.readFile(path)` / `readFileBytes` | Read a string, or exact bytes |
| `bash.mkdir` / `bash.remove` | Manage virtual directories/files |
| `bash.close()` | Deterministically free the native instance |

Builder options: `cwd`, `env` map, `username`/`hostname` (virtual identity),
pre-seeded `files`, and `maxCommands` limits.

`BashkitRuntime` exposes `library()`, `abiVersion()` (guards ABI 1), `version()`,
`capabilitiesJson()` — handy for a health check endpoint.

---

## Caveats (honest ones)

- The C ABI currently exposes **only the isolated in-memory VFS** — there is no
  option to mount a host directory yet (this is a known, roadmap'd gap; it's also
  what keeps the sandbox airtight out of the box).
- `curl`/`wget` exist as commands but are **hard-unavailable** in this build (the
  network client isn't compiled in) — connect outbound access only if/when the
  upstream ABI exposes it, and only behind your own allowlist.
- Minor bashkit semantics worth knowing: `wc -l` counts newlines; ${#UNDEF} is `0`;
  exceeding a resource limit surfaces as `BashException`, not a non-zero `ExecResult`.

---

## Roadmap

- [ ] **M1** `BashTool` LLM/agent layer — tool metadata, input/output schema,
      `systemPrompt()`, typed errors.
- [x] **M2** Packaging — native lib bundled per platform, auto-detected and
      auto-loaded. (CI matrix across Linux/macOS/Windows still TBD.)
- [ ] **M3** Closer C-ABI gaps — richer upstream ABI, or JNI for streaming output,
      custom builtins, snapshots.
- [ ] **M4** Publish to Maven Central.

---

## Project layout

```
src/main/java/io/bashkit/
  Bashkit.java         # JNA 1:1 mapping of the bashkit-capi C ABI
  BashkitRuntime.java  # lazy native load + ABI version guard
  Bash.java            # facade: builder, exec, VFS helpers
  ExecResult.java      # result record
  BashException.java   # error type with ABI status
  sample/BashkitSample.java   # runnable demo (main)
native/                # bundled native libs (bashkit v0.17.1), one per platform
src/test/java/io/bashkit/    # 40 tests: BashTest, VanillaBashTest, FeatureTest, NativeLoadTest
```

---

## License

[MIT](LICENSE). Bashkit4j is an independent Java binding of
[everruns/bashkit](https://github.com/everruns/bashkit) (MIT); the native
libraries are distributed under the upstream license. See
[NOTICE](NOTICE) for attribution and terms.