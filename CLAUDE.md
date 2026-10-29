# CLAUDE.md

## Comments

No comments in code (decision 2026-10-29): they go stale and mislead agents; the why goes in commit messages and PRs. KDoc/GoDoc included.
Only exceptions: tool directives that change behaviour (`eslint-disable*`, `@ts-expect-error`, `/// <reference`, shebangs, `//go:build`, `//go:generate`, `//go:embed`, `// +build`, `# syntax=`, `# shellcheck`, `//noinspection`, `noqa`, bundler magic comments) and license headers. Generated files and Markdown are left untouched.
