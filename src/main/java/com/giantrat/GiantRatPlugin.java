package com.giantrat;

import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.Map;
import java.util.Set;
import javax.inject.Inject;

import com.google.inject.Provides;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.AnimationController;
import net.runelite.api.Client;
import net.runelite.api.GameState;
import net.runelite.api.Model;
import net.runelite.api.ModelData;
import net.runelite.api.NPCComposition;
import net.runelite.api.NpcID;
import net.runelite.api.Player;
import net.runelite.api.RuneLiteObject;
import net.runelite.api.coords.LocalPoint;
import net.runelite.api.events.ClientTick;
import net.runelite.api.events.GameStateChanged;
import net.runelite.api.events.GameTick;
import net.runelite.api.events.HitsplatApplied;
import net.runelite.client.callback.ClientThread;
import net.runelite.client.config.ConfigManager;
import net.runelite.client.eventbus.Subscribe;
import net.runelite.client.events.ConfigChanged;
import net.runelite.client.plugins.Plugin;
import net.runelite.client.plugins.PluginDescriptor;

@Slf4j
@PluginDescriptor(
	name = "Giant Rat Transmog",
	description = "Finally, the endgame content we've all been waiting for. Become the most feared level-3 monster in Lumbridge.",
	tags = {"transmog", "rat", "model", "fun", "cosmetic"}
)
public class GiantRatPlugin extends Plugin
{
	private static final int GIANT_RAT_NPC_ID = NpcID.GIANT_RAT;

	// Giant rat animation IDs (verified from live NPC Actor data)
	private static final int ANIM_IDLE = 4932;
	private static final int ANIM_WALK = 4931;
	private static final int ANIM_ATTACK = 4933;
	private static final int ANIM_DEFEND = 4934;
	private static final int ANIM_DEATH = 4935;

	@Inject
	private Client client;

	@Inject
	private ClientThread clientThread;

	@Inject
	private GiantRatConfig config;

	// Local player rat
	private RuneLiteObject ratObject;
	private AnimationController controller;
	private boolean needsSpawn = false;
	private int spawnDelay = 0;
	private boolean isMoving = false;
	private boolean isActioning = false;
	private int lastActionAnim = -1;
	private int lastOrientation = -1;

	// Friend rats: player name (lowercase) -> tracked state
	private final Map<String, FriendRat> friendRats = new HashMap<>();
	private Set<String> ratFriendNameSet = new HashSet<>();

	// Cached model for reuse across all rats
	private Model ratModel;

	@Provides
	GiantRatConfig provideConfig(ConfigManager configManager)
	{
		return configManager.getConfig(GiantRatConfig.class);
	}

	@Override
	protected void startUp()
	{
		needsSpawn = true;
		spawnDelay = 3;
		parseRatFriendNames();
	}

	@Override
	protected void shutDown()
	{
		needsSpawn = false;
		clientThread.invokeLater(() ->
		{
			removeRat();
			removeAllFriendRats();
		});
	}

	@Subscribe
	public void onConfigChanged(ConfigChanged event)
	{
		if (!"giantrat".equals(event.getGroup()))
		{
			return;
		}

		if ("ratFriendNames".equals(event.getKey()))
		{
			parseRatFriendNames();
			// Remove rats for players no longer on the list
			clientThread.invokeLater(() ->
			{
				Iterator<Map.Entry<String, FriendRat>> it = friendRats.entrySet().iterator();
				while (it.hasNext())
				{
					Map.Entry<String, FriendRat> entry = it.next();
					if (!ratFriendNameSet.contains(entry.getKey()))
					{
						entry.getValue().remove();
						it.remove();
					}
				}
			});
		}
	}

	@Subscribe
	public void onGameStateChanged(GameStateChanged event)
	{
		if (event.getGameState() == GameState.LOGGED_IN)
		{
			needsSpawn = true;
			spawnDelay = 3;
		}
		else if (event.getGameState() == GameState.LOGIN_SCREEN
			|| event.getGameState() == GameState.HOPPING)
		{
			removeRat();
			removeAllFriendRats();
		}
	}

	@Subscribe
	public void onGameTick(GameTick event)
	{
		if (needsSpawn)
		{
			spawnDelay--;
			if (spawnDelay <= 0)
			{
				needsSpawn = false;
				buildRatModel();
				spawnRat();
			}
		}

		// Manage friend rats: spawn/despawn based on nearby players
		if (ratModel != null && client.getGameState() == GameState.LOGGED_IN)
		{
			updateFriendRats();
		}
	}

	@Subscribe
	public void onHitsplatApplied(HitsplatApplied event)
	{
		if (event.getActor() == null)
		{
			return;
		}

		// Local player defend
		Player localPlayer = client.getLocalPlayer();
		if (localPlayer != null && event.getActor() == localPlayer && controller != null)
		{
			playOneShot(controller, ANIM_DEFEND, isMoving);
		}

		// Friend player defend
		if (event.getActor() instanceof Player)
		{
			Player player = (Player) event.getActor();
			String name = sanitizeName(player.getName());
			FriendRat fr = friendRats.get(name);
			if (fr != null)
			{
				playOneShot(fr.controller, ANIM_DEFEND, fr.isMoving);
			}
		}
	}

	@Subscribe
	public void onClientTick(ClientTick event)
	{
		if (client.getGameState() != GameState.LOGGED_IN)
		{
			return;
		}

		// Update local player rat
		if (ratObject != null && controller != null)
		{
			Player localPlayer = client.getLocalPlayer();
			if (localPlayer != null)
			{
				updateRatForPlayer(localPlayer, ratObject, controller, true);
			}
		}

		// Update friend rats
		for (FriendRat fr : friendRats.values())
		{
			if (fr.ratObject != null && fr.controller != null && fr.player != null)
			{
				updateRatForPlayer(fr.player, fr.ratObject, fr.controller, false);
				fr.isMoving = fr.player.getPoseAnimation() != fr.player.getIdlePoseAnimation();
			}
		}
	}

	private void updateRatForPlayer(Player player, RuneLiteObject obj, AnimationController ctrl, boolean isLocal)
	{
		// Update position
		LocalPoint lp = player.getLocalLocation();
		if (lp != null)
		{
			obj.setLocation(lp, client.getPlane());
		}

		// Update orientation
		int orientation = player.getCurrentOrientation();
		if (isLocal)
		{
			if (orientation != lastOrientation)
			{
				lastOrientation = orientation;
				obj.setOrientation(orientation);
			}
		}
		else
		{
			obj.setOrientation(orientation);
		}

		// Detect movement
		int poseAnim = player.getPoseAnimation();
		int idlePoseAnim = player.getIdlePoseAnimation();
		boolean moving = poseAnim != idlePoseAnim;

		// Detect new action
		int actionAnim = player.getAnimation();
		int prevAction = isLocal ? lastActionAnim : getLastAction(player);
		boolean newAction = actionAnim != -1 && prevAction == -1;
		if (isLocal)
		{
			lastActionAnim = actionAnim;
		}
		else
		{
			setLastAction(player, actionAnim);
		}

		// Handle new attack/death action
		if (newAction)
		{
			if (isLocal) isActioning = true;
			int animId = (actionAnim == 836 || actionAnim == 2304) ? ANIM_DEATH : ANIM_ATTACK;
			boolean[] movingRef = isLocal ? null : new boolean[]{moving};
			playOneShot(ctrl, animId, moving);
			return;
		}

		// Don't interrupt one-shot actions
		if (isLocal && isActioning)
		{
			return;
		}

		// Pose: idle vs walk
		boolean wasMoving = isLocal ? isMoving : wasMoving(player);
		if (moving != wasMoving)
		{
			if (isLocal) isMoving = moving;
			ctrl.setAnimation(client.loadAnimation(moving ? ANIM_WALK : ANIM_IDLE));
			ctrl.setFrame(0);
			ctrl.setOnFinished(AnimationController::loop);
		}
	}

	private void playOneShot(AnimationController ctrl, int animId, boolean currentlyMoving)
	{
		ctrl.setAnimation(client.loadAnimation(animId));
		ctrl.setFrame(0);
		ctrl.setOnFinished(ac ->
		{
			isActioning = false;
			ac.setAnimation(client.loadAnimation(currentlyMoving ? ANIM_WALK : ANIM_IDLE));
			ac.setOnFinished(AnimationController::loop);
		});
	}

	// Track per-friend animation state using the FriendRat object
	private int getLastAction(Player player)
	{
		String name = sanitizeName(player.getName());
		FriendRat fr = friendRats.get(name);
		return fr != null ? fr.lastActionAnim : -1;
	}

	private void setLastAction(Player player, int anim)
	{
		String name = sanitizeName(player.getName());
		FriendRat fr = friendRats.get(name);
		if (fr != null)
		{
			fr.lastActionAnim = anim;
		}
	}

	private boolean wasMoving(Player player)
	{
		String name = sanitizeName(player.getName());
		FriendRat fr = friendRats.get(name);
		return fr != null && fr.isMoving;
	}

	private void updateFriendRats()
	{
		if (ratFriendNameSet.isEmpty())
		{
			return;
		}

		// Track which friends are currently visible
		Set<String> visibleFriends = new HashSet<>();

		for (Player player : client.getPlayers())
		{
			if (player == client.getLocalPlayer() || player.getName() == null)
			{
				continue;
			}

			String name = sanitizeName(player.getName());
			if (!ratFriendNameSet.contains(name))
			{
				continue;
			}

			visibleFriends.add(name);

			FriendRat fr = friendRats.get(name);
			if (fr == null)
			{
				// Spawn rat for this friend
				fr = spawnFriendRat(player);
				if (fr != null)
				{
					friendRats.put(name, fr);
				}
			}
			else
			{
				// Update player reference (may change between ticks)
				fr.player = player;
			}
		}

		// Remove rats for friends no longer visible
		Iterator<Map.Entry<String, FriendRat>> it = friendRats.entrySet().iterator();
		while (it.hasNext())
		{
			Map.Entry<String, FriendRat> entry = it.next();
			if (!visibleFriends.contains(entry.getKey()))
			{
				entry.getValue().remove();
				it.remove();
			}
		}
	}

	private FriendRat spawnFriendRat(Player player)
	{
		if (ratModel == null)
		{
			return null;
		}

		AnimationController ctrl = new AnimationController(client, client.loadAnimation(ANIM_IDLE));
		ctrl.setOnFinished(AnimationController::loop);

		RuneLiteObject obj = client.createRuneLiteObject();
		obj.setModel(ratModel);
		obj.setAnimationController(ctrl);

		LocalPoint lp = player.getLocalLocation();
		obj.setLocation(lp, client.getPlane());
		obj.setOrientation(player.getCurrentOrientation());
		obj.setActive(true);

		FriendRat fr = new FriendRat();
		fr.ratObject = obj;
		fr.controller = ctrl;
		fr.player = player;

		log.info("Spawned rat for friend: {}", player.getName());
		return fr;
	}

	private void buildRatModel()
	{
		NPCComposition npcComp = client.getNpcDefinition(GIANT_RAT_NPC_ID);
		if (npcComp == null)
		{
			return;
		}

		int[] modelIds = npcComp.getModels();
		if (modelIds == null || modelIds.length == 0)
		{
			return;
		}

		ModelData[] modelDatas = new ModelData[modelIds.length];
		for (int i = 0; i < modelIds.length; i++)
		{
			modelDatas[i] = client.loadModelData(modelIds[i]);
			if (modelDatas[i] == null)
			{
				return;
			}
		}

		ModelData merged = client.mergeModels(modelDatas);

		short[] recolorFrom = npcComp.getColorToReplace();
		short[] recolorTo = npcComp.getColorToReplaceWith();
		if (recolorFrom != null && recolorTo != null)
		{
			for (int i = 0; i < recolorFrom.length; i++)
			{
				merged.recolor(recolorFrom[i], recolorTo[i]);
			}
		}

		ratModel = merged.light();
	}

	private void spawnRat()
	{
		removeRat();

		if (client.getGameState() != GameState.LOGGED_IN || ratModel == null)
		{
			return;
		}

		Player localPlayer = client.getLocalPlayer();
		if (localPlayer == null)
		{
			return;
		}

		controller = new AnimationController(client, client.loadAnimation(ANIM_IDLE));
		controller.setOnFinished(AnimationController::loop);

		ratObject = client.createRuneLiteObject();
		ratObject.setModel(ratModel);
		ratObject.setAnimationController(controller);

		LocalPoint lp = localPlayer.getLocalLocation();
		ratObject.setLocation(lp, client.getPlane());
		lastOrientation = localPlayer.getCurrentOrientation();
		ratObject.setOrientation(lastOrientation);
		ratObject.setActive(true);

		isMoving = false;
		isActioning = false;
		lastActionAnim = -1;

		log.info("Giant Rat transmog activated");
	}

	private void removeRat()
	{
		if (ratObject != null)
		{
			ratObject.setActive(false);
			ratObject = null;
		}
		controller = null;
		ratModel = null;
		isMoving = false;
		isActioning = false;
		lastActionAnim = -1;
		lastOrientation = -1;
	}

	private void removeAllFriendRats()
	{
		for (FriendRat fr : friendRats.values())
		{
			fr.remove();
		}
		friendRats.clear();
	}

	private void parseRatFriendNames()
	{
		ratFriendNameSet.clear();
		String names = config.ratFriendNames();
		if (names == null || names.trim().isEmpty())
		{
			return;
		}
		Arrays.stream(names.split(","))
			.map(String::trim)
			.filter(s -> !s.isEmpty())
			.map(s -> s.toLowerCase().replace(" ", "\u00a0"))
			.forEach(ratFriendNameSet::add);
	}

	private static String sanitizeName(String name)
	{
		if (name == null)
		{
			return "";
		}
		return name.toLowerCase().replace(" ", "\u00a0");
	}

	private static class FriendRat
	{
		RuneLiteObject ratObject;
		AnimationController controller;
		Player player;
		int lastActionAnim = -1;
		boolean isMoving = false;

		void remove()
		{
			if (ratObject != null)
			{
				ratObject.setActive(false);
				ratObject = null;
			}
			controller = null;
			player = null;
		}
	}
}
