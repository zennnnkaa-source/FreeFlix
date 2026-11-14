# SunnyMod — Modular Refactor Design

This document is the contract for the modular refactor of the fake-lag VPN engine.
It applies the modular-architecture principles (policy-based design, dependency
inversion, one concern per module, ~400-line files) to the existing Java codebase.

## Package layout

```
com.fakelag.cryhard8
├── MainActivity.java            thin UI shell (entry point, unchanged manifest name)
├── FloatingOverlayService.java  thin overlay shell (unchanged manifest name)
├── PacketVpnService.java        thin VpnService shell (unchanged manifest name)
├── VpnActions.java              all Intent actions + extra keys (CONTRACT)
├── Prefs.java                   all SharedPreferences keys + accessors (CONTRACT)
├── core/                        transport: tunnel, packet loop, UDP sessions
│   ├── VpnEngine.java           tunnel lifecycle + run loop
│   ├── PacketLoop.java          parse + dispatch loop
│   ├── UdpSessionManager.java   UDP session map + recv threads + PacketSender impl
│   ├── PacketBuilder.java       IP/UDP header build, checksum, ICMP echo reply
│   └── TunAddressPool.java      tun IP allocation
├── filter/                      the fake-lag policy engine (CONTRACT interfaces)
│   ├── PacketFilter.java        policy interface
│   ├── FilterDecision.java      PASS / DROP / HOLD
│   ├── FilterKind.java          FREEZE / GHOST / JITTER / JITTER_V2 / DELAY_PING / TELE_SPEED
│   ├── FilterEngine.java        orchestrator interface
│   ├── PacketSender.java        replay send abstraction
│   ├── PacketContext.java       parsed packet snapshot
│   ├── TelePacket.java          buffered packet value type
│   ├── PacketClassifier.java    stateless payload classification (CONTRACT, done)
│   ├── DefaultFilterEngine.java concrete orchestrator (mutual exclusion + replay)
│   ├── FreezeFilter.java
│   ├── GhostFilter.java
│   ├── JitterFilter.java
│   ├── JitterV2Filter.java
│   ├── DelayPingFilter.java
│   └── TeleSpeedFilter.java
├── replay/
│   ├── ReplayEngine.java        replayPacketList + runnables
│   └── PacketBuffer.java        bounded buffer
├── dev/
│   ├── DevLogger.java           interface (CONTRACT, done)
│   └── DevLoggerImpl.java       concrete logger + report dump
└── app/
    └── AppPicker.java           multi-app selection
```

## Data flow

1. `PacketVpnService` (thin) receives an Intent action, maps it to a `FilterKind`
   via `FilterKind.fromAction`, and calls `FilterEngine.toggle(kind)`.
2. `VpnEngine` runs the tun read loop. `PacketLoop` parses each packet into a
   `PacketContext` and classifies it with `PacketClassifier`.
3. `PacketLoop` asks `FilterEngine.onOutbound(ctx)` for a `FilterDecision`:
   - `PASS` → `UdpSessionManager.send(ctx)`
   - `DROP` → count loss, continue
   - `HOLD` → the filter buffered it for replay, continue
4. Inbound packets go through `FilterEngine.onInbound(ctx)` (freeze drops here).
5. Replay: filters buffer `TelePacket`s and hand them to `ReplayEngine`, which
   sends them via the `PacketSender` (implemented by `UdpSessionManager`).

## Multi-app selection

`Prefs.getTargetPackages()` returns a `Set<String>` of selected packages (falls
back to the legacy single `target_package` key). `VpnEngine` calls
`builder.addAllowedApplication(pkg)` for each selected package. `AppPicker` shows
a multi-select dialog of launchable apps.

## Rules

- Each file stays under ~400 lines; split further if a file grows past that.
- No class reaches into another module's internals; cross-module calls go through
  the interfaces in this document.
- Preserve existing behavior exactly (same actions, same prefs keys, same packet
  heuristics). Do not change the manifest service/activity names.
