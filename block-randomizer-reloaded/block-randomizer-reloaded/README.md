# BlockRandomizerReloaded (Paper/Purpur 1.19.2)

Randomizes **every non-air block in newly-generated chunks** to a seeded random block.
Deterministic per-boot (set `seed` in `config.yml` to lock it). Processes chunks in vertical slices for TPS safety.

## Build (Local)
- Install **JDK 17** and **Gradle 8+**
- In this folder: `gradle build`
- Jar will be at `build/libs/block-randomizer-reloaded-1.0.0.jar`

## Build (GitHub Actions – no local Gradle needed)
1. Create a new GitHub repo and upload this folder.
2. Actions → enable workflows. A build will run automatically.
3. Download the artifact jar from the workflow run.

## Install
- Drop the jar into `plugins/` on a **Paper/Purpur 1.19.2** server.
- Start the server to generate `config.yml`.
- Adjust options as needed. Use `/brr reload` to rebuild the mapping without rebooting.
- Use `/brr dump` to write `mapping-dump.txt` for debugging.

## Config highlights
- `preserveCategories`: keep water/lava as liquids; falling blocks in falling group.
- `oneToOneMapping`: optional shuffle so categories map 1:1 (no repeats) where possible.
- `includeAir`: if true, air also becomes random (not recommended).
- `yStepPerTick`: vertical band size per-tick while transforming chunks (lower = safer).
- `worlds`: restrict to certain world names.
