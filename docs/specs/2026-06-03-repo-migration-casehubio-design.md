# Design: Migrate quarkmind repo to casehubio org — #167

## Goal

Transfer `casehubio/quarkmind` to `casehubio/quarkmind`. This completes the planned
org placement; `upstream → casehubio` was always the intended home. After transfer
GitHub redirects all old URLs automatically — no link rot, no broken issue references.

## Steps

### Step 1 — GitHub transfer (user action)

GitHub Settings → Danger Zone → Transfer → destination: `casehubio`.
Confirm with `casehubio/quarkmind`. All issues, PRs, releases, stars, and
the redirect from `casehubio/quarkmind` transfer automatically.

Must be done before any local changes.

### Step 2 — Update local origin remote

```bash
git -C /Users/mdproctor/claude/casehub/quarkmind remote set-url origin \
    https://github.com/casehubio/quarkmind.git
```

No `upstream` remote exists to remove. After transfer `origin` IS the
canonical casehubio repo — direct push, no fork model.

### Step 3 — Update CLAUDE.md

- `**GitHub repo:** casehubio/quarkmind` → `casehubio/quarkmind`
- Fork model section: remove personal-fork / upstream framing;
  `origin` is now the canonical casehubio repo

### Step 4 — Update in-repo docs

Mass-replace `casehubio/quarkmind` → `casehubio/quarkmind` in:
`docs/DESIGN.md`, `docs/index.md`, and all spec files containing the old URL.

### Step 5 — File parent repo issue

`casehubio/parent` contains three files with old URL:
- `docs/PLATFORM.md` (×2 occurrences)
- `docs/APPLICATIONS.md` (×1)
- `docs/repos/quarkmind.md` (×1)

File a single issue on `casehubio/parent`; do not edit peer repo directly.

## Out of Scope

Workspace plans (`wsp-quarkmind/plans/`) contain historical `--repo casehubio/quarkmind`
shell commands from already-executed plans. GitHub's redirect makes them functional
if re-run. No update needed.

## Testing

No logic — git remote config and doc edits. Verification: `git remote -v` shows
`casehubio/quarkmind`; `grep -r casehubio/quarkmind` in project repo returns zero hits.
