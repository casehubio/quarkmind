---
id: PP-20260814-40a87e
title: "Cross-module ide_move_file requires package-private → public audit"
type: rule
scope: repo
applies_to: "Any class moved from quarkmind-sc2 to quarkmind-core (or between modules) via ide_move_file"
severity: important
refs:
  - docs/protocols/emulated-plugin-seam-visibility.md
violation_hint: "Package-private method in quarkmind-core called from quarkmind-sc2 — compiles locally but fails at runtime or in tests that resolve the dependency from the local Maven repo"
garden_ref: "GE-20260814-2d0df2"
created: 2026-08-14
---

When relocating a class from one Maven module to another via `ide_move_file`, audit all package-private members (methods, constructors, fields) for callers in the original module. IntelliJ updates import statements but does not promote visibility — package-private members become inaccessible from the original module's code after the package changes. Promote to `public` any member called cross-module, verify with `mvn compile`, and check for `@QuarkusTest` augmentation failures that surface only at test time.
