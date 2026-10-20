# Known defects and regressions (M0 baseline)

All five defects from the M0 baseline are fixed. The two legacy app-domain checks
(cross-midnight time and Unicode folder names) were retired in M4 when the direct
CalendarContract/MediaStore code was removed; the pure-GUI replacement must
preserve those semantics and will add its own regression.

Run the remaining blocking regression:

```sh
JAVA_HOME=/path/to/jdk21 sh tools/test-known-defects.sh
```

| # | Defect | Regression | Current result |
|---|---|---|---|
| 1 | Model-marked LOW payment is allowed | `RiskGateTest.guiCommitments` | **FIXED (M1)** — confirm at A3, handoff at A2, deny at A0 |
| 2 | Eight subgoals hit the six-attempt cap | `KnownDefectsTest.eightSubgoals` | **FIXED (M3)** — retries are counted separately; 8 initial subgoals complete |
| 3 | Continuous progress stops after three actions | `KnownDefectsTest.continuousProgress` | **FIXED (M3)** — changed effect resets no-progress; 5-step sequence completes |
| 4 | 23:30 + 60 minutes ends on the same day | legacy parser removed in M4 | **FIXED (M2), code retired** — no direct calendar parser remains |
| 5 | Chinese directory name falls back to `agent-demo` | legacy parser removed in M4 | **FIXED (M2), code retired** — no direct MediaStore parser remains |

## Notes

- Defects 2–3 are part of `BrainHostTest`; a regression fails the host gate.
- M4 removed `CalendarStore`, `FilesStore`, `ClockStore` and their domain skills from
  the product; calendar/files/clock now go through the generic GUI path until
  deterministic pure-GUI skills are added.
- The GUI path must carry date/Unicode parameters explicitly rather than
  re-introducing silent defaults.
