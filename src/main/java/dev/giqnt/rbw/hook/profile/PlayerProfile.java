package dev.giqnt.rbw.hook.profile;

import dev.giqnt.rbw.hook.leaderboard.LeaderboardCategory;

import java.util.Map;

public record PlayerProfile(Map<String, Integer> stats, Map<LeaderboardCategory, Integer> positions) {}
