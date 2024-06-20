/*
 * Copyright (c) 2023-2024 Alexei Frolov
 *
 * Permission is hereby granted, free of charge, to any person obtaining a copy of
 * this software and associated documentation files (the “Software”), to deal in
 * the Software without restriction, including without limitation the rights to use,
 * copy, modify, merge, publish, distribute, sublicense, and/or sell copies of the
 * Software, and to permit persons to whom the Software is furnished to do so,
 * subject to the following conditions:
 *
 * The above copyright notice and this permission notice shall be included in all
 * copies or substantial portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED “AS IS”, WITHOUT WARRANTY OF ANY KIND,
 * EXPRESS OR IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES
 * OF MERCHANTABILITY, FITNESS FOR A PARTICULAR PURPOSE AND
 * NONINFRINGEMENT. IN NO EVENT SHALL THE AUTHORS OR COPYRIGHT
 * HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER LIABILITY,
 * WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING
 * FROM, OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR
 * OTHER DEALINGS IN THE SOFTWARE.
 */

package io.blert.challenges.tob.rooms;

import io.blert.challenges.tob.HpVarbitTrackedNpc;
import io.blert.challenges.tob.Location;
import io.blert.challenges.tob.TheatreChallenge;
import io.blert.challenges.tob.TobNpc;
import io.blert.core.DataTracker;
import io.blert.core.Hitpoints;
import io.blert.util.Tick;
import lombok.Getter;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.Client;
import net.runelite.api.HitsplatID;
import net.runelite.api.NPC;
import net.runelite.api.events.*;
import net.runelite.client.eventbus.Subscribe;
import net.runelite.client.util.Text;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Slf4j
public abstract class RoomDataTracker extends DataTracker {
    private static int TOB_HITPOINTS_VARBIT = 6448;

    protected final TheatreChallenge theatreChallenge;

    @Getter
    private final Room room;
    private final Pattern waveEndRegex;

    private final boolean startOnEntry;
    private boolean startingTickAccurate;
    private boolean shouldUpdateHitpoints;
    private int healTick = -1;

    protected RoomDataTracker(TheatreChallenge theatreChallenge, Client client, Room room, boolean startOnEntry) {
        super(theatreChallenge, client, room.toStage());
        this.theatreChallenge = theatreChallenge;
        this.room = room;
        this.waveEndRegex = Pattern.compile(
                "Wave '" + room.waveName() + "' \\(\\w+ Mode\\) complete!Duration: ([0-9:.]+)"
        );
        this.startOnEntry = startOnEntry;
    }

    protected RoomDataTracker(TheatreChallenge theatreChallenge, Client client, Room room) {
        this(theatreChallenge, client, room, false);
    }

    /**
     * Begins tracking data for the room.
     */
    public void startRoom() {
        startRoom(0, true);
    }

    /**
     * Begins tracking data for the room, assuming that the starting tick is not correct (possibly because the room was
     * already in progress).
     */
    public void startRoomInaccurate() {
        startRoom(0, false);
    }

    private void startRoom(int tickOffset, boolean accurate) {
        if (getState() != State.NOT_STARTED) {
            return;
        }

        super.start();

        startingTickAccurate = accurate;
        onRoomStart();
    }

    /**
     * Checks whether players are in the room and starts the room if they are.
     */
    public void checkEntry() {
        if (getState() == State.NOT_STARTED && this.startOnEntry) {
            if (playersAreInRoom()) {
                log.debug("Room {} started because player entered", room);
                startRoom();
            }
        }
    }

    @Override
    protected void onTick() {
        int tick = getTick();

        // The hitpoints varbit is delayed by up to 3 ticks, so don't update immediately following a heal as it may
        // undo the hitpoints added by the heal.
        if (shouldUpdateHitpoints && (healTick == -1 || tick - healTick > 3)) {
            getTrackedNpcs().stream().filter(npc -> npc instanceof HpVarbitTrackedNpc)
                    .map(npc -> (HpVarbitTrackedNpc) npc)
                    .findFirst()
                    .ifPresent(npc -> npc.updateHitpointsFromVarbit(client.getVarbitValue(TOB_HITPOINTS_VARBIT)));
            shouldUpdateHitpoints = false;
        }
    }

    /**
     * Checks if any players are located within the boundaries of the boss room.
     *
     * @return True if there is at least one player within the room.
     */
    public boolean playersAreInRoom() {
        return client.getPlayers().stream()
                .filter(player -> theatreChallenge.playerIsInChallenge(player.getName()))
                .anyMatch(player -> Location.fromWorldPoint(getWorldLocation(player)).inRoom(room));
    }

    /**
     * Updates the base and current hitpoints of all NPCs in the room to match the given raid scale.
     *
     * @param scale The raid scale.
     */
    public void correctNpcHitpointsForScale(int scale) {
        getTrackedNpcs().forEach(trackedNpc -> {
            NPC npc = trackedNpc.getNpc();
            int healthRatio = npc.getHealthRatio();
            int healthScale = npc.getHealthScale();

            if (healthScale > 0 && healthRatio != -1) {
                double percent = healthRatio / (double) healthScale;
                TobNpc tobNpc = TobNpc.withId(npc.getId()).orElseThrow();
                trackedNpc.setHitpoints(Hitpoints.fromRatio(percent, tobNpc.getBaseHitpoints(scale)));
            }
        });

    }

    /**
     * Initialization method invoked when the room is first started.
     */
    protected abstract void onRoomStart();

    /**
     * Implementation-specific equivalent of the {@code onGameObjectSpawned} Runelite event handler.
     * Should be overriden by implementations which require special handling.
     *
     * @param event The event.
     */
    protected void onGameObjectSpawn(GameObjectSpawned event) {
    }

    /**
     * Implementation-specific equivalent of the {@code onGameObjectDespawned} Runelite event handler.
     * Should be overriden by implementations which require special handling.
     *
     * @param event The event.
     */
    protected void onGameObjectDespawn(GameObjectDespawned event) {
    }

    /**
     * Implementation-specific equivalent of the {@code onGraphicsObjectCreated} Runelite event handler.
     * Should be overriden by implementations which require special handling.
     *
     * @param event The event.
     */
    protected void onGraphicsObjectCreation(GraphicsObjectCreated event) {
    }

    @Subscribe
    protected final void onGameObjectSpawned(GameObjectSpawned event) {
        if (!terminating()) {
            onGameObjectSpawn(event);
        }
    }

    @Subscribe
    protected final void onGameObjectDespawned(GameObjectDespawned event) {
        if (!terminating()) {
            onGameObjectDespawn(event);
        }
    }

    @Subscribe
    private void onGraphicsObjectCreated(GraphicsObjectCreated event) {
        if (!terminating()) {
            onGraphicsObjectCreation(event);
        }
    }

    @Override
    protected final void onMessage(ChatMessage chatMessage) {
        String stripped = Text.removeTags(chatMessage.getMessage());
        Matcher matcher = waveEndRegex.matcher(stripped);
        if (matcher.find()) {
            try {
                String inGameTime = matcher.group(1);
                finish(true, Tick.fromTimeString(inGameTime));
            } catch (IndexOutOfBoundsException e) {
                log.warn("Could not parse timestamp from wave end message: {}", stripped);
                finish(true);
            }
        }
    }

    @Override
    protected void onHitsplat(HitsplatApplied event) {
        if (event.getActor() instanceof NPC && event.getHitsplat().getHitsplatType() == HitsplatID.HEAL) {
            getTrackedNpcs().getByNpc((NPC) event.getActor()).ifPresent(npc -> {
                if (npc instanceof HpVarbitTrackedNpc) {
                    healTick = getTick();
                }
            });
        }
    }

    @Override
    protected void onVarbit(VarbitChanged event) {
        if (event.getVarbitId() == TOB_HITPOINTS_VARBIT) {
            shouldUpdateHitpoints = true;
        }
    }

    protected String formattedRoomTime() {
        return Tick.asTimeString(getTick());
    }
}
