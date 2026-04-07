package com.giantrat;

import net.runelite.client.config.Config;
import net.runelite.client.config.ConfigGroup;
import net.runelite.client.config.ConfigItem;
import net.runelite.client.config.ConfigSection;

@ConfigGroup("giantrat")
public interface GiantRatConfig extends Config
{
	@ConfigItem(
		keyName = "info",
		name = "Tip",
		description = "Use the built-in Entity Hider plugin to hide your real player model under the rat",
		position = 0
	)
	default String info()
	{
		return "Enable Entity Hider > Hide Local Player to hide your character model";
	}

	@ConfigSection(
		name = "Rat Friends",
		description = "Show other players as giant rats too",
		position = 1
	)
	String ratFriendsSection = "ratFriends";

	@ConfigItem(
		keyName = "ratFriendNames",
		name = "Player names",
		description = "Comma-separated list of player names to also show as rats",
		section = "ratFriends",
		position = 2
	)
	default String ratFriendNames()
	{
		return "";
	}
}
