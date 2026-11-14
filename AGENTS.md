# TaskerTetherControl — project agent instructions

The shared Android rules and skills repository is pinned as the Git submodule `.agents/`.

## Mandatory agent startup

1. If accessible but uninitialized, run `git submodule update --init --recursive`. Report missing access/instructions; never fabricate shared rules.
2. Read `.agents/rules/INDEX.md` and all core rule files listed in the index before Android changes.
3. Read these relevant domain rules: `.agents/rules/tasker.md`, `.agents/rules/privileged-android.md`. Read other domain rules when the task warrants.
4. Consult `.agents/skills/README.md` and read the applicable skills **before** doing the task:
   - `.agents/skills/tasker-locale-action/SKILL.md`
   - `.agents/skills/tasker-tether-state/SKILL.md`
   - `.agents/skills/gradle-ci-build/SKILL.md`
   - `.agents/skills/persistent-apk-signing/SKILL.md`
   - `.agents/skills/apk-extract-publish/SKILL.md`
   - `.agents/skills/android-diagnostics/SKILL.md`
5. Also read this project's `README.md`, relevant source/tests, build configuration and `.github/workflows/` before changing implementation.

## Project-specific scope

Tasker tethering action and state conditions, systemless Magisk integration and Android user/profile handling.

## Invariants and lessons to preserve

- The default branch is master. Preserve the older Tasker/Locale action contract as well as QUERY_CONDITION state semantics; unavailable or stale hotspot/client state must report UNKNOWN rather than false or zero.
- Use the existing TetheringManager callback fallback logic, prevent null-unboxing with Integer client counts, and maintain event-order regression tests. Local-only Wi-Fi AP state must not be conflated with tethering.
- Magisk/systemless apps can appear in clone profiles: guard duplicate monitors. Do not add or execute destructive profile cleanup except under the existing explicit, verified policy and user authorization; never touch the primary user or unrelated apps.
- Maintain the protected signer / explicit unsigned CI compilation split; follow the project's build workflows exactly and report output validity.
- This project is PUBLIC but the shared ai-rules-android submodule is PRIVATE. Access to the private submodule is required for recursive clone and for agents to read the instructions; never copy private content into this public repository or change visibility as a workaround. Non-recursive checkout/build remains independent unless separately configured.

## Precedence and updating shared guidance

Explicit task instructions and this project's root `AGENTS.md` take precedence over generic shared procedures. Do not trigger unintended builds or claim device validation from CI alone. An update to the shared repository does not automatically update this project's pinned `.agents` gitlink; review the new shared revision and commit a gitlink update deliberately.

## Authoritative project configuration

Before implementation or build work, read `README.md` and `.github/workflows/build-apk.yml`, `.github/workflows/compile-apk.yml`, `app/build.gradle`, `build.gradle`, `settings.gradle`. Use these project files to establish the current toolchain/SDK versions, package identity, signing configuration and expected certificate where documented, build triggers, artifact publication steps and required tests. Inspect referenced test and validation files for the task. Report missing certificate or workflow information rather than inventing it; do not copy signing secrets into instructions.
