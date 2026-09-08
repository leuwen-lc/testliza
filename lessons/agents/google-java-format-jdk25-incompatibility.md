---
title: "pretty-format-java hook crashes on JDK 25"
trigger: "When pre-commit's pretty-format-java (google-java-format) hook fails on a .java file in this repo"
keywords: [pretty-format-java, google-java-format, NoSuchMethodError, javac, JDK 25, pre-commit, language-formatters-pre-commit-hooks]
date: 2026-09-08
---

## Context

`.pre-commit-config.yaml` runs the `pretty-format-java` hook from
`macisamuele/language-formatters-pre-commit-hooks` (rev v2.14.0), which downloads and
runs google-java-format via `--add-exports` into `com.sun.tools.javac.*` internals.
This repo's only installed JDK is Temurin 25.

## Failure Mode

The hook's default bundled google-java-format (1.22.0) throws:
```
java.lang.NoSuchMethodError: 'java.util.Queue com.sun.tools.javac.util.Log$DeferredDiagnosticHandler.getDiagnostics()'
```
at `JavaInput.buildToks`. google-java-format 1.22.0 was built against javac internals
that changed shape by JDK 25 — the reflection/`--add-exports` bridge no longer matches.
This blocks pre-commit for *every* `.java` file, not just the one being formatted, so it
silently blocks every coding task in a Java-touching sprint if unaddressed.

## Solution

Pin a newer google-java-format via the hook's own override flag — no JDK downgrade, no
new installed tooling, no hook-repo rev bump needed. Verified working on JDK 25: 1.28.0
(latest at time of writing is 1.36.1; anything ≥1.28.0 is likely fine, but only 1.28.0
was verified here).

```yaml
- id: pretty-format-java
  args: [--autofix, --aosp, --google-java-formatter-version=1.28.0]
```

Verify a candidate version directly before trusting it:
```bash
curl -sL -o /tmp/gjf.jar https://github.com/google/google-java-format/releases/download/v1.28.0/google-java-format-1.28.0-all-deps.jar
java --add-exports jdk.compiler/com.sun.tools.javac.api=ALL-UNNAMED \
     --add-exports jdk.compiler/com.sun.tools.javac.file=ALL-UNNAMED \
     --add-exports jdk.compiler/com.sun.tools.javac.parser=ALL-UNNAMED \
     --add-exports jdk.compiler/com.sun.tools.javac.tree=ALL-UNNAMED \
     --add-exports jdk.compiler/com.sun.tools.javac.util=ALL-UNNAMED \
     -jar /tmp/gjf.jar --aosp --set-exit-if-changed <some-file>.java
```
Exit 0 (or a clean diff) means it works; `NoSuchMethodError` means try a newer version.

## References

- `.pre-commit-config.yaml` (pretty-format-java hook)
- https://github.com/google/google-java-format/releases
