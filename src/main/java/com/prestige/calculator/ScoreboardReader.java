package com.prestige.calculator;

import net.minecraft.client.MinecraftClient;
import net.minecraft.scoreboard.Scoreboard;
import net.minecraft.scoreboard.ScoreboardObjective;
import net.minecraft.scoreboard.ScoreboardDisplaySlot;
import net.minecraft.scoreboard.ScoreboardEntry;

import java.util.ArrayList;
import java.util.List;

/**
 * Reads visible scoreboard text only. Server-specific parsing will be configured
 * after the exact scoreboard labels and formatting are confirmed.
 */
public final class ScoreboardReader {
    private ScoreboardReader() {}

    public static List<String> getSidebarLines() {
        MinecraftClient client = MinecraftClient.getInstance();
        List<String> lines = new ArrayList<>();
        if (client.world == null) return lines;

        Scoreboard scoreboard = client.world.getScoreboard();
        ScoreboardObjective objective = scoreboard.getObjectiveForSlot(ScoreboardDisplaySlot.SIDEBAR);
        if (objective == null) return lines;

        for (ScoreboardEntry entry : scoreboard.getScoreboardEntries(objective)) {
            if (entry.hidden()) continue;
            String owner = entry.owner();
            lines.add(owner);
        }
        return lines;
    }
}
