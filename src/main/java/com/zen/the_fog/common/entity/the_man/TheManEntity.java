package com.zen.the_fog.common.entity.the_man;

import com.zen.the_fog.common.damage_type.ModDamageTypes;
import com.zen.the_fog.common.entity.ModEntities;
import com.zen.the_fog.common.entity.the_man.states.*;
import com.zen.the_fog.common.gamerules.ModGamerules;
import com.zen.the_fog.common.item.ModItems;
import com.zen.the_fog.common.other.Util;
import com.zen.the_fog.common.sounds.ModSounds;
import com.zen.the_fog.common.status_effects.ModStatusEffects;
import com.zen.the_fog.common.world.dimension.ModDimensions;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.fabricmc.fabric.api.networking.v1.PacketByteBufs;
import net.minecraft.block.*;
import net.minecraft.enchantment.EnchantmentHelper;
import net.minecraft.entity.*;
import net.minecraft.entity.ai.pathing.*;
import net.minecraft.entity.attribute.DefaultAttributeContainer;
import net.minecraft.entity.attribute.EntityAttributes;
import net.minecraft.entity.damage.DamageSource;
import net.minecraft.entity.damage.DamageTypes;
import net.minecraft.entity.effect.StatusEffectInstance;
import net.minecraft.entity.effect.StatusEffectUtil;
import net.minecraft.entity.effect.StatusEffects;
import net.minecraft.entity.mob.HostileEntity;
import net.minecraft.entity.passive.IronGolemEntity;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.nbt.NbtCompound;
import net.minecraft.network.PacketByteBuf;
import net.minecraft.network.packet.s2c.play.EntitySpawnS2CPacket;
import net.minecraft.predicate.block.BlockStatePredicate;
import net.minecraft.registry.tag.BlockTags;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.sound.SoundCategory;
import net.minecraft.sound.SoundEvent;
import net.minecraft.sound.SoundEvents;
import net.minecraft.util.Hand;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.hit.HitResult;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.MathHelper;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.*;
import org.jetbrains.annotations.Nullable;
import software.bernie.geckolib.animatable.GeoEntity;
import software.bernie.geckolib.core.animatable.instance.AnimatableInstanceCache;
import software.bernie.geckolib.core.animation.AnimatableManager;
import software.bernie.geckolib.core.animation.AnimationController;
import software.bernie.geckolib.core.animation.AnimationState;
import software.bernie.geckolib.core.object.PlayState;
import software.bernie.geckolib.util.GeckoLibUtil;

import java.util.Arrays;
import java.util.HashMap;

public class TheManEntity extends HostileEntity implements GeoEntity {
    public static final EntityDimensions HITBOX_SIZE = EntityDimensions.fixed(0.8f, 2.3f);
    public static final EntityDimensions CROUCH_HITBOX_SIZE = EntityDimensions.fixed(0.8f, 1.3f);
    public static final EntityDimensions CRAWL_HITBOX_SIZE = EntityDimensions.fixed(0.8f, 0.8f);

    public static final double MAN_SPEED = 0.5; // Stilt - Changed Attribute
    public static final double MAN_CLIMB_SPEED = 0.7;
    public static final double MAN_MAX_SCAN_DISTANCE = 10000.0;
    public static final double MAN_BLOCK_CHANCE = 0.25;
    public static final int MAN_CHASE_DISTANCE = 200;

    public static Block[] MAN_BREAK_WHITELIST = {
            Blocks.CHEST,
            Blocks.ENDER_CHEST,
            Blocks.DIAMOND_BLOCK,
            Blocks.DIAMOND_ORE,
            Blocks.DEEPSLATE_DIAMOND_ORE,
            Blocks.NETHERITE_BLOCK,
            Blocks.ANCIENT_DEBRIS,
            Blocks.EMERALD_BLOCK,
            Blocks.EMERALD_ORE,
            Blocks.DEEPSLATE_EMERALD_ORE,
            Blocks.BEDROCK,
            Blocks.BEEHIVE,
            Blocks.BEE_NEST,
            Blocks.ACACIA_LOG,
            Blocks.RAIL,
            Blocks.ACTIVATOR_RAIL,
            Blocks.DETECTOR_RAIL,
            Blocks.POWERED_RAIL,
            Blocks.WATER,
            Blocks.LAVA,
            Blocks.CAULDRON,
            Blocks.LAVA_CAULDRON,
            Blocks.WATER_CAULDRON,
            Blocks.BARREL,
            Blocks.BARRIER,
            Blocks.HOPPER
    };

    /* NBT data names */
    public static final String MAN_STATE_NBT = "ManState";
    public static final String MAN_ALIVE_TICKS_NBT = "ManAliveTicks";
    public static final String MAN_SHIELD_HEALTH_NBT = "ManShieldHealth";

    // Animation cache stuff
    private final AnimatableInstanceCache geoCache = GeckoLibUtil.createInstanceCache(this);

    @Nullable
    private Path path;

    /* Cooldowns */
    // Attack cooldown
    private long attackCooldown;
    // Alive ticks
    private long aliveTicks;

    // State manager
    private final StateManager stateManager;

    // Chances
    private double blockChance = MAN_BLOCK_CHANCE;

    // Maps
    public HashMap<String,Boolean> playersLookingMap = new HashMap<>();

    public TheManEntity(EntityType<? extends TheManEntity> entityType, World world) {
        super(entityType,world);

        this.attackCooldown = Util.secToTick(this.getAttributeValue(EntityAttributes.GENERIC_ATTACK_SPEED));
        this.aliveTicks = Util.secToTick(this.getRandom().nextBetween(30,120));
        this.stateManager = new StateManager(this);

        this.setStepHeight(1.0f);

        this.addStatusEffects();
        this.initStates();
        this.initPathfindingPenalties();
    }

    /* Initialization */

    public void onSpawn(ServerWorld serverWorld) {
        this.setNoGravity(false);
        this.noClip = false;

        if (!this.isHallucination()) {

            NbtCompound nbtCompound = this.writeNbt(new NbtCompound());

            if (nbtCompound.contains(MAN_STATE_NBT)) {
                this.setState(TheManState.values()[nbtCompound.getInt(MAN_STATE_NBT)]);
            } else {
                if (this.getTarget() != null) {
                    BlockHitResult hitResult = serverWorld.raycast(new BlockStateRaycastContext(this.getEyePos(), this.getTarget().getEyePos(), TheManPredicates.BLOCK_STATE_PREDICATE));
                    if (hitResult.getType() == HitResult.Type.MISS) {
                        this.setState(TheManState.STARE);
                    } else {
                        this.setState(TheManState.STALK);
                    }
                } else {
                    switch (this.getRandom().nextBetween(0, 2)) {
                        case 0:
                            this.setState(TheManState.STARE);
                            break;
                        case 1:
                            this.setState(TheManState.STALK);
                            break;
                    }
                }
            }
        } else {
            this.startChase();
        }

    }

    @Override
    public void onSpawnPacket(EntitySpawnS2CPacket packet) {
        super.onSpawnPacket(packet);
        if (packet.getEntityData() == 0 && !this.getWorld().isClient()) {
            this.onSpawn(this.getServerWorld());
        }
    }

    public void initPathfindingPenalties() {
        this.setPathfindingPenalty(PathNodeType.LEAVES,0);
        this.setPathfindingPenalty(PathNodeType.UNPASSABLE_RAIL,0);
        this.setPathfindingPenalty(PathNodeType.DOOR_OPEN,0);
        this.setPathfindingPenalty(PathNodeType.DOOR_IRON_CLOSED,0);
        this.setPathfindingPenalty(PathNodeType.DOOR_WOOD_CLOSED,0);
    }

    public void initStates() {
        this.stateManager.add(TheManState.CHASE,new ChaseState(this));
        this.stateManager.add(TheManState.STARE,new StareState(this));
        this.stateManager.add(TheManState.FLEE,new FleeState(this));
        this.stateManager.add(TheManState.STALK,new StalkState(this));
    }

    @Override
    protected EntityNavigation createNavigation(World world) {
        SpiderNavigation mobNavigation = new SpiderNavigation(this,world);

        mobNavigation.setCanSwim(true);

        return mobNavigation;
    }

    /* Attributes */
    // Stilt - Changed Attributes
    public static DefaultAttributeContainer.Builder createManAttributes() {
        return TheManEntity.createHostileAttributes()
                .add(EntityAttributes.GENERIC_MAX_HEALTH,100000) // Stilt - Changed Attribute
                .add(EntityAttributes.GENERIC_MOVEMENT_SPEED,MAN_SPEED)
                .add(EntityAttributes.GENERIC_ATTACK_DAMAGE,100.0) // Stilt - Changed Attribute
                .add(EntityAttributes.GENERIC_ATTACK_KNOCKBACK,3.5)
                .add(EntityAttributes.GENERIC_ATTACK_SPEED,10) // Stilt - Changed Attribute
                .add(EntityAttributes.GENERIC_FOLLOW_RANGE,MAN_MAX_SCAN_DISTANCE)
                .add(EntityAttributes.GENERIC_KNOCKBACK_RESISTANCE,100)
                .add(EntityAttributes.GENERIC_ARMOR,7)
                .add(EntityAttributes.GENERIC_ARMOR_TOUGHNESS,5);
    }

    /* States */
    public StateManager getStateManager() {
        return this.stateManager;
    }

    public void setState(TheManState state) {
        this.getDataTracker().set(TheManDataTrackers.STATE,state.ordinal());
    }

    public TheManState getState() {
        return TheManState.values()[this.getDataTracker().get(TheManDataTrackers.STATE)];
    }

    public void startChase() {
        if (this.getState() == TheManState.CHASE) {
            return;
        }
        this.setState(TheManState.CHASE);
        this.playAlarmSound();
        TheManUtils.doLightning(this.getServerWorld(),this);
    }

    /* Data trackers */
    @Override
    protected void initDataTracker() {
        super.initDataTracker();

        this.getDataTracker().startTracking(TheManDataTrackers.SHIELD_HEALTH, 1000f); // Stilt - Changed Attribute
        this.getDataTracker().startTracking(TheManDataTrackers.CLIMBING,false);
        this.getDataTracker().startTracking(TheManDataTrackers.CROUCHING,false);
        this.getDataTracker().startTracking(TheManDataTrackers.CRAWLING,false);
        this.getDataTracker().startTracking(TheManDataTrackers.STATE,TheManState.STARE.ordinal());
        this.getDataTracker().startTracking(TheManDataTrackers.IS_LUNGING,false);
    }

    public void setShieldHealth(float shieldHealth) {
        this.getDataTracker().set(TheManDataTrackers.SHIELD_HEALTH,shieldHealth);
    }

    public float getShieldHealth() {
        return this.getDataTracker().get(TheManDataTrackers.SHIELD_HEALTH);
    }

    public void damageShield(float damage) {
        if (!this.hasShield()) {
            return;
        }

        if (this.getShieldHealth() - damage <= 0) {
            this.playShieldBreakSound();
        }

        this.setShieldHealth(Math.max(0f,this.getShieldHealth() - damage));
    }

    public boolean hasShield() {
        return this.getShieldHealth() > 0;
    }

    public void setClimbing(boolean climbing) {
        this.getDataTracker().set(TheManDataTrackers.CLIMBING, climbing);
    }

    @Override
    public boolean isClimbing() {
        return this.getDataTracker().get(TheManDataTrackers.CLIMBING);
    }

    public boolean shouldClimb(final Path path) {
        if (this.getTarget() == null) {
            return false;
        }

        return path != null && path.getLength() == 1 && this.getTarget().getBlockY() > this.getBlockY() + this.getStepHeight();
    }

    public void setCrouching(boolean crouching) {
        this.getDataTracker().set(TheManDataTrackers.CROUCHING,crouching);
    }

    public boolean isCrouching() {
        return this.getDataTracker().get(TheManDataTrackers.CROUCHING);
    }

    public void setCrawling(boolean crawling) {
        this.getDataTracker().set(TheManDataTrackers.CRAWLING,crawling);
    }

    public boolean isCrawling() {
        return this.getDataTracker().get(TheManDataTrackers.CRAWLING);
    }

    @Environment(EnvType.CLIENT)
    public void updatePlayerLookedAt(String uuid, boolean lookedAt) {
        PacketByteBuf packet = PacketByteBufs.create();
        packet.writeInt(this.getId());
        packet.writeString(uuid);
        packet.writeBoolean(lookedAt);
        ClientPlayNetworking.send(TheManPackets.LOOKED_AT_PACKET_ID,packet);
    }

    public void updatePlayerMap(String uuid, boolean value) {
        this.playersLookingMap.put(uuid,value);
    }

    public void removePlayerFromMap(String uuid) {
        if (!this.playersLookingMap.containsKey(uuid)) {
            return;
        }
        this.playersLookingMap.remove(uuid);
    }

    public boolean isLookedAt() {
        for (boolean looking : this.playersLookingMap.values()) {
            if (looking) {
                return true;
            }
        }

        return false;
    }

    public void setLunging(boolean lunging) {
        this.getDataTracker().set(TheManDataTrackers.IS_LUNGING,lunging);
    }

    public boolean isLunging() {
        return this.getDataTracker().get(TheManDataTrackers.IS_LUNGING);
    }

    /* NBT data */

    @Override
    public void writeCustomDataToNbt(NbtCompound nbt) {
        super.writeCustomDataToNbt(nbt);
        nbt.putInt(MAN_STATE_NBT,this.getState().ordinal());
        nbt.putLong(MAN_ALIVE_TICKS_NBT,this.aliveTicks);
        nbt.putFloat(MAN_SHIELD_HEALTH_NBT,this.getShieldHealth());
    }

    @Override
    public void readCustomDataFromNbt(NbtCompound nbt) {
        super.readCustomDataFromNbt(nbt);
        if (nbt.contains(MAN_STATE_NBT)) {
            this.setState(TheManState.values()[nbt.getInt(MAN_STATE_NBT)]);
        }
        if (nbt.contains(MAN_ALIVE_TICKS_NBT)) {
            this.aliveTicks = nbt.getLong(MAN_ALIVE_TICKS_NBT);
        }
        if (nbt.contains(MAN_SHIELD_HEALTH_NBT)) {
            this.setShieldHealth(nbt.getFloat(MAN_SHIELD_HEALTH_NBT));
        }
    }

    /* Animations */
    private PlayState predictate(AnimationState<TheManEntity> event) {
        if (this.isClimbing()) {
            return event.setAndContinue(TheManAnimations.CLIMB);
        }

        if (event.isMoving()) {
            return event.setAndContinue(TheManAnimations.RUN);
        }

        return event.setAndContinue(TheManAnimations.IDLE);
    }

    @Override
    public void registerControllers(AnimatableManager.ControllerRegistrar controllers) {
        controllers.add(new AnimationController<>(this,"controller", Util.secToTick(0.1),this::predictate));
    }

    @Override
    public AnimatableInstanceCache getAnimatableInstanceCache() {
        return this.geoCache;
    }

    /* Hallucinations */
    public boolean isHallucination() {
        return false;
    }

    public boolean isParanoia() {
        return false;
    }

    /* Sounds */
    @Override
    protected float getSoundVolume() {
        return 3.0f;
    }

    public float getLoudSoundVolume() {
        return 8.0f;
    }

    @Override
    public SoundCategory getSoundCategory() {
        return SoundCategory.MASTER;
    }

    public void playAlarmSound() {
        this.playSound(ModSounds.MAN_ALARM,this.getLoudSoundVolume(),this.getSoundPitch());
    }

    public void playLungeSound() {
        this.playSound(ModSounds.MAN_LUNGE,3.0f,this.getSoundPitch());
    }

    public void playSlashSound() {
        this.playSound(ModSounds.MAN_SLASH,this.getSoundVolume() + 1f,1.0f);
    }

    public void playAttackSound() {
        this.playSound(ModSounds.MAN_ATTACK,this.getSoundVolume(),this.getSoundPitch());
    }

    public void playSpitSound() {
        this.playSound(ModSounds.MAN_SPIT,this.getSoundVolume(),this.getSoundPitch());
    }

    public void playLungeAttackSound() {
        this.playSound(ModSounds.MAN_LUNGE_ATTACK,this.getLoudSoundVolume(),this.getSoundPitch());
    }

    public void playShieldBreakSound() {
        this.playSound(ModSounds.SHIELD_BREAK,this.getLoudSoundVolume(),1f);
    }

    @Override
    public void playAmbientSound() {
        if (this.getState() == TheManState.CHASE) {
            return;
        }
        SoundEvent soundEvent = this.getAmbientSound();
        if (soundEvent != null) {
            this.playSound(soundEvent, this.getSoundVolume(), 1);
        }
    }

    @Override
    protected void playStepSound(BlockPos pos, BlockState state) {
        if (this.isTouchingWater()) {
            this.playSwimSound();
            this.playSecondaryStepSound(state);
        } else {
            BlockPos blockPos = this.getStepSoundPos(pos);
            if (!pos.equals(blockPos)) {
                BlockState blockState = this.getWorld().getBlockState(blockPos);
                if (blockState.isIn(BlockTags.COMBINATION_STEP_SOUND_BLOCKS)) {
                    this.playCombinationStepSounds(blockState, state);
                } else {
                    super.playStepSound(blockPos, blockState);
                }
            } else {
                super.playStepSound(pos, state);
            }
        }
    }

    @Override
    protected SoundEvent getHurtSound(DamageSource source) {
        return ModSounds.MAN_PAIN;
    }

    @Override
    protected SoundEvent getDeathSound() {
        return ModSounds.MAN_DEATH;
    }

    public void playCritSound() {
        this.playSound(SoundEvents.ENTITY_PLAYER_ATTACK_CRIT,this.getLoudSoundVolume(),1.0f);
    }

    /* Properties and Behavior */

    @Override
    public float getStepHeight() {
        return super.getStepHeight();
    }

    @Override
    public boolean canAvoidTraps() {
        return true;
    }

    @Override
    public boolean canGather(ItemStack stack) {
        return false;
    }

    @Override
    public boolean canUsePortals() {
        return false;
    }

    @Override
    protected boolean canStartRiding(Entity entity) {
        return false;
    }

    @Override
    public boolean canFreeze() {
        return false;
    }

    @Override
    public boolean canBreatheInWater() {
        return true;
    }

    @Override
    public boolean isFireImmune() {
        return false;
    }

    @Override
    public boolean canHaveStatusEffect(StatusEffectInstance effect) {
        return effect.getEffectType() != StatusEffects.INSTANT_DAMAGE &&
                effect.getEffectType() != StatusEffects.SLOWNESS &&
                effect.getEffectType() != StatusEffects.POISON &&
                effect.getEffectType() != StatusEffects.INVISIBILITY &&
                effect.getEffectType() != StatusEffects.WEAKNESS &&
                (Util.isDay(this.getWorld()) && !this.getWorld().getGameRules().getBoolean(ModGamerules.MAN_CAN_SPAWN_IN_DAY) && effect.getEffectType() != StatusEffects.REGENERATION);
    }

    public static boolean canManSpawn(ServerWorld serverWorld) {
        return !(TheManUtils.manExists(serverWorld) || TheManUtils.hallucinationsExists(serverWorld));
    }

    public static boolean isInAllowedDimension(World world) {
        return world.getRegistryKey() == World.OVERWORLD || world.getRegistryKey() == ModDimensions.ENSHROUDED_LEVEL_KEY;
    }

    @Override
    public boolean canSpawn(WorldAccess world, SpawnReason spawnReason) {
        return this.canSpawn(world);
    }

    @Override
    public boolean canSpawn(WorldView world) {
        return canManSpawn(this.getServerWorld());
    }

    @Override
    public boolean disablesShield() {
        return true;
    }

    @Override
    public boolean isAiDisabled() {
        return super.isAiDisabled();
    }

    @Override
    protected void dropInventory() {
        if ((Util.isDay(this.getWorld()) && !this.getWorld().getGameRules().getBoolean(ModGamerules.MAN_CAN_SPAWN_IN_DAY)) || this.isHallucination()) {
            return;
        }
        this.dropStack(new ItemStack(Items.WITHER_ROSE,this.random.nextBetween(1,6)));
        if (Math.random() < 0.45) {
            this.dropStack(new ItemStack(ModItems.CLAWS,1));
        } else {
            this.dropStack(new ItemStack(ModItems.TEAR_OF_THE_MAN,1));
        }
    }

    @Override
    public int getXpToDrop() {
        return 52;
    }

    @Override
    protected int computeFallDamage(float fallDistance, float damageMultiplier) {
        return 0;
    }

    public void blockDamage(DamageSource source,float amount) {
        Entity attacker = source.getAttacker();

        if (attacker == null) {
            return;
        }

        if (attacker instanceof LivingEntity livingEntity) {
            if (amount > 0 && (source.getTypeRegistryEntry() == DamageTypes.PLAYER_ATTACK || source.getTypeRegistryEntry() == DamageTypes.MOB_ATTACK)) {
                livingEntity.damage(new DamageSource(source.getTypeRegistryEntry(),this),amount);
            }
            this.playCritSound();
        }
    }

    public void blockDamage(DamageSource source) {
        this.blockDamage(source,0);
    }

    @Override
    public void kill() {
        this.despawn();
    }

    @Override
    public boolean damage(DamageSource source, float amount) {
        if (source.getTypeRegistryEntry() == DamageTypes.IN_WALL) {
            return false;
        }

        if (this.getState() == TheManState.STARE || this.getState() == TheManState.STALK) {
            this.blockDamage(source);
            return false;
        }

        if (this.hasShield()) {
            this.damageShield(amount);
            return true;
        }

        if (source.getName().contains("bullet")) {
            this.blockDamage(source);
            return false;
        }

        if (Util.isNight(this.getWorld())) {
            Entity attacker = source.getAttacker();

            if (attacker instanceof LivingEntity livingEntity && !livingEntity.getMainHandStack().isOf(ModItems.CLAWS) && !this.isHallucination()) {

                if (attacker instanceof IronGolemEntity) {
                    this.blockDamage(source,amount * 2f);

                    return false;
                }

                if (Math.random() < this.blockChance) {
                    if (this.blockChance < 0.9) {
                        this.blockChance += 0.1;
                    } else {
                        this.blockChance = 0.9;
                    }
                    this.blockDamage(source,amount / 4f);

                    this.aliveTicks -= 20;

                    return false;
                }
            }

            this.aliveTicks += 10;
        }

        return super.damage(source, amount);
    }

    public void addStatusEffects() {
        if (this.isHallucination()) {
            return;
        }
        this.addStatusEffect(TheManStatusEffects.REGENERATION);
    }

    public void despawn() {
        TheManUtils.doLightning(this.getServerWorld(),this);
        this.discard();
    }

    @Override
    protected Vec3d getAttackPos() {
        return this.getPos();
    }

    public void lunge(double x, double y, double z, double verticalForce) {
        if (this.isLunging()) {
            return;
        }
        this.playLungeSound();
        this.playLungeAttackSound();
        this.setVelocity((x - this.getX()) / 4,verticalForce + Math.abs((y - this.getY()) / 4),(z - this.getZ()) / 4);
        this.setLunging(true);
    }

    public void lunge(Vec3d position, double verticalForce) {
        this.lunge(position.getX(),position.getY(),position.getZ(),verticalForce);
    }

    public void lunge(Entity target, double verticalForce) {
        this.lunge(target.getX(),target.getY(),target.getZ(),verticalForce);
    }

    /**
     * Spawns hallucinations randomly around The Man
     */
    public void spawnHallucinations() {
        if (this.getState() != TheManState.CHASE || this.isHallucination()) {
            return;
        }

        ServerWorld serverWorld = this.getServerWorld();

        if (TheManUtils.hallucinationsExists(serverWorld)) {
            return;
        }

        for (int i = 0; i < 3; i++) {
            int xOffset = (this.getRandom().nextBoolean() ? 1 : -1) * this.getRandom().nextBetween(2,11);
            int zOffset = (this.getRandom().nextBoolean() ? 1 : -1) * this.getRandom().nextBetween(2,11);

            TheManEntityHallucination hallucination = new TheManEntityHallucination(ModEntities.THE_MAN_HALLUCINATION,serverWorld);

            hallucination.setPosition(this.getPos().add(xOffset,0,zOffset));

            serverWorld.spawnEntity(hallucination);
        }
    }

    public static boolean isObstructed(World world, Vec3d origin, Vec3d target) {
        return world.raycast(new BlockStateRaycastContext(origin,target, BlockStatePredicate.ANY)).getType() != HitResult.Type.MISS;
    }

    public void chaseIfTooClose(double radius) {
        if (this.getTarget() != null && this.getTarget().isInRange(this,radius)) {
            this.startChase();
        }
    }

    public void chaseIfTooClose() {
        this.chaseIfTooClose(15);
    }

    public boolean shouldBreak(Block block) {
        return !Arrays.asList(MAN_BREAK_WHITELIST).contains(block);
    }

    private void breakBlocksInWay(ServerWorld serverWorld, LivingEntity target) {
        if (this.isClimbing() || this.getTarget() == null || this.isMoving() || this.path == null || this.path.getLength() > 1 || !isObstructed(serverWorld,this.getPos().subtract(0,1,0),target.getPos().subtract(0,1,0))) {
            return;
        }

        Vec3d lookVector = Util.getRotationVector(0f,this.getYaw(1.0f));
        BlockPos lookBlockPos = BlockPos.ofFloored(this.getEyePos().add(lookVector));

        BlockState blockState = serverWorld.getBlockState(lookBlockPos);
        BlockState blockStateDown = serverWorld.getBlockState(lookBlockPos.down());

        Block block = blockState.getBlock();
        Block blockDown = blockStateDown.getBlock();

        if (!blockState.isAir() && block.getHardness() <= 2.0 && block.getHardness() >= 0.5) {
            if (this.shouldBreak(block)) {
                serverWorld.breakBlock(lookBlockPos,true);
            }

            if (!blockStateDown.isAir() && blockDown.getHardness() <= 2.0 && blockDown.getHardness() >= 0.5) {
                if (this.shouldBreak(blockDown)) {
                    serverWorld.breakBlock(lookBlockPos.down(),true);
                }
            }
        }
    }

    public void breakBlocksAround() {
        if (this.isDead() || this.isHallucination() || !this.getServerWorld().getGameRules().getBoolean(GameRules.DO_MOB_GRIEFING)) {
            return;
        }

        ServerWorld serverWorld = this.getServerWorld();
        LivingEntity target = this.getTarget();

        this.breakBlocksInWay(serverWorld,target);

        for (BlockPos blockPos : BlockPos.iterateOutwards(this.getBlockPos().up(), 1, 1, 1)) {
            BlockState blockState = serverWorld.getBlockState(blockPos);
            if (blockState.isAir() || blockState.isOf(Blocks.LAVA) || blockState.isOf(Blocks.WATER)) {
                continue;
            }

            Block block = blockState.getBlock();

            if (blockPos.getX() == this.getBlockX() && blockPos.getZ() == this.getBlockZ() && this.getBlockPos().getY() < blockPos.getY() && this.isClimbing()) {
                if (!blockState.isAir() && blockState.getBlock() instanceof LeavesBlock) {
                    serverWorld.breakBlock(blockPos, false);
                }
            }

            if (block instanceof TrapdoorBlock && blockState.contains(TrapdoorBlock.OPEN)) {
                if (blockPos.getY() > this.getBlockY()) {
                    if (!blockState.get(TrapdoorBlock.OPEN)) {
                        serverWorld.setBlockState(blockPos,blockState.with(TrapdoorBlock.OPEN,true));
                    }
                } else {
                    if (blockState.get(TrapdoorBlock.OPEN)) {
                        serverWorld.setBlockState(blockPos,blockState.with(TrapdoorBlock.OPEN,false));
                    }
                }
                continue;
            }

            if (this.isClimbing()) {
                continue;
            }

            if (block instanceof DoorBlock) {
                serverWorld.breakBlock(blockPos, true);
                serverWorld.playSoundAtBlockCenter(
                        blockPos,
                        SoundEvents.ENTITY_ZOMBIE_BREAK_WOODEN_DOOR,
                        SoundCategory.BLOCKS,
                        this.getLoudSoundVolume(),
                        1.0f,
                        true
                );
                continue;
            }

            if (!blockState.emitsRedstonePower()) {
                if (block instanceof TorchBlock && serverWorld.getGameRules().getBoolean(ModGamerules.MAN_SHOULD_BREAK_LIGHT_SOURCES)) {
                    serverWorld.breakBlock(blockPos, true);
                    serverWorld.playSoundAtBlockCenter(
                            blockPos,
                            SoundEvents.BLOCK_WOOD_BREAK,
                            SoundCategory.BLOCKS,
                            this.getSoundVolume(),
                            1.0f,
                            true
                    );
                }

                if ((block instanceof AbstractGlassBlock || block instanceof PaneBlock) && serverWorld.getGameRules().getBoolean(ModGamerules.MAN_SHOULD_BREAK_GLASS)) {
                    serverWorld.breakBlock(blockPos, true);
                    serverWorld.playSoundAtBlockCenter(
                            blockPos,
                            block.getSoundGroup(blockState).getBreakSound(),
                            SoundCategory.BLOCKS,
                            this.getSoundVolume(),
                            1.0f,
                            true
                    );
                }
            }
        }
    }

    public boolean tryAttackTarget(Entity target) {
        float attackDamage = (float)this.getAttributeValue(EntityAttributes.GENERIC_ATTACK_DAMAGE);
        float attackKnockback = (float)this.getAttributeValue(EntityAttributes.GENERIC_ATTACK_KNOCKBACK);
        if (target instanceof LivingEntity) {
            attackDamage += EnchantmentHelper.getAttackDamage(this.getMainHandStack(), ((LivingEntity)target).getGroup());
            attackKnockback += (float)EnchantmentHelper.getKnockback(this);
        }

        int fireAspectLevel = EnchantmentHelper.getFireAspect(this);
        if (fireAspectLevel > 0) {
            target.setOnFireFor(fireAspectLevel * 4);
        }

        boolean damaged = target.damage(this.getDamageSources().create(ModDamageTypes.MAN_ATTACK_DAMAGE_TYPE,this), attackDamage);
        if (damaged) {
            if (attackKnockback > 0.0F && target instanceof LivingEntity) {
                ((LivingEntity)target)
                        .takeKnockback(
                                attackKnockback * 0.5F,
                                MathHelper.sin(this.getYaw() * (float) (Math.PI / 180.0)),
                                -MathHelper.cos(this.getYaw() * (float) (Math.PI / 180.0))
                        );
                this.setVelocity(this.getVelocity().multiply(0.6, 1.0, 0.6));
            }

            if (target instanceof PlayerEntity playerEntity) {
                playerEntity.disableShield(playerEntity.isSprinting());
            }

            this.applyDamageEffects(this, target);
            this.onAttacking(target);
        }

        return damaged;
    }

    @Override
    public boolean tryAttack(Entity target) {
        if (this.isHallucination()) {
            this.despawn();
            return false;
        }
        this.playAttackSound();
        this.playSlashSound();

        if (this.getState() == TheManState.STALK) {
            target.kill();
            this.startChase();
            return true;
        }

        return this.tryAttackTarget(target);
    }

    public void spitAt(LivingEntity target) {
        TheManSpitEntity spitEntity = new TheManSpitEntity(this.getWorld(),this);
        double velX = target.getX() - this.getX();
        double velY = target.getY() - this.getY();
        double velZ = target.getZ() - this.getZ();
        spitEntity.setVelocity(velX,velY,velZ,1.5f,10.0f);

        if (!this.isSilent()) {
            this.playSpitSound();
        }

        this.getWorld().spawnEntity(spitEntity);
    }

    public void attack(LivingEntity target) {
        if (this.isInAttackRange(target) && --this.attackCooldown <= 0L) {
            this.attackCooldown = Util.secToTick(this.getAttributeValue(EntityAttributes.GENERIC_ATTACK_SPEED));
            this.swingHand(Hand.MAIN_HAND);
            this.tryAttack(target);
        }
    }

    @Override
    public EntityDimensions getDimensions(EntityPose pose) {

        if (this.isCrouching()) {
            return CROUCH_HITBOX_SIZE;
        }

        if (this.isCrawling()) {
            return CRAWL_HITBOX_SIZE;
        }

        return HITBOX_SIZE;
    }

    @Override
    public float getEyeHeight(EntityPose pose) {
        return this.getEyeHeight(pose,this.getDimensions(pose));
    }

    @Nullable
    public Path findPath(double x, double y, double z) {
        Path newPath = this.getNavigation().findPathTo(x, y, z, 0);
        //this.fixPath(newPath);
        return this.path = newPath;
    }

    @Nullable
    public Path findPath(Vec3d position) {
        return this.findPath(position.getX(),position.getY(),position.getZ());
    }

    public void moveTo(double x, double y, double z, double speed) {
        this.findPath(x, Math.min(y, this.getY()), z);

        if (this.path != null) {
            this.getNavigation().startMovingAlong(this.path,speed);
        } else {
            this.getMoveControl().moveTo(x,y,z,speed);
        }
    }

    public void moveTo(Vec3d position, double speed) {
        this.moveTo(position.getX(),position.getY(),position.getZ(),speed);
    }

    public void moveTo(Entity target, double speed) {
        this.moveTo(target.getX(),target.getY(),target.getZ(),speed);
    }

    public boolean isMoving() {
        return this.getVelocity() != Vec3d.ZERO;
    }

    @Override
    public boolean isSilent() {
        return this.getState() == TheManState.STALK;
    }

    @Nullable
    @Override
    public LivingEntity getTarget() {
        LivingEntity target = this.getServerWorld().getClosestPlayer(this.getX(),this.getY(),this.getZ(),MAN_MAX_SCAN_DISTANCE,TheManPredicates.TARGET_PREDICATE);
        this.setTarget(target);
        return target;
    }

    /* Ticking */
    public ServerWorld getServerWorld() {
        if (this.getWorld().isClient()) {
            throw new Error("Attempt to get a ServerWorld in a Client thread");
        }
        return (ServerWorld) this.getWorld();
    }

    public void serverTick(ServerWorld serverWorld) {
        if (this.isAiDisabled()) {
            return;
        }

        if (this.getState() == TheManState.CHASE && this.getTarget() != null && this.getTarget().isDead()) {
            for (ServerPlayerEntity player : serverWorld.getPlayers(TheManPredicates.TARGET_PREDICATE)) {
                if (player.isInRange(this, TheManEntity.MAN_CHASE_DISTANCE)) {
                    if (!player.hasStatusEffect(ModStatusEffects.PARANOIA)) {
                        player.addStatusEffect(new StatusEffectInstance(
                                ModStatusEffects.PARANOIA,
                                Util.secToTick(120.0),
                                1,
                                false,
                                true
                        ));
                    }
                }
            }
            this.despawn();
            return;
        }

        if (--this.aliveTicks <= 0L) {
            this.despawn();
            return;
        }

        if (this.isAlive() && this.isHallucination()) {
            this.setHealth(this.getHealth() - 4f);
        }

        if (Util.isDay(serverWorld) && !serverWorld.getGameRules().getBoolean(ModGamerules.MAN_CAN_SPAWN_IN_DAY)) {
            this.despawn();
            return;
        }

        if (this.blockChance > MAN_BLOCK_CHANCE) {
            this.blockChance -= 0.01;
        }

        if (this.blockChance < MAN_BLOCK_CHANCE) {
            this.blockChance = MAN_BLOCK_CHANCE;
        }

        this.movementTick(serverWorld);

        this.getStateManager().tick(serverWorld);

        if (this.isLunging() && this.isOnGround()) {
            this.setLunging(false);
        }

        // If we are right above target, we move with the same velocity as the target and also down
        if (this.isLunging() && this.getTarget() != null) {
            Vec3d origin = new Vec3d(this.getX(),0,this.getZ());
            Vec3d target = new Vec3d(this.getTarget().getX(),0,this.getTarget().getZ());
            Vec3d targetVelocity = this.getTarget().getVelocity();

            if (target.isInRange(origin,10)) {
                this.setVelocity(targetVelocity.getX(),-2,targetVelocity.getZ());
                this.setLunging(false);
            }
        }
    }

    @Override
    protected void mobTick() {
        if (!this.getWorld().isClient()) {
            this.serverTick((ServerWorld) this.getWorld());
        }
    }

    public int getClimbHeight(BlockPos blockPos) {
        ServerWorld serverWorld = this.getServerWorld();

        int height = 0;

        BlockPos currentBlockPos = blockPos;

        while (TheManPredicates.CLIMBABLE_BLOCK_PREDICATE.test(serverWorld,currentBlockPos)) {
            currentBlockPos = currentBlockPos.up();
            height += 1;
            if (height > serverWorld.getTopY()) {
                break;
            }
        }

        if (height > 0) {
            height += 1;
        }

        return height;
    }

    public boolean isClimbable(BlockPos center,int height) {
        ServerWorld serverWorld = this.getServerWorld();

        for (int i = 1; i <= height; i++) {
            BlockPos blockPos = center.up(i);

            if (TheManPredicates.CLIMBABLE_BLOCK_PREDICATE.test(serverWorld,blockPos)) {
                return false;
            }
        }

        return true;
    }

    @Nullable
    public BlockPos getClimbPos(BlockPos center) {
        ServerWorld serverWorld = this.getServerWorld();
        for (BlockPos blockPos : BlockPos.iterateOutwards(center,1,0,1)) {
            if (TheManPredicates.CLIMBABLE_BLOCK_PREDICATE.test(serverWorld,blockPos)) {
                return blockPos;
            }
        }

        return null;
    }

    @Nullable
    public BlockPos getClimbablePos(BlockPos center,int height) {
        for (BlockPos blockPos : BlockPos.iterateOutwards(center,1,0,1)) {
            if (this.isClimbable(blockPos,height)) {
                return blockPos;
            }
        }

        return null;
    }

    public void climbTick(ServerWorld serverWorld) {
        if (this.getTarget() == null) {
            this.setClimbing(false);
            return;
        }

        boolean areBlocksAboveHead = Util.areBlocksAround(serverWorld,this.getBlockPos().up(2),1,0,1);

        this.setClimbing(areBlocksAboveHead && Util.get2dDistance(this.getPos(),this.getTarget().getPos()) <= 5 && this.getTarget().getBlockY() > this.getBlockY());

        if (!this.isClimbing()) {
            return;
        }

        this.setVelocity(0,MAN_CLIMB_SPEED,0);

        BlockPos climbBlockPos = this.getClimbPos(this.getBlockPos());

        if (climbBlockPos == null) {
            return;
        }

        this.getLookControl().lookAt(climbBlockPos.toCenterPos().getX(),this.getY(),climbBlockPos.toCenterPos().getZ(),30f,30f);

        int height = this.getClimbHeight(climbBlockPos);

        @Nullable BlockPos climbableBlockPos = this.getClimbablePos(climbBlockPos,height);

        if (climbableBlockPos == null) {
            return;
        }

        Vec3d climbablePos = climbableBlockPos.up().toCenterPos();

        this.updatePosition(climbablePos.getX(),this.getY(),climbablePos.getZ());
    }

    public void movementTick(ServerWorld serverWorld) {
        if (this.isSubmergedInWater() && (this.getTarget() == null || this.getTarget().getBlockY() >= this.getBlockY())) {
            Vec3d oldVelocity = this.getVelocity();
            this.setVelocity(oldVelocity.getX(),0.5,oldVelocity.getZ());
        }

        this.climbTick(serverWorld);

        boolean areBlocksAboveHead = Util.areBlocksAround(serverWorld,this.getBlockPos().up(2),2,0,2);
        boolean areBlocksAroundChest = Util.areBlocksAround(serverWorld,this.getBlockPos().up(1),2,0,2);

        this.setCrouching(areBlocksAboveHead && !areBlocksAroundChest && !this.isClimbing());
        this.setCrawling(areBlocksAboveHead && areBlocksAroundChest && !this.isClimbing());

        this.calculateDimensions();
    }

    /* Other */

    public void addEffectToClosePlayers(ServerWorld world, StatusEffectInstance statusEffectInstance) {
        if (this.isHallucination()) {
            return;
        }
        StatusEffectUtil.addEffectToPlayersWithinDistance(world,this,this.getPos(),MAN_CHASE_DISTANCE,statusEffectInstance,statusEffectInstance.getDuration() - 5);
    }
}
