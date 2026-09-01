# Bashkit4j

Java bindings for [Bashkit](https://github.com/everruns/bashkit) — a sandboxed,
in-process **virtual bash interpreter with a virtual file system**, originally
written in Rust.

```java
try (Bash bash = Bash.builder().build()) {
    ExecResult r = bash.exec("echo hello world");
    System.out.println(r.stdout()); // "hello world\n"
}
```

---

## The problem this solves

Coding agents, LLM tools, multi-tenant SaaS, and CI-like workflows increasingly
want to run **arbitrary shell scripts** against a "computer"-style environment.
The standard answer — `Runtime.exec()` / `ProcessBuilder` — shells out to a real
`bash` on the host with full access to the real filesystem and real processes.
That is a **security boundary you cannot safely cross with untrusted input**:
one `rm -rf /`, one `curl | sh`, one escaped `cat /etc/passwd` and the host
machine is compromised or leaking secrets.

**Bashkit4j lets a Java program safely run untrusted bash.** The script never
touches your real OS:

- **No process spawning** — `fork`/`exec` are never used; everything runs
  in-process inside a bundled native core.
- **Virtual filesystem** — scripts see an isolated in-memory filesystem. They can
  `mkdir`, `cd`, `cat`, `sort`, even `mktemp`, and it all stays in a sandbox.
  The host filesystem is simply not reachable.
- **Network deny-by-default** — `curl`/`wget` fail unless explicitly allowed.
- **Resource limits** — cap command count, input/output size, timeouts, etc.
- **Multi-tenant isolation** — every `Bash` instance is fully independent.

So a model or user can be handed a "terminal" without handing over the machine.

> Detailed, verified security properties are in the [Security](#security) section below.

---

## What Bashkit is (context)

Bashkit (the upstream Rust project) is a POSIX-ish bash reimplementation: 164
built-in commands (`grep`, `sed`, `awk`, `jq`, `curl`, `find`, ...) and full bash
syntax (variables, pipelines, redirection, loops, functions, arrays, here-docs)
all implemented in Rust — **no real bash binary required**. It ships an official
versioned **C ABI** (`bashkit-capi`) with prebuilt native libraries for Windows,
Linux, and macOS.

This repository binds that C ABI from Java through [JNA](https://github.com/java-native-access/jna)
— the same "native adapter" approach used by Bashkit's own Node bindings. No C
compiler is needed to build or use this library.

---

## Features

- **Sandboxed execution** — `Bash.exec(String)` returns stdout, stderr, exit code,
  truncation flags, and optional final environment JSON.
- **Virtual filesystem helpers** — `writeFile`, `readFile(Bytes)`, `mkdir`, `remove`
  operate on the interpreter's in-memory FS; files written in one call are visible
  in the next.
- **Builder configuration** — `cwd`, `env`, `username`/`hostname` (virtual
  identity), pre-seeded `files`, and `maxCommands` limits.
- **Stateful sessions** — shell variables and files persist across calls on the
  same `Bash` instance; separate instances are fully isolated.
- **Type-safe ownership** — native handles are freed deterministically via
  `AutoCloseable` and matching `*_free` calls; no leaks, no double-frees.
- **Binary-safe** — `readFileBytes`/`writeFile` handle arbitrary byte content.

### Verified feature matrix

All of the following are covered by passing tests in `FeatureTest` (run with
`mvn test`):

| Area | Verified behavior |
|---|---|
| Variables / expansion | `$X`, `${VAR:-default}`, `${X^^}`, `${#VAR}` |
| Arithmetic / substitution | `$((3+4))`, `$(cmd)` |
| Pipelines / redirection | `|`, `>`, heredocs (`<<EOF`) |
| Control flow | `for`, `if/elif/else`, `case` (variable patterns), functions |
| Arrays | indexed arrays `${a[@]}`, `${#a[@]}` |
| Text tools | `wc`, `head`, `rev`, `tr`, `cut` |
| File tools | `mkdir -p`, `touch`, `mv`, `rm -r`, `test -f` |
| Archives | `tar -cf` / `tar -xf` round-trip |
| Data tools | `jq -r`, `bc`, `expr`, `cut -c` |
| Checksums / enc | `base64`, `md5sum`, `sha256sum` |
| Binary VFS | arbitrary `byte[]` (incl. `0xff`) round-trip |
| Virtual identity | `whoami`, `hostname`, `id`, `$USER` via builder |
| Stateful session | vars/files persist across calls on one instance |
| Multi-tenant isolation | separate instances share no vars/files |
| Sandbox | host paths invisible; no `..` escape; network denied |
| Limits | `maxCommands` enforced (raises `BashException`) |

Notes on verified bashkit semantics: `wc -l` counts newlines (so `a\nb\nc` is
`2`); `${#UNDEF}` is `0`; `case` patterns match against a variable value (a
bare literal like `case x in a)` won't match); `md5sum`/`sha256sum` print a `-`
placeholder after the digest. Exceeding a resource limit is surfaced as a
`BashException` (ABI execution status), not a normal non-zero `ExecResult`.

---

## Requirements

- Java 17+
- Maven 3.6+ (to build)
- A native `bashkit` library for your platform (see [Native library](#native-library)).

### Native library

The bundled copy currently targets **Windows x86-64** (`native/windows-x86_64/bashkit.dll`,
bashkit v0.17.1). For other platforms (Linux, macOS), download the matching
`bashkit-capi-*` archive from the [Bashkit releases](https://github.com/everruns/bashkit/releases)
and point the loader at it (see below).

The library is located in this order:
1. JVM property `-Dbashkit.native.path=/path/to/libs`
2. Environment variable `BASHKIT_NATIVE_PATH`
3. The default platform library path (`java.library.path`)

When running tests or the sample with Maven, the path is set automatically for
Windows x86-64.

---

## Getting started

### Build & test

```bash
mvn test        # 38 tests: BashTest, VanillaBashTest, FeatureTest (see matrix below)
```

### Run the sample

```bash
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

### Usage

```java
import io.bashkit.Bash;
import io.bashkit.BashkitRuntime;
import io.bashkit.ExecResult;

BashkitRuntime.library(); // loads native lib, guards ABI version

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

## API reference

### `Bash` (implements `AutoCloseable`)

| Method | Description |
|---|---|
| `bash.exec(String)` | Run a script; returns `ExecResult`. Non-zero exit is a normal result. |
| `bash.execOrThrow(String)` | Like `exec`, but throws `BashException` on non-zero exit. |
| `bash.writeFile(path, byte[] \| String)` | Write to the virtual filesystem. |
| `bash.readFile(path)` / `readFileBytes(path)` | Read a string, or exact bytes (`byte[]`). |
| `bash.mkdir(path, recursive)` | Create a virtual directory. |
| `bash.remove(path, recursive)` | Remove a virtual file/directory. |
| `close()` | Free the native instance (idempotent, thread-safe). |

### `Bash.builder()...build()`

| Builder method | Purpose |
|---|---|
| `.cwd(String)` | Initial working directory. |
| `.env(k, v)` | Initial environment variables. |
| `.username(String)` / `.hostname(String)` | Virtual identity used by `whoami`/`hostname`/`id`. |
| `.file(path, content)` | Pre-seed a file in the VFS. |
| `.maxCommands(long)` | Cap on commands executed in one script. |

### `ExecResult` (record)

`stdoutBytes()`, `stdout()` (UTF-8), `stderr()`, `exitCode()`, `success()`,
`stdoutTruncated()`, `stderrTruncated()`, `finalEnvJson()`.

### `BashkitRuntime`

`library()`, `abiVersion()` (guards ABI 1), `version()`, `capabilitiesJson()`.

### `BashException`

Single error class with an `int status` field holding the raw bashkit ABI status.

---

## Security

These properties were **verified against the real `bashkit.dll`** (v0.17.1) with a
workload that probes host access, network, and cross-instance leakage:

| Probe | Result | Implication |
|---|---|---|
| `ls /`, `ls /home` | Only virtual `dev home tmp` | Host directories are not visible. |
| `test -e /etc/passwd` | `no-passwd` | Host system files are not reachable. |
| `test -f C:/Windows/win.ini` | `no` | Host (Windows) paths are not reachable. |
| `ls / ../..` | Same `dev home tmp` listing | `..` cannot escape the in-memory VFS. |
| `curl https://example.com` | `network access not configured`, exit 1 | Network denied by default. |
| `cat /etc/hostname` | `file not found` | No host processes/files. |
| `id` | `uid=1000(sandbox) ...` | Virtual identity, not the real OS user. |
| Two `Bash` instances, different env | Each sees only its own vars | Multi-tenant isolation. |

Capabilities reported by the bundled build: `abi: 1`, features `git`, `jq`, `vfs`
(outbound HTTP client is **not** compiled in, so `curl` is hard-unavailable).

### Mounts & the C ABI

The C ABI v1 intentionally does **not** expose host-filesystem mounts — by design
this binding gives you only the isolated in-memory VFS, which is the safest
default. If you need to expose a bounded host directory, that is a known gap
(see [Roadmap](#roadmap)). No host path, `..`, or symlink can escape the sandbox
in this build.

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
native/windows-x86_64/bashkit.dll   # bundled native lib (v0.17.1)
src/test/java/io/bashkit/          # BashTest + VanillaBashTest + FeatureTest (38 tests)
```

---

## Roadmap

- [ ] **M1** `BashTool` LLM/agent layer — tool metadata, input/output schema,
      `systemPrompt()`, typed errors.
- [ ] **M2** Packaging — bundle native lib per platform (`os.arch`/`os.name`),
      auto-load from the classpath; CI on Linux/macOS/Windows.
- [ ] **M3** Closer C-ABI gaps — richer upstream ABI, or a small hand-written JNI
      module for streaming output, custom builtins, and snapshots.
- [ ] **M4** Publish to Maven Central.

---

## License

[MIT](LICENSE). Bashkit4j is an independent Java binding of
[everruns/bashkit](https://github.com/everruns/bashkit) (MIT); the native
`bashkit.dll` is distributed under its own upstream license.
