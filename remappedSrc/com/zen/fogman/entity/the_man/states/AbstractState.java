package com.zen.fogman.common.entity.the_man.states;

import com.zen.fogman.common.entity.the_man.TheManEntity;
import net.minecraft.server.world.ServerWorld;

public abstract class AbstractState {
    public TheManEntity mob;

    public AbstractState(TheManEntity entity) {
        this.mob = entity;
    }

    public void tick(ServerWorld serverWorld) {

    }
}
