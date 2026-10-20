# Capability status

Updated: 2026-10-20  
Scope: internal Beta, pure-GUI execution is the only accepted product path.  
Source of truth for current status; historical docs under `docs/` record earlier prototypes and
direct-write experiments.

Status legend:

- **implemented** — code exists and is wired.
- **host pass** — deterministic host tests pass.
- **device pass** — verified on a real device for the stated scope.
- **partial** — exists but does not meet the pure-GUI or reliability bar.
- **uncovered** — not tested on device.
- **retired** — kept for history; must not be used by product runtime.

| Area | Status | Evidence / gap |
|---|---|---|
| Shizuku connection, private display, target, observation, frame | implemented / host pass / device pass (Android 16) | `tools/test-service.sh`, `test-shizuku-adapter.sh`, `test-capability-adapter.sh`; Android 13 phone not connected |
| Directed tap / setText / back / swipe, task move | implemented / host pass / device pass (Android 16) | capability adapter smoke; Android 13 pending |
| Preview mirror + user touch takeover | implemented / host pass / device pass (Android 16) | preview/touch smoke docs; Android 13 pending |
| Brain TaskGraph / FastLoop / SlowLoop / SessionRunner | implemented / host pass / legacy device smoke | `test-brain.sh`; not yet a pure-GUI acceptance |
| `ActionProposal → GroundedAction → ActionExecutor` | partial / host pass | M1 core: semantic proposals, strict grounding, `GuiRiskClassifier`, confirmation and re-ground executor; `Tap/SetText/Back/Scroll` route through it from `FastLoop`; Quark entry migrated to a governed tap; preview/user input ownership enforced in BlockingDeviceApi; `SequenceAction` executes composites through the executor and cancels remaining steps; calculator product path uses a governed sequence; legacy JD domain is unregistered from product runtime and is historical until M4 |
| `TaskContract` / structured parameters / `PlanValidator` | partial / host pass | M2: contract + validator wired into initial plan; clarification gate; structured time in contract; `fixedPlan` removed; TaskReport/Partial final gate wired; replanning validation and planner consumption of structured parameters remain |
| Semantic progress / bounded recovery / budgets | partial | M3: attempt cap and simple no-progress defects fixed; stable observation, no-replay-on-unknown and bounded replan (PlanValidator-gated) are wired; semantic-progress hook is wired (default trusts changed effect; policies can veto); model/stage-based judgment remains; 26 suites |
| Pure-GUI skills (search, fill, scroll, save draft, reopen verify) | **uncovered** | M4; not implemented |
| Domain direct writes (CalendarContract, MediaStore, AlarmClock, calculator visual) | legacy / partial | Calendar/Clock/Files direct writes are still registered in app runtime; must leave product registration in M4; prior PASS records are historical, not pure-GUI acceptance |
| Debug smoke entry (`ShellActivity`, soak intent) | implemented / debug-only | ShellActivity moved to `app/src/debug`; release manifest checked by `tools/verify-host.sh`; release no longer contains it |
| Release APK signing / manifest / checks | implemented / host pass | `tools/verify-release.sh`; release checksum and signing verified locally |
| Android 13 (API 33) device acceptance | partial / uncovered | code compatibility work exists; phone not connected |
| Device evaluation suite (24 dev + 12 held-out) | **uncovered** | M6; not built |
| Fault injection / 20-minute soak / two-device report | partial / historical | prior smoke only; not the PLAN gate |

## How to run the current host gate

```sh
JAVA_HOME=/path/to/jdk21 \
GRADLE_USER_HOME=/path/to/gradle-cache \
sh tools/verify-host.sh
```

The script runs service, device-api, brain, capability-adapter and Shizuku host tests, builds the
debug APK, and checks that `ShellActivity` is debug-only and release entry points stay unexported.
It also runs `tools/test-known-defects.sh`, which must report the five M0 reproductions as
`REPRODUCED` until M2/M3 fix them; see [known-defects.md](known-defects.md). It is not a substitute
for the device suites in M6.
