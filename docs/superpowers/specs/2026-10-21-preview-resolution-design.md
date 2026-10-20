# Preview Resolution Upgrade

## Goal

Increase preview detail on the Redmi Pad while keeping the portrait layout and
the apparent UI scale unchanged.

## Approved design

Move the private display and all frame-producing paths from `720×1280 @ 240 dpi`
to `1080×1920 @ 360 dpi`. Keeping the pixel-to-dp ratio constant preserves the
current logical display size, so app controls should not become smaller or
larger. The phone preview, interactive mirror, desktop read-only monitor, and
agent observation frames must use the same source dimensions.

Update dimension checks, frame metadata limits, and touch coordinate bounds to
match the new display. Touches continue to map from the displayed preview into
the private display's source coordinates; no hard-coded `720` or `1280` bounds
remain in the preview input path. Update every production invariant that encodes
the old geometry.

## Alternatives considered

- Enlarge the preview widget only: inexpensive, but only scales the existing
  pixels and does not improve detail.
- Use `1080×1920 @ 360 dpi` throughout: improves source detail while preserving
  logical layout size; selected by the user.
- Use `1440×2560`: offers more pixels but quadruples pixel volume and raises
  memory and binder payload costs beyond the requested step.

## Constraints and risks

- Pixel buffers grow by 2.25×. Preserve bounded frame handling and raise the
  accepted PNG/frame size only as far as needed, keeping app and desktop viewer
  limits consistent.
- Touch mapping, display lifecycle validation, frame evidence validation, and
  preview scaling must agree on the same dimensions.
- Keep the existing portrait aspect ratio and preview behavior. Do not change
  the desktop viewer's local-only or read-only behavior.

## Verification

Build the Debug APK. Confirm host-side display contracts accept the new default
geometry and that preview touch coordinates still map to the source display.
Install on the connected Redmi Pad Pro and visually confirm the rendered frame
uses the higher-resolution source.
