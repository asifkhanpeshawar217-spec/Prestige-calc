# Prestige Calculator

A client-side Fabric mod project for Minecraft 1.21.11.

## Current first-stage features
- Press **P** to open the calculator screen.
- Settings screen is opened from inside the calculator.
- ESC closes the active screen.
- Initial scoreboard and tooltip reader helpers.
- Price storage is separated by rebirth and only sums fully observed prices.

## Important
This is the initial buildable project foundation. Server-specific scoreboard parsing and
prestige-menu tooltip capture are not guessed: they need the exact visible scoreboard
labels and prestige menu item/tooltips from your server before implementing accurate
automatic price collection.

## Build
Run `gradle build` with Java 21. The GitHub Actions workflow builds the JAR automatically.
