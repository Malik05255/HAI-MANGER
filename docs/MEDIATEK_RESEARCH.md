# HAI MANAGER — MediaTek CPE Research

MediaTek is intentionally kept separate from Qualcomm SDX and Huawei Balong. A shared 5G feature set does not imply compatible boot, flash, partition, diagnostic, or SIMLOCK mechanisms.

## T750 / MT6890

MediaTek documents T750 as a 5G CPE platform built around the MT6890 integrated 5G SoC.

Reference devices/projects:
- `mt6890-cpe-recovery/mt6890-cpe-recovery` — recovery images/tools for Zyxel NR5103/NR5103E/FWA505 and Tozed ZLT-X28.
- `safuapy/zlt-x28-unbrick` — MT6890 NAND recovery, PMT handling and mtkclient patches.
- `bkerler/mtkclient` — generic MediaTek BROM/preloader/Download Agent tooling; device-specific support must still be verified.

Important differences even within MT6890 CPEs:
- Some boards use NAND and PMT.
- Others use eMMC and may use a different partition layout.
- Download Agents, preloader behavior and images are not interchangeable simply because the SoC is MT6890.

HAI policy:
- WebUI/vendor diagnostics first.
- Read-only hardware/storage fingerprint before recovery tooling.
- No cross-device flashing.
- No assumption that a recovered NVRAM image contains an IMEI-derived NCK.

## T830 / M80

MediaTek documents T830 as a newer CPE platform integrating an M80-generation 5G modem and Release-16 cellular features.

Reference research:
- MediaTek T830/M80 platform documentation.
- `bkerler/mtkclient#251` — active 2026 research asking about T830 support specifically on ZTE MC8512 and NVRAM dumping.

HAI treats T830 as a separate generation from T750/MT6890. A patch, Download Agent or storage assumption that works on MT6890 must not be applied to T830 without direct evidence.

## ZTE MC8512 / G5 family

This model needs special handling because public naming/evidence conflicts:
- ZTE's early G5 Ultra announcement described a Qualcomm X75 platform.
- Current ZTE support identifies MC8512 in the G5 family.
- Community/research reports on market MC8512 hardware identify MediaTek T830 and are actively testing mtkclient compatibility.

Therefore HAI classifies `MC8512` as `Variant-dependent` and requires hardware/firmware fingerprinting before selecting a low-level path. It is never allowed to fall back to the legacy ZTE ZX297520V3 IMEI calculator.

## MediaTek SIMLOCK / NVRAM model

Older/documented MediaTek platform internals show SIMLOCK data as modem/NVRAM-backed state, with protected NVRAM areas such as `protect_f` / `protect_s` used for lock-related records on some generations. This is useful for understanding architecture, but it is not evidence that modern T750/T830 CPEs use the same exact layout or that an unlock code can be calculated from IMEI.

HAI therefore separates:
- SIMLOCK state/storage research.
- Retry counters and lock categories.
- A legitimate depersonalization/unlock entry path.
- IMEI-only NCK derivation.

The last item remains unverified for T750/T830 and no code is generated from platform identity alone.

## Huawei

No Huawei router is mapped to MediaTek merely because MediaTek is common in other 5G CPEs. Huawei models remain on Balong or another platform only when model/hardware evidence supports that classification.
