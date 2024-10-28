package com.zen.the_fog.common.entity.the_man.states;

import com.mojang.authlib.minecraft.client.MinecraftClient;
import net.minecraft.entity.attribute.EntityAttributeModifier;
import com.zen.the_fog.common.entity.the_man.TheManEntity;
import com.zen.the_fog.common.entity.the_man.TheManPredicates;
import com.zen.the_fog.common.entity.the_man.TheManStatusEffects;
import com.zen.the_fog.common.gamerules.ModGamerules;
import com.zen.the_fog.common.other.Util;
import net.minecraft.advancement.Advancement;
import net.minecraft.advancement.AdvancementManager;
import net.minecraft.client.network.ClientAdvancementManager;
import net.minecraft.client.util.telemetry.WorldSession;
import net.minecraft.entity.LivingEntity;
import net.minecraft.entity.attribute.EntityAttribute;
import net.minecraft.entity.attribute.EntityAttributes;
import net.minecraft.entity.attribute.EntityAttributeModifier.Operation;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.item.Item;
import net.minecraft.item.Items;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.Identifier;
import net.minecraft.world.World;
import net.minecraft.world.gen.feature.EndIslandFeature;

public class ChaseState extends AbstractState {

    public EntityAttributeModifier attack_DamageModifier = new EntityAttributeModifier("config_attackdamage", -86, Operation.ADDITION);
    public EntityAttributeModifier attack_SpeedModifier = new EntityAttributeModifier("config_attackspeed", -9, Operation.ADDITION);    

    public World world;
    public WorldSession worldSession;

    public static final double LUNGE_COOLDOWN = 30;
    public static final double LUNGE_CHANCE = 0.4;

    public static final double SPIT_COOLDOWN = 20;
    public static final double SPIT_CHANCE = 0.8;

    public static final double HALLUCINATION_COOLDOWN = 60;
    public static final double HALLUCINATION_CHANCE = 0.1;

    private long lungeCooldown = Util.secToTick(LUNGE_COOLDOWN);
    private long spitCooldown = Util.secToTick(SPIT_COOLDOWN);
    private long hallucinationCooldown = Util.secToTick(HALLUCINATION_COOLDOWN);

    private static boolean attribute_changed = false;

    public ChaseState(TheManEntity mob) {
        super(mob);
    }


    public boolean server_PlayerHasAdvancements (LivingEntity target)
    {   
        Advancement nether_Advancement = target.getServer().getAdvancementLoader().get(new Identifier("minecraft:story/enter_the_nether"));
        Advancement diamond_Advancement = target.getServer().getAdvancementLoader().get(new Identifier("minecraft:story/mine_diamond"));
        Advancement netherite_Advancement = target.getServer().getAdvancementLoader().get(new Identifier("minecraft:nether/netherite_armor"));
        Advancement ancientdebris_Advancement = target.getServer().getAdvancementLoader().get(new Identifier("minecraft:nether/obtain_ancient_debris"));
        Advancement blazerod_Advancement = target.getServer().getAdvancementLoader().get(new Identifier("minecraft:nether/obtain_blaze_rod"));

        if (nether_Advancement != null || diamond_Advancement != null || netherite_Advancement != null ||
        ancientdebris_Advancement != null || blazerod_Advancement != null)
        {
            return true;
        }
    
        return false;
    }

    public boolean client_PlayerHasAdvancements ()
    {   
        net.minecraft.client.MinecraftClient client = net.minecraft.client.MinecraftClient.getInstance();

        if (client.player != null)
        {
            AdvancementManager manager = new ClientAdvancementManager(client, worldSession).getManager();
            

            Advancement nether_Advancement = manager.get(new Identifier("minecraft:story/enter_the_nether"));
            Advancement diamond_Advancement = manager.get(new Identifier("minecraft:story/mine_diamond"));
            Advancement netherite_Advancement = manager.get(new Identifier("minecraft:nether/netherite_armor"));
            Advancement ancientdebris_Advancement = manager.get(new Identifier("minecraft:nether/obtain_ancient_debris"));
            Advancement blazerod_Advancement = manager.get(new Identifier("minecraft:nether/obtain_blaze_rod"));


            if (nether_Advancement != null || diamond_Advancement != null || netherite_Advancement != null ||
            ancientdebris_Advancement != null || blazerod_Advancement != null)
            {
                return true;
            }
        }

        return false;
    }

    @Override
    public void tick(ServerWorld serverWorld) {
        LivingEntity target = this.mob.getTarget();

        if (target == null) {
            return;
        }

        if (attribute_changed && target.getHealth() <= 6.0)
        {
            this.mob.despawn();
            return;
        }

        /*
         * Performs a server side or client side check and adds the modifiers.
         */
        if (!(attribute_changed) && !(world.isClient))
        {
            if (server_PlayerHasAdvancements(target))
            {
                mob.getAttributeInstance(EntityAttributes.GENERIC_ATTACK_DAMAGE).addPersistentModifier(attack_DamageModifier);
                mob.getAttributeInstance(EntityAttributes.GENERIC_ATTACK_SPEED).addPersistentModifier(attack_SpeedModifier);
            }

            attribute_changed = true;
        }
        else
        {
            if (client_PlayerHasAdvancements())
            {
                mob.getAttributeInstance(EntityAttributes.GENERIC_ATTACK_DAMAGE).addPersistentModifier(attack_DamageModifier);
                mob.getAttributeInstance(EntityAttributes.GENERIC_ATTACK_SPEED).addPersistentModifier(attack_SpeedModifier);
            }

            attribute_changed = true;
        }

        this.mob.breakBlocksAround();

        if (serverWorld.getGameRules().getBoolean(ModGamerules.MAN_GIVE_DARKNESS_EFFECT)) {
            this.mob.addEffectToClosePlayers(serverWorld, TheManStatusEffects.DARKNESS);
            this.mob.addEffectToClosePlayers(serverWorld, TheManStatusEffects.NIGHT_VISION);
        }
        if (serverWorld.getGameRules().getBoolean(ModGamerules.MAN_GIVE_SPEED_EFFECT)) {
            this.mob.addEffectToClosePlayers(serverWorld, TheManStatusEffects.SPEED);
        }

        this.mob.getLookControl().lookAt(target,30f,30f);
        this.mob.moveTo(target,1.0);

        if (--this.lungeCooldown <= 0L) {
            this.lungeCooldown = Util.secToTick(LUNGE_COOLDOWN);
            if (Math.random() < LUNGE_CHANCE) {
                this.mob.setLunging(false);
                this.mob.lunge(target,0.6);
            }
        }

        if (this.mob.distanceTo(target) > 15 && --this.spitCooldown <= 0L) {
            this.spitCooldown = Util.secToTick(SPIT_COOLDOWN);
            if (Math.random() < SPIT_CHANCE) {
                this.mob.spitAt(target);
            }
        }

        if (--this.hallucinationCooldown <= 0L) {
            this.hallucinationCooldown = Util.secToTick(HALLUCINATION_COOLDOWN);
            if (Math.random() < HALLUCINATION_CHANCE) {
                this.mob.spawnHallucinations();
            }
        }

        for (ServerPlayerEntity player : serverWorld.getPlayers(TheManPredicates.TARGET_PREDICATE)) {
            if (player.isInRange(this.mob, TheManEntity.MAN_CHASE_DISTANCE)) {
                if (player.isSleeping()) {
                    player.wakeUp();
                }

                player.getHungerManager().add(1,1);
                // Commented this cuz the guns dont work if a player is sprinting
                // player.setSprinting(true);
            }
        }

        this.mob.attack(target);
    }
}