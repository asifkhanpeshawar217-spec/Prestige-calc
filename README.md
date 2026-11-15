# Prestige Calculator + Prestige Companion

A client-side Fabric mod for Minecraft **1.21.11**, plus a Windows desktop companion app.

## Components

### 1. Minecraft mod
- Press **P** to open the in-game calculator.
- Automatically scans actual `/prestige` menu item tooltips.
- Saves a local `prestige_prices.json` backup.
- Sends each completed page to the desktop app over localhost (`127.0.0.1:8765`).
- The desktop app is optional: scanning and the local JSON backup continue if it is not running.

### 2. Prestige Companion desktop app
Location: `companion/companion.py`
- Uses Python 3.10+ and only the Python standard library.
- Receives scanned pages from the mod.
- Stores prices in SQLite, one record per rebirth/prestige.
- Displays collected prices, page receipts, rebirths and missing-price estimates.
- Filters and searches collected prices.
- Calculates costs only when every price in the requested range is present.
- Imports and exports the compatible `prestige_prices.json` format.

## Run on Windows
1. Install Python 3.10+ from https://www.python.org/downloads/windows/ and enable **Add Python to PATH**. Ensure Tcl/Tk is included.
2. Download this repository or open it with GitHub Desktop.
3. Open the `companion` folder.
4. Double-click `start_companion.bat`, or run `py companion.py` in PowerShell.
5. Keep the app open, launch Minecraft with the updated Fabric mod, and open `/prestige`.
6. Scanned pages should appear in the desktop app automatically.

The app listens only on `127.0.0.1` (this computer), not on your local network.

## Import existing prices
In the app, click **Import JSON** and select your existing `prestige_prices.json`. Records merge by rebirth/prestige; no estimated prices are created.

## Data files
- `prestige_companion.db`: SQLite database created beside the app.
- `prestige_prices.json`: Minecraft-side backup in the game directory.
- Exported JSON remains compatible with the mod's price format.

## Expected maximum prestige pattern
- RB0–RB6: maximum = 75 + 25 × rebirth
- RB7 onward: maximum = 300 + 50 × (rebirth − 7)

This pattern is used for completeness reporting only, not to invent prices.

## Build
GitHub Actions builds the mod with Java 21, checks the companion's Python syntax, and packages the companion files.

## Troubleshooting
- **Port unavailable:** close another Prestige Companion instance and retry.
- **No incoming pages:** keep the app open before scanning and verify Minecraft uses the updated mod JAR.
- **No prices appear:** confirm the in-game scanner detected readable Cost tooltip lines.
- **Python closes immediately:** run `py companion.py` from PowerShell to see the error.