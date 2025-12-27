package com.minecraft.gancity.ai;

import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.ai.goal.Goal;
import net.minecraft.world.entity.projectile.Arrow;
import net.minecraft.world.entity.projectile.ThrownTrident;
import net.minecraft.world.item.BowItem;
import net.minecraft.world.item.CrossbowItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;

import java.util.EnumSet;

/**
 * A standalone ranged-weapon goal (Forge-side) that enables non-ranged mobs (e.g., zombies)
 * to use bows/crossbows/tridents as actual ranged weapons.
 *
 * This is intentionally not tied to Mixins or ML initialization so it can run in reduced-feature
 * mode and in environments where mixin goal injection may be unreliable.
 */
@SuppressWarnings({"null", "unused"})
public final class GenericRangedWeaponGoal extends Goal {
    private final Mob mob;
    private final double speedModifier;

    private LivingEntity target;
    private int attackCooldownTicks = 0;

    private static final int MIN_COOLDOWN_TICKS = 25;
    private static final int MAX_COOLDOWN_TICKS = 45;

    private static final double PREFERRED_MIN_DISTANCE_SQR = 36.0;  // 6 blocks
    private static final double PREFERRED_MAX_DISTANCE_SQR = 144.0; // 12 blocks

    public GenericRangedWeaponGoal(Mob mob, double speedModifier) {
        this.mob = mob;
        this.speedModifier = speedModifier;
        this.setFlags(EnumSet.of(Goal.Flag.MOVE, Goal.Flag.LOOK));
    }

    private boolean isHoldingSupportedRangedWeapon() {
        ItemStack main = mob.getMainHandItem();
        return main.getItem() instanceof BowItem
            || main.getItem() instanceof CrossbowItem
            || main.is(Items.BOW)
            || main.is(Items.CROSSBOW)
            || main.is(Items.TRIDENT);
    }

    private boolean isHoldingCrossbow() {
        ItemStack main = mob.getMainHandItem();
        return main.getItem() instanceof CrossbowItem || main.is(Items.CROSSBOW);
    }

    private boolean isHoldingTrident() {
        return mob.getMainHandItem().is(Items.TRIDENT);
    }

    @Override
    public boolean canUse() {
        LivingEntity t = mob.getTarget();
        if (t == null || !t.isAlive()) return false;
        if (!isHoldingSupportedRangedWeapon()) return false;
        this.target = t;
        return true;
    }

    @Override
    public boolean canContinueToUse() {
        if (target == null || !target.isAlive()) return false;
        return isHoldingSupportedRangedWeapon();
    }

    @Override
    public void start() {
        attackCooldownTicks = 0;
    }

    @Override
    public void stop() {
        this.target = null;
        mob.getNavigation().stop();
    }

    @Override
    public void tick() {
        if (target == null) return;

        mob.getLookControl().setLookAt(target, 30.0F, 30.0F);

        double distSqr = mob.distanceToSqr(target);
        if (distSqr < PREFERRED_MIN_DISTANCE_SQR) {
            Vec3 away = mob.position().subtract(target.position());
            if (away.lengthSqr() < 1.0E-4) {
                away = new Vec3(1, 0, 0);
            }
            Vec3 retreatPos = mob.position().add(away.normalize().scale(6.0));
            mob.getNavigation().moveTo(retreatPos.x, retreatPos.y, retreatPos.z, speedModifier);
        } else if (distSqr > PREFERRED_MAX_DISTANCE_SQR) {
            mob.getNavigation().moveTo(target, speedModifier);
        } else {
            mob.getNavigation().stop();
        }

        if (attackCooldownTicks > 0) {
            attackCooldownTicks--;
            return;
        }

        if (!mob.getSensing().hasLineOfSight(target)) {
            return;
        }

        Level level = mob.level();
        if (level.isClientSide) {
            return;
        }

        double dx = target.getX() - mob.getX();
        double dy = target.getEyeY() - (mob.getEyeY() + 0.1);
        double dz = target.getZ() - mob.getZ();

        if (isHoldingTrident()) {
            ThrownTrident trident = new ThrownTrident(level, mob, mob.getMainHandItem().copy());
            trident.setPos(mob.getX(), mob.getEyeY() - 0.1, mob.getZ());
            trident.shoot(dx, dy, dz, 1.6F, 14 - level.getDifficulty().getId() * 4);
            level.addFreshEntity(trident);
            level.playSound(null, mob.getX(), mob.getY(), mob.getZ(), SoundEvents.DROWNED_SHOOT, SoundSource.HOSTILE, 1.0F, 1.0F);
        } else {
            Arrow arrow = new Arrow(level, mob);
            arrow.setPos(mob.getX(), mob.getEyeY() - 0.1, mob.getZ());
            arrow.setBaseDamage(isHoldingCrossbow() ? 3.0D : 2.0D);
            arrow.shoot(dx, dy, dz, isHoldingCrossbow() ? 1.9F : 1.6F, 14 - level.getDifficulty().getId() * 4);
            level.addFreshEntity(arrow);

            level.playSound(null, mob.getX(), mob.getY(), mob.getZ(),
                isHoldingCrossbow() ? SoundEvents.CROSSBOW_SHOOT : SoundEvents.SKELETON_SHOOT,
                SoundSource.HOSTILE, 1.0F, 1.0F);
        }

        attackCooldownTicks = MIN_COOLDOWN_TICKS + mob.getRandom().nextInt(MAX_COOLDOWN_TICKS - MIN_COOLDOWN_TICKS + 1);
    }
}
