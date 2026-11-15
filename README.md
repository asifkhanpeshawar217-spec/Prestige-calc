# Prestige Calculator

A calculator-only client-side Fabric mod for Minecraft **1.21.11**.

## Use

1. Install the mod JAR in your Fabric `mods` folder.
2. Launch Minecraft.
3. Press **P** to open the Prestige Calculator.
4. Enter the rebirth, your current prestige, and your target prestige.
5. Press **Calculate**.

## Price catalogue

- The mod downloads the recorded `prestige_prices.json` catalogue from this repository's main branch when Minecraft starts.
- A local copy is saved as `prestige_prices_cache.json` in the Minecraft game directory.
- If the download is unavailable, the mod uses the last successfully cached catalogue.
- Prestige 1 is treated as free and does not need a catalogue entry.
- Missing prices are reported explicitly. The calculator never fills gaps using a guessed progression pattern.

The catalogue can be expanded later as additional rebirths are scanned and verified.

## Controls

- **P** — open or close the calculator.
- **Esc** — close the calculator.

## Build

The project uses Java 21, Fabric Loom, and GitHub Actions. Pushes and pull requests targeting the default branch build the mod JAR.
