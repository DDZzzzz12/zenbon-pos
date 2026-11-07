# PROTECTED MODULE — TSE (Technische Sicherheitseinrichtung)

## DO NOT MODIFY without explicit authorization

This module implements the legally required fiscal security device integration
for German KassenSichV compliance. Changes to this module can:

- Break fiscal compliance (legal liability)
- Corrupt TSE transaction chains (irreversible)
- Brick TSE hardware (firmware operations)
- Cause tax audit failures

## What this module contains

- **Admin operations**: TSE setup, factory reset, decommission, firmware updates
- **Signing operations**: Transaction start/update/finish, quick sign
- **Queue system**: Nachsignieren (late signing) for offline resilience
- **Configuration**: Connection mode switching, PIN/PUK management
- **Providers**: Local hardware and remote connector abstractions

## Rules for contributors

1. **Never modify** these files as a side effect of other work
2. **Never refactor** without explicit request and full understanding of fiscal implications
3. **Never change interfaces** — downstream consumers depend on exact signatures
4. **Test thoroughly** — TSE operations interact with real hardware
5. **Ask first** if you are uncertain whether your change affects this module
