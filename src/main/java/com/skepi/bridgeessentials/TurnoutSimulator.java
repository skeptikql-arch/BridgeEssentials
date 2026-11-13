package com.skepi.bridgeessentials;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

import com.mojang.authlib.GameProfile;
import net.minecraft.block.Block;
import net.minecraft.entity.SharedMonsterAttributes;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.potion.Potion;
import net.minecraft.potion.PotionEffect;
import net.minecraft.util.BlockPos;
import net.minecraft.util.DamageSource;

/**
 * A detached 1.8.9 player used only for short movement forecasts.
 *
 * The forecast deliberately uses EntityPlayer's vanilla update / movement implementation
 * rather than approximating blocks-per-tick. It follows the detached-EntityPlayer approach
 * used by the supplied Parkour Calculator 1.8.9 simulator: the entity is never inserted into
 * the world, block collision is bypassed with no-clip on every movement tick, and nearby-entity
 * collisions are disabled. This produces a free-flight projection.
 */
final class TurnoutSimulator extends EntityPlayer {
    private final boolean forwardKey;
    private final boolean backKey;
    private final boolean leftKey;
    private final boolean rightKey;
    private final boolean jumpKey;
    private final boolean sneakKey;
    private final boolean sprintKey;
    private final boolean usingItem;
    private final boolean riding;

    private boolean initializing = true;
    private boolean previousSneak;
    private float previousMoveForward;
    private int sprintToggleTimer;
    private int sprintingTicksLeft;

    TurnoutSimulator(EntityPlayer reference,
                     boolean forwardKey, boolean backKey,
                     boolean leftKey, boolean rightKey,
                     boolean jumpKey, boolean sneakKey, boolean sprintKey) {
        super(reference.worldObj, reference.getGameProfile());

        this.forwardKey = forwardKey;
        this.backKey = backKey;
        this.leftKey = leftKey;
        this.rightKey = rightKey;
        this.jumpKey = jumpKey;
        this.sneakKey = sneakKey;
        this.sprintKey = sprintKey;
        this.usingItem = reference.isUsingItem();
        this.riding = reference.isRiding();

        // Match the supplied 1.8.9 simulator's detached-player initialization.
        this.noClip = true;
        this.setSize(0.6F, 1.8F);
        this.setPosition(reference.posX, reference.posY, reference.posZ);
        this.onUpdate();
        this.onUpdate();
        this.initializing = false;

        // Seed the forecast from the real player's exact current movement state.
        this.noClip = true;
        this.setSize(0.6F, 1.8F);
        this.setPosition(reference.posX, reference.posY, reference.posZ);
        this.motionX = reference.motionX;
        this.motionY = reference.motionY;
        this.motionZ = reference.motionZ;
        this.rotationYaw = reference.rotationYaw;
        this.rotationPitch = reference.rotationPitch;
        this.onGround = reference.onGround;
        this.isCollidedHorizontally = readBooleanField(
                reference, false, "isCollidedHorizontally", "field_70123_F"
        );
        this.fallDistance = reference.fallDistance;
        this.setSneaking(reference.isSneaking());
        this.setSprinting(reference.isSprinting());
        this.capabilities.allowFlying = reference.capabilities.allowFlying;
        this.getFoodStats().setFoodLevel(reference.getFoodStats().getFoodLevel());
        this.getFoodStats().setFoodSaturationLevel(
                reference.getFoodStats().getSaturationLevel()
        );

        writeIntField(this,
                readIntField(reference, 0, "jumpTicks", "field_70773_bE"),
                "jumpTicks", "field_70773_bE");
        this.jumpMovementFactor = readFloatField(
                reference, reference.isSprinting() ? 0.026F : 0.02F,
                "jumpMovementFactor", "field_70747_aH"
        );
        this.isInWeb = readBooleanField(reference, false, "isInWeb", "field_70134_J");

        // EntityPlayerSP owns the sprint timers. Read both MCP names (dev) and SRG names
        // (reobfuscated client) so the simulation starts with the same double-tap/sprint state.
        this.sprintToggleTimer = readIntField(
                reference, 0, "sprintToggleTimer", "field_71156_d"
        );
        this.sprintingTicksLeft = readIntField(
                reference, reference.isSprinting() ? 600 : 0,
                "sprintingTicksLeft", "field_71157_e"
        );
        this.previousSneak = reference.isSneaking();
        this.previousMoveForward = readFloatField(
                reference,
                (forwardKey == backKey ? 0.0F : (forwardKey ? 1.0F : -1.0F)),
                "moveForward", "field_70701_bs"
        );

        this.getEntityAttribute(SharedMonsterAttributes.movementSpeed).setBaseValue(
                reference.getEntityAttribute(SharedMonsterAttributes.movementSpeed).getBaseValue()
        );
        copyActivePotionEffects(reference);
    }

    /**
     * Advance the detached player in free flight while keeping sampled inputs and view angles
     * fixed. noClip is reasserted for every tick so no block collision can alter the path.
     */
    void simulate(int ticks, float fixedYaw, float fixedPitch) {
        for (int i = 0; i < ticks; i++) {
            this.noClip = true;
            // A collision detected before the forecast must not affect sprint or travel.
            this.isCollided = false;
            this.isCollidedHorizontally = false;
            this.isCollidedVertically = false;
            this.rotationYaw = fixedYaw;
            this.rotationPitch = fixedPitch;
            this.onUpdate();
            this.noClip = true;
        }
    }

    /** Force vanilla movement to use the no-collision branch for the whole forecast. */
    @Override
    public void moveEntity(double x, double y, double z) {
        this.noClip = true;
        super.moveEntity(x, y, z);
    }

    @Override
    public void onLivingUpdate() {
        // Keep the detached player in free-flight mode throughout vanilla's movement update.
        this.noClip = true;
        if (!initializing) {
            applyFrozenInput();
        }
        super.onLivingUpdate();
    }

    private void applyFrozenInput() {
        if (sprintToggleTimer > 0) {
            sprintToggleTimer--;
        }
        if (sprintingTicksLeft > 0) {
            sprintingTicksLeft--;
            if (sprintingTicksLeft == 0) {
                this.setSprinting(false);
            }
        }

        float moveForward = axis(forwardKey, backKey);
        float moveStrafe = axis(leftKey, rightKey);
        if (sneakKey) {
            moveForward *= 0.3F;
            moveStrafe *= 0.3F;
        }

        if (usingItem && !riding) {
            moveStrafe *= 0.2F;
            moveForward *= 0.2F;
            sprintToggleTimer = 0;
        }

        boolean canSprint = this.getFoodStats().getFoodLevel() > 6
                || this.capabilities.allowFlying;
        boolean blindness = this.isPotionActive(Potion.blindness);
        boolean wasMovingForward = previousMoveForward >= 0.8F;

        if (this.onGround
                && !previousSneak
                && !wasMovingForward
                && moveForward >= 0.8F
                && !this.isSprinting()
                && canSprint
                && !usingItem
                && !blindness) {
            if (sprintToggleTimer <= 0 && !sprintKey) {
                sprintToggleTimer = 7;
            } else {
                this.setSprinting(true);
                sprintingTicksLeft = 600;
            }
        }

        if (!this.isSprinting()
                && moveForward >= 0.8F
                && canSprint
                && !usingItem
                && !blindness
                && sprintKey) {
            this.setSprinting(true);
            sprintingTicksLeft = 600;
        }

        if (this.isSprinting()
                && (moveForward < 0.8F || this.isCollidedHorizontally || !canSprint)) {
            this.setSprinting(false);
            sprintingTicksLeft = 0;
        }

        this.setSneaking(sneakKey);
        this.moveForward = moveForward;
        this.moveStrafing = moveStrafe;
        this.isJumping = jumpKey;

        previousSneak = sneakKey;
        previousMoveForward = moveForward;
    }

    private static float axis(boolean positive, boolean negative) {
        if (positive == negative) {
            return 0.0F;
        }
        return positive ? 1.0F : -1.0F;
    }

    private void copyActivePotionEffects(EntityPlayer reference) {
        Collection<PotionEffect> effects =
                new ArrayList<PotionEffect>(reference.getActivePotionEffects());
        for (PotionEffect effect : effects) {
            this.addPotionEffect(new PotionEffect(
                    effect.getPotionID(), effect.getDuration(), effect.getAmplifier(), false, false
            ));
        }
    }

    @Override
    protected void onNewPotionEffect(PotionEffect effect) {
        Potion type = getPotion(effect);
        if (type != null) {
            type.applyAttributesModifiersToEntity(
                    this, this.getAttributeMap(), effect.getAmplifier()
            );
        }
    }

    @Override
    protected void onChangedPotionEffect(PotionEffect effect, boolean reapply) {
        if (reapply) {
            Potion type = getPotion(effect);
            if (type != null) {
                type.removeAttributesModifiersFromEntity(
                        this, this.getAttributeMap(), effect.getAmplifier()
                );
                type.applyAttributesModifiersToEntity(
                        this, this.getAttributeMap(), effect.getAmplifier()
                );
            }
        }
    }

    @Override
    protected void onFinishedPotionEffect(PotionEffect effect) {
        Potion type = getPotion(effect);
        if (type != null) {
            type.removeAttributesModifiersFromEntity(
                    this, this.getAttributeMap(), effect.getAmplifier()
            );
        }
    }

    private static Potion getPotion(PotionEffect effect) {
        int id = effect.getPotionID();
        if (id < 0 || id >= Potion.potionTypes.length) {
            return null;
        }
        return Potion.potionTypes[id];
    }

    @Override
    public void addExhaustion(float amount) {
        // Forecast ticks must not change the real player's food/exhaustion.
    }

    @Override
    public boolean attackEntityFrom(DamageSource source, float amount) {
        return false;
    }

    @Override
    protected void collideWithNearbyEntities() {
        // The detached forecast entity is never part of world entity collision.
    }

    @Override
    public void spawnRunningParticles() {
    }

    @Override
    protected void resetHeight() {
    }

    @Override
    public void playSound(String name, float volume, float pitch) {
    }

    @Override
    protected void updateFallState(double y, boolean onGroundIn, Block blockIn, BlockPos pos) {
        if (!this.isInWater()) {
            this.handleWaterMovement();
        }
        if (onGroundIn) {
            if (this.fallDistance > 0.0F) {
                if (blockIn != null) {
                    blockIn.onFallenUpon(this.worldObj, pos, this, this.fallDistance);
                } else {
                    this.fall(this.fallDistance, 1.0F);
                }
            }
            this.fallDistance = 0.0F;
        } else if (y < 0.0D) {
            this.fallDistance = (float) ((double) this.fallDistance - y);
        }
    }

    @Override
    protected void kill() {
        // A forecast falling out of the world must not affect the real player.
    }

    @Override
    public boolean isSpectator() {
        return false;
    }

    @Override
    public boolean isServerWorld() {
        // EntityLivingBase gates its normal travel path on this check.
        return true;
    }

    private static void writeIntField(Object target, int value, String... names) {
        Class<?> type = target.getClass();
        while (type != null) {
            for (String name : names) {
                try {
                    Field field = type.getDeclaredField(name);
                    field.setAccessible(true);
                    field.setInt(target, value);
                    return;
                } catch (NoSuchFieldException ignored) {
                    // Try the alternate MCP / SRG name, then continue up the hierarchy.
                } catch (Exception ignored) {
                    // Keep the simulator usable if reflective access is unavailable.
                }
            }
            type = type.getSuperclass();
        }
    }

    private static int readIntField(Object target, int fallback, String... names) {
        Object value = readField(target, names);
        return value instanceof Number ? ((Number) value).intValue() : fallback;
    }

    private static float readFloatField(Object target, float fallback, String... names) {
        Object value = readField(target, names);
        return value instanceof Number ? ((Number) value).floatValue() : fallback;
    }

    private static boolean readBooleanField(Object target, boolean fallback, String... names) {
        Object value = readField(target, names);
        return value instanceof Boolean ? ((Boolean) value).booleanValue() : fallback;
    }

    private static Object readField(Object target, String... names) {
        Class<?> type = target.getClass();
        while (type != null) {
            for (String name : names) {
                try {
                    Field field = type.getDeclaredField(name);
                    field.setAccessible(true);
                    return field.get(target);
                } catch (NoSuchFieldException ignored) {
                    // Try the alternate MCP / SRG name, then continue up the hierarchy.
                } catch (Exception ignored) {
                    // Keep the safe fallback if access is denied or a field is unavailable.
                }
            }
            type = type.getSuperclass();
        }
        return null;
    }
}
