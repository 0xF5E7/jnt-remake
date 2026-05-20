# JNT — Java Native Transpiler

> A Java bytecode obfuscator and native transpiler that protects JVM applications by applying multi-layered transformations and converting Java bytecode to native machine code via C/Zig.

Built over 1+ years by a dedicated team. Special thanks to **ritchy**, **faceless**, **dramatically**, **leaf**, **reo**, **lvstrng**, and **twonick**.

---

## Table of Contents

- [Overview](#overview)
- [Architecture](#architecture)
- [Requirements](#requirements)
- [Building from Source](#building-from-source)
- [Usage](#usage)
- [Configuration Reference](#configuration-reference)
- [Mutators (Metaphor Engine)](#mutators-metaphor-engine)
  - [Renaming](#renaming)
  - [Flow Obfuscation](#flow-obfuscation)
  - [String Obfuscation](#string-obfuscation)
  - [Number Obfuscation](#number-obfuscation)
  - [Integrity Checks](#integrity-checks)
  - [Optimization & Cleanup](#optimization--cleanup)
  - [Miscellaneous](#miscellaneous)
- [Exhaust Engine (Native Transpilation)](#exhaust-engine-native-transpilation)
- [Exemptions](#exemptions)
- [Profiles](#profiles)
- [jntc — Companion Viewer](#jntc--companion-viewer)
- [CI / GitHub Actions](#ci--github-actions)
- [Platform Support](#platform-support)
- [Project Structure](#project-structure)
- [License](#license)

---

## Overview

**jnt** is a two-stage Java protection tool:

1. **Metaphor Engine** — A bytecode transformation pipeline that applies renaming, flow obfuscation, string encryption, number obfuscation, integrity checks, and more to your `.jar` file.
2. **Exhaust Engine** — Translates selected Java bytecode methods into native C code and compiles them to platform-specific shared libraries (`.dll` / `.so` / `.dylib`), using either **Zig** (default) or **GCC** as the compiler backend.

The result is a hardened JAR whose critical logic runs as native code, making reverse engineering significantly more difficult.

---

## Architecture

```
           Input JAR
              │
              ▼
┌─────────────────────────────┐
│       Metaphor Engine            │  ← Java bytecode obfuscation
│  (renaming, flow, strings,       │
│   numbers, integrity, etc.)      │
└────────────┬────────────────┘
               │  metaphor-temp.jar
               ▼
┌─────────────────────────────┐
│       Exhaust Engine             │  ← Native transpilation (JNI)
│  (bytecode → C → native lib)    |
│  Compiler: Zig / GCC / Make.     │
└────────────┬────────────────┘
               │
               ▼
       output-final.jar  +  native .dll/.so
```

The output JAR loads the native library at runtime via the `Loader` class, which auto-detects the OS and architecture, decompresses the embedded GZIP'd native binary, and loads it with `System.load()`.

---

## Requirements

| Requirement | Version |
|---|---|
| Java (JDK) | 21+ |
| Maven | 3.8+ |
| Zig (default compiler) | 0.15.2 |
| GCC (alternative) | Any modern version |
| Operating System | Windows, Linux, macOS |

---

## Building from Source

```bash
# Clone the repository
git clone https://github.com/your-org/jnt-remake.git
cd jnt-remake

# Build with Maven (produces a fat JAR with all dependencies)
mvn clean package

# The output JAR is located at:
# target/jnt-1.0-SNAPSHOT-jar-with-dependencies.jar
```

> **Note:** Java 21 preview features are enabled during compilation. Make sure your JDK is exactly version 21 or later.

The build bundles the local `libraries/asm.jar` (ASM 9.7) and all Maven dependencies into a single executable JAR.

---

## Usage

Run jnt from the command line, pointing it at your configuration file:

```bash
java -jar jnt-1.0-SNAPSHOT-jar-with-dependencies.jar --config config.yml
```

### CLI Options

| Flag | Type | Default | Description |
|---|---|---|---|
| `--config <path>` | `File` | *(required)* | Path to your `config.yml` |
| `--metaphor` | `Boolean` | `false` | Run the Metaphor (obfuscation) engine only; skip native transpilation |
| `--transpile` | `Boolean` | `false` | Run the Exhaust (native) engine only; skip obfuscation |
| `--logger` | `Boolean` | `true` | Enable/disable console logging |
| `--compiler <name>` | `String` | `zig` | Choose the native compiler: `zig`, `gcc`, or `make-gcc` |

### Examples

```bash
# Full pipeline: obfuscate + transpile to native
java -jar jnt.jar --config config.yml

# Obfuscation only (no native compilation)
java -jar jnt.jar --config config.yml --metaphor true

# Native transpilation only (skip obfuscation)
java -jar jnt.jar --config config.yml --transpile true

# Use GCC instead of Zig
java -jar jnt.jar --config config.yml --compiler gcc

# Suppress logging (silent mode)
java -jar jnt.jar --config config.yml --logger false
```

---

## Configuration Reference

All configuration lives in a single `config.yml` file. A full annotated example:

```yaml
# Path to the input JAR file
input: path/to/your/app.jar

# Path for the final output JAR (optional; auto-generated if omitted)
output: path/to/output.jar

# Optional: load a preset obfuscation profile from profiles/<name>/config.yml
# Use "custom" or omit to use this file's settings directly
# profile: custom

# Path where the native JNT library is stored at runtime
jnt-path: war/jnt

# Whether to inject a fingerprint into the output JAR (default: true)
fingerprint: true

# Native compiler settings
compiler:
  os: windows           # Target OS for compiler (windows / linux / macos)
  arch: x86_64          # Target architecture
  version: 0.15.2       # Compiler version

# Zig compiler settings (used when --compiler zig)
zig:
  os: windows
  arch: x86_64
  version: 0.15.2
  installation: zig     # Command to invoke zig (e.g., full path if not on PATH)

# Compilation targets
targets:
  - x86_64-windows
  # - x86_64-linux
  # - aarch64-linux

# Enable debug symbols for these targets
debug:
  compilation:
    - x86_64-windows

# Mutators configuration — see sections below
mutators:
  metaphor:
    order: [...]        # Execution order of mutators
    transformers: {...} # Per-mutator settings

  jnt:
    order: [cleanup, integrate]
    transformers:
      cleanup:
        enabled: true
      integrate:
        enabled: true
    virtualize:
      enabled: false
      chance: 100       # Percentage of eligible methods to virtualize
    intrinsic: false
    traceless: false

# Exempt specific packages/classes/methods/fields from transformation
exempt:
  - package: com.example.myapp.api
    classes:
      - class: PublicApi
        methods:
          - myPublicMethod
```

---

## Mutators (Metaphor Engine)

Mutators run in the order defined under `mutators.metaphor.order`. Each can be individually enabled or disabled.

### Renaming

Transform class, method, field, and descriptor names to meaningless identifiers.

```yaml
renamer:
  class:
    enabled: true
  method:
    enabled: true
  field:
    enabled: true
  desc:
    enabled: false    # Descriptor scrambling — use with caution
```

### Flow Obfuscation

Disrupt the readable control flow of methods.

| Mutator Key | Description |
|---|---|
| `flow.break` | Splits basic blocks to break decompiler output |
| `flow.flattening` | Flattens control flow using a dispatcher loop (switch-based state machine) |
| `flow.shuffle` | Randomly reorders instructions within safe boundaries |
| `flow.switch` | Obfuscates `switch` statements |
| `flow.traps` | Inserts unreachable exception-handler trap edges |
| `flow.opaque` | Inserts opaque predicates (always-true/always-false branches) |

```yaml
flow:
  break:
    enabled: false
  flattening:
    enabled: true
  traps:
    enabled: true
  opaque:
    enabled: true
  shuffle:
    enabled: false
  switch:
    enabled: false
```

### String Obfuscation

Encrypt or transform string literals in bytecode.

| Mutator Key | Description |
|---|---|
| `string.light` | Light-weight string obfuscation (XOR-based, low overhead) |
| `string.poly` | Polymorphic string encryption (heavier, harder to reverse) |
| `string.poly2` | Second-generation polymorphic string encryption |

```yaml
string:
  light:
    enabled: true
  poly:
    enabled: false
  poly2:
    enabled: false
```

### Number Obfuscation

Transform integer constants to make static analysis harder.

| Mutator Key | Description |
|---|---|
| `number.salt` | Replaces integer constants with XOR-salted expressions |
| `number.table` | Replaces constants with table lookups |

```yaml
number:
  salt:
    enabled: true
    level: HIGH       # LOW, MEDIUM, HIGH
    skipBsm: true     # Skip bootstrap methods (invokedynamic)
  table:
    enabled: true
```

### Integrity Checks

Embed runtime checks that cause the program to fail if tampered with.

| Mutator Key | Description |
|---|---|
| `call-graph` | Validates the call graph at runtime |
| `main-call-check` | Verifies the entry point is called from the expected context |
| `method-integrity` | Injects checksum validation into method bodies |

```yaml
call-graph:
  enabled: true

main-call-check:
  entry: 'com/example/MainKt'   # Internal class name of your entry point
  enabled: false

method-integrity:
  enabled: false
```

### Optimization & Cleanup

```yaml
optimizer:
  enabled: false        # Peephole + constant propagation + jump inlining

unused-method-remover:
  enabled: false        # Remove methods never called

unused-class-remover:
  enabled: false        # Remove classes never referenced

strip:
  enabled: true         # Remove debug info (line numbers, local variable names, source file)
```

### Miscellaneous

| Mutator Key | Description |
|---|---|
| `access-unify` | Widens access modifiers to `public` to aid other transformations |
| `watermark` | Injects a `myMetaphor @ jnt.so` field into every class |
| `inlining` | Inlines small methods into their call sites |
| `field-initialize` | Inlines field initializers |
| `lift-constructors` | Lifts initializer logic out of constructors |
| `internal-class-integrator` | Merges inner/anonymous classes into outer classes |
| `array-rewriter` | Rewrites array creation patterns |
| `indy-rewriter` | Rewrites `invokedynamic` call sites |
| `ref` | Obfuscates field and method reference patterns |
| `exchange` | Shuffles method parameter order |
| `dot-graph` | Exports a Graphviz `.dot` call graph (debug/analysis tool) |
| `splash-screen` | Adds a splash screen on startup |
| `mutilate-return` | Mutates return instructions |
| `runtime-patch` | Patches bytecode at runtime |

```yaml
access-unify:
  enabled: true
  exploit: false      # Enable more aggressive access exploitation

watermark:
  enabled: true

inlining:
  enabled: false
  debug: false
  iterate: false
  skip-transpiled-checks: false

array-rewriter:
  enabled: true

indy-rewriter:
  enabled: false

ref:
  enabled: true

exchange:
  enabled: false

dot-graph:
  enabled: false

splash-screen:
  enabled: false

mutilate-return:
  enabled: false
```

---

## Exhaust Engine (Native Transpilation)

The Exhaust engine converts selected Java bytecode methods to native C code, compiles them with Zig (or GCC), and embeds the resulting native library inside the output JAR.

At runtime, `war.jnt.Loader` extracts and loads the correct native binary for the detected OS/architecture automatically:

| OS | Architectures |
|---|---|
| Windows | `x86_64`, `aarch64` |
| Linux | `x86_64`, `aarch64` |
| macOS | `x86_64`, `aarch64` |

### JNT Transformers

```yaml
jnt:
  order:
    - cleanup       # Removes unnecessary bytecode artefacts
    - integrate     # Integrates the native loader stub into the JAR

  transformers:
    cleanup:
      enabled: true
    integrate:
      enabled: true

  # Experimental: virtualize integer operations through a mini VM
  virtualize:
    enabled: false
    chance: 100     # % chance each eligible instruction is virtualized

  # Embed C intrinsics (advanced, may affect compatibility)
  intrinsic: false

  # Remove JNT-specific traces from the output
  traceless: false
```

### Compiler Options

Select the compiler with the `--compiler` CLI flag:

| Value | Backend | Notes |
|---|---|---|
| `zig` | Zig cc | Default. Cross-compilation support, no separate toolchain install on path required beyond `zig` |
| `gcc` | GCC (experimental) | Requires GCC installed |
| `make-gcc` | Make + GCC | Uses a Makefile-driven build |

---

## Exemptions

Use the `exempt` block to protect specific packages, classes, methods, or fields from being transformed. This is critical for reflection-heavy code, serialization, public APIs, and framework entry points.

```yaml
exempt:
  # Exempt a specific method by name
  - package: com.example.crypto
    classes:
      - class: Blowfish
        methods:
          - encrypt

  # Exempt a method by its descriptor
  - package: com.example.crypto
    classes:
      - class: Blowfish
        methods:
          - encrypt(Ljava/lang/String;)Ljava/lang/String;

  # Exempt all classes in a package
  - package: com.example.api
    classes:
      - class: "*"
        methods:
          - "*"
        fields:
          - "*"

  # Global: exempt a field from all classes everywhere
  - package: "*"
    classes:
      - class: "*"
        fields:
          - serialVersionUID
```

> **Tip:** Always exempt `serialVersionUID` fields globally to avoid breaking Java serialization.

---

## Profiles

You can define preset configurations in `profiles/<name>/config.yml` and reference them:

```yaml
profile: my-profile
```

When a profile is set (and is not `custom`), jnt loads `profiles/my-profile/config.yml` instead of using the current file's transformer settings. This allows switching between obfuscation presets (e.g., `debug`, `release`, `paranoid`) without editing the main config.

---

## jntc — Companion Viewer

jntc is a Swing-based GUI bundled in the same JAR that lets you inspect obfuscated and processed JAR files:

- **File tree** view of all entries in the JAR
- **Syntax-highlighted** bytecode/source decompilation (using CFR decompiler and RSyntaxTextArea)
- **Metadata table** showing entry attributes
- **Error list** panel
- **Drag-and-drop** JAR loading

To launch jntc, run:

```bash
java -cp jnt.jar war.toolkit.JNTC
```

---

## CI / GitHub Actions

The repository includes a GitHub Actions workflow (`.github/workflows/maven.yml`) that:

1. Checks out the repository on every push to `main`
2. Sets up JDK 21 (Temurin)
3. Builds the project with `mvn clean package`
4. Automatically publishes a GitHub Release with the built JAR and a generated changelog

Releases are tagged `v<run_number>` (e.g., `v42`).

Dependabot is configured (`.github/dependabot.yml`) to keep Maven dependencies up to date automatically.

---

## Platform Support

| Platform | x86_64 | aarch64 |
|---|---|---|
| Windows | ✅ | ✅ |
| Linux | ✅ | ✅ |
| macOS | ✅ | ✅ |

The native library is bundled as a GZIP-compressed resource inside the JAR and extracted to a temp file at runtime.

---

## Project Structure

```
jnt-remake-main/
├── config.yml                        # Example full configuration
├── config.md                         # Config format documentation
├── pom.xml                           # Maven build file
├── libraries/
│   └── asm.jar                       # Bundled ASM 9.7 library
├── helpers/                          # C helper sources (boxing, invokedynamic)
│   ├── boxing.c / boxing.h
│   └── invokedynamic.c / invokedynamic.h
├── intrinsics/                       # C intrinsic sources
│   └── intrinsics.c / intrinsics.h
├── jni/
│   └── jni.h                         # JNI header
└── src/main/java/war/
    ├── Entrypoint.java               # Main entry point (CLI)
    ├── configuration/                # YAML config loader (SnakeYAML-based)
    ├── jar/                          # JAR reading/writing utilities
    ├── jnt/
    │   ├── Loader.java               # Native library loader
    │   ├── cache/                    # Bytecode caching layer
    │   ├── core/                     # JNT transpilation core & code units
    │   ├── exhaust/                  # Native compilation pipeline
    │   └── ...
    ├── metaphor/
    │   ├── Metaphor.java             # Obfuscation pipeline builder
    │   ├── mutator/
    │   │   ├── flow/                 # Flow obfuscation mutators
    │   │   ├── data/                 # String & number obfuscation
    │   │   ├── integrity/            # Integrity check mutators
    │   │   ├── misc/                 # Renaming, stripping, watermark, etc.
    │   │   ├── optimization/         # Optimizer, dead code removal
    │   │   ├── loader/               # Cleanup & loader integration
    │   │   ├── virtualization/       # VM-based obfuscation (experimental)
    │   │   └── parameter/            # Parameter exchange mutator
    │   ├── tree/                     # Class/method/field node hierarchy
    │   ├── sim/                      # Bytecode simulator (value analysis)
    │   └── util/                     # ASM utilities, builders, descriptors
    ├── locker/                       # JAR fingerprinting
    └── toolkit/
        ├── JNTC.java                 # GUI companion viewer
        └── Decompression.java        # Decompression utilities
```

---

## License

See [LICENSE](LICENSE) for full terms.

---

*jnt is a research and protection tool. Use responsibly and only on software you own or have rights to protect.*
