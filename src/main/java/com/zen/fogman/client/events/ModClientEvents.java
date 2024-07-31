package com.zen.fogman.client.events;

import com.zen.fogman.common.entity.ModEntities;
import com.zen.fogman.common.entity.the_man.TheManEntity;
import com.zen.fogman.common.entity.the_man.TheManPredicates;
import com.zen.fogman.common.entity.the_man.TheManState;
import com.zen.fogman.common.other.Util;
import com.zen.fogman.common.sounds.ModSounds;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.render.Camera;
import net.minecraft.client.render.Frustum;
import net.minecraft.client.sound.PositionedSoundInstance;
import net.minecraft.client.sound.SoundManager;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.hit.HitResult;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.BlockStateRaycastContext;
import org.joml.Matrix4f;

import java.util.List;

@Environment(EnvType.CLIENT)
public class ModClientEvents implements ClientTickEvents.EndTick {

    public static final double MAN_DETECT_RANGE = 1024;

    public PositionedSoundInstance chaseTheme;
    public PositionedSoundInstance nightAmbience;

    public ModClientEvents() {
        this.chaseTheme = PositionedSoundInstance.master(ModSounds.MAN_CHASE,1f);
        this.nightAmbience = PositionedSoundInstance.master(ModSounds.NIGHT_AMBIENCE,1f,0.15f);
    }

    public void cameraTick(MinecraftClient client, TheManEntity theMan) {
        if (client.world == null) {
            return;
        }

        if (client.player == null) {
            return;
        }

        Camera camera = client.gameRenderer.getCamera();
        Vec3d cameraLookVector = Util.getRotationVector(camera.getPitch(),camera.getYaw()).normalize();

        BlockHitResult result = client.world.raycast(
                new BlockStateRaycastContext(
                        new Vec3d(theMan.getX(), theMan.getEyeY(), theMan.getZ()),
                        camera.getPos(),
                        TheManPredicates.BLOCK_STATE_PREDICATE
                )
        );

        if (result.getType() != HitResult.Type.MISS) {
            theMan.updatePlayerLookedAt(client.player.getUuidAsString(),false);
        } else {
            float fov = client.options.getFov().getValue() * client.player.getFovMultiplier();

            Matrix4f projectionMatrix = client.gameRenderer.getBasicProjectionMatrix(fov);
            Matrix4f viewMatrix = new Matrix4f();
            viewMatrix = viewMatrix.lookAt(
                    camera.getPos().toVector3f(),
                    camera.getPos().toVector3f().add(cameraLookVector.toVector3f()),
                    cameraLookVector.rotateX((float) Math.toRadians(90)).toVector3f()
            );

            Frustum frustum = new Frustum(viewMatrix,projectionMatrix);

            theMan.updatePlayerLookedAt(client.player.getUuidAsString(),frustum.isVisible(theMan.getBoundingBox()));
        }
    }

    public void tick(MinecraftClient client) {
        SoundManager soundManager = client.getSoundManager();

        if (client.world == null) {
            if (soundManager.isPlaying(this.nightAmbience)) {
                soundManager.stop(this.nightAmbience);
            }
            if (soundManager.isPlaying(this.chaseTheme)) {
                soundManager.stop(this.chaseTheme);
            }
            return;
        }

        if (client.player == null) {
            return;
        }

        client.world.calculateAmbientDarkness();

        if (client.world.getAmbientDarkness() > 4 && !soundManager.isPlaying(this.nightAmbience) && !soundManager.isPlaying(this.chaseTheme) && TheManEntity.isInAllowedDimension(client.world)) {
            soundManager.play(this.nightAmbience);
        }

        List<TheManEntity> theManEntities = client.world.getEntitiesByType(
                ModEntities.THE_MAN,
                Box.of(
                        client.player.getPos(),
                        MAN_DETECT_RANGE,
                        MAN_DETECT_RANGE,
                        MAN_DETECT_RANGE
                ),
                TheManPredicates.VALID_MAN
        );

        if (!theManEntities.isEmpty()) {

            TheManEntity theMan = theManEntities.get(0);

            this.cameraTick(client,theMan);

            if (theMan.getState() == TheManState.CHASE && theMan.isInRange(client.player, TheManEntity.MAN_CHASE_DISTANCE)) {
                if (!soundManager.isPlaying(this.chaseTheme)) {
                    soundManager.play(this.chaseTheme);
                }
            } else {
                if (soundManager.isPlaying(this.chaseTheme)) {
                    soundManager.stop(this.chaseTheme);
                }
            }

        } else {

            if (soundManager.isPlaying(this.chaseTheme)) {
                soundManager.stop(this.chaseTheme);
            }
        }
    }

    @Override
    public void onEndTick(MinecraftClient client) {
        this.tick(client);
    }
}
