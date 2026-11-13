package com.skepi.bridgeessentials;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

import net.minecraft.block.Block;
import net.minecraft.block.BlockBed;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.FontRenderer;
import net.minecraft.client.gui.Gui;
import net.minecraft.client.gui.ScaledResolution;
import net.minecraft.client.renderer.GlStateManager;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.init.Blocks;
import net.minecraft.item.ItemBlock;
import net.minecraft.item.ItemStack;
import net.minecraft.util.AxisAlignedBB;
import net.minecraft.util.BlockPos;
import net.minecraft.util.MathHelper;
import net.minecraft.world.World;
import net.minecraftforge.event.entity.player.PlayerInteractEvent;
import net.minecraftforge.event.world.BlockEvent;
import net.minecraftforge.fml.common.eventhandler.SubscribeEvent;
import net.minecraftforge.fml.common.gameevent.TickEvent;

/** Tracks bridging attempts and renders their timer/results in the client HUD. */
public class BridgeEssentialsListener {
    private static final double YAW_DISPLAY_SCALE = 10000.0D;
    private static final double PITCH_DISPLAY_SCALE = 10.0D;
    private static final int TURNOUT_FACING_CHECK_TICKS = 7;
    private static final int TURNOUT_PROJECTION_TICKS = 7;
    private static final double TIMER_STEP_SECONDS = 0.050D;
    private static final double FALL_DISTANCE_TO_FAIL = 3.0D;
    private static final double TELEPORT_DISTANCE_SQUARED_TO_FAIL = 100.0D;
    private static final int PENDING_PLACEMENT_MAX_AGE = 4;
    // Keep a landing alive while a same-tick client block update is still being verified.
    private static final int PENDING_LANDING_MAX_AGE = 4;

    private enum AttemptResult {
        NONE,
        FAILED,
        COMPLETED
    }

    private enum FailedLandingType {
        NONE,
        TAP,
        FLAT
    }

    private static final class PendingPlacement {
        private final BlockPos position;
        private final net.minecraft.block.state.IBlockState beforeState;
        private final int createdAtTick;
        // ClientTick.END tick on which the click/placement actually occurred. Kept separately
        // from createdAtTick because verification can happen on a later tick.
        private final int placementClientTick;
        private final float placementYaw;
        private final float placementPitch;

        private PendingPlacement(BlockPos position,
                                 net.minecraft.block.state.IBlockState beforeState,
                                 int createdAtTick, int placementClientTick,
                                 float placementYaw, float placementPitch) {
            this.position = position;
            this.beforeState = beforeState;
            this.createdAtTick = createdAtTick;
            this.placementClientTick = placementClientTick;
            this.placementYaw = placementYaw;
            this.placementPitch = placementPitch;
        }
    }

    /**
     * Snapshot of an airborne-to-ground transition. A block placement can be confirmed by the
     * client one or more ticks after the interaction event, so do not throw the landing away
     * just because its support has not entered ownPlacedBlocks yet. The saved box/coordinates
     * let us resolve the same landing position later, even if the player has already jumped off.
     */
    private static final class PendingLanding {
        private final int clientTick;
        private final double posX;
        private final double posZ;
        private final AxisAlignedBB playerBox;
        private final double previousTickCoordinate;
        private final boolean wasFacingOpposite;
        private final boolean sprinting;
        // Only the first same-height landing (the opening flat) ignores placements first
        // registered on that landing tick when choosing its support/baseline.
        private final boolean excludeLandingTickPlacements;
        // Once selected, the exact landing support cannot be replaced by a later block.
        private BlockPos lockedSupportPosition;
        // True only when the locked support is a block placed on the landing tick that sits
        // directly under the player's centre. Nothing else can outrank it, so no waiting.
        private boolean lockedStable;
        // Live (unfiltered) support scan on the landing tick; last-resort fallback only.
        private BlockPos liveScanSupport;

        private PendingLanding(EntityPlayer player, int clientTick,
                               double previousTickCoordinate, boolean wasFacingOpposite,
                               boolean excludeLandingTickPlacements) {
            this.clientTick = clientTick;
            this.posX = player.posX;
            this.posZ = player.posZ;
            AxisAlignedBB box = player.getEntityBoundingBox();
            this.playerBox = new AxisAlignedBB(
                    box.minX, box.minY, box.minZ, box.maxX, box.maxY, box.maxZ
            );
            this.previousTickCoordinate = previousTickCoordinate;
            this.wasFacingOpposite = wasFacingOpposite;
            this.sprinting = player.isSprinting();
            this.excludeLandingTickPlacements = excludeLandingTickPlacements;
            this.lockedSupportPosition = null;
            this.lockedStable = false;
            this.liveScanSupport = null;
        }
    }

    private static final class HudRow {
        private final String label;
        private final String value;
        private final int labelColor;
        private final int valueColor;
        private final String suffix;
        private final int suffixColor;
        /** A divider line instead of text. */
        private final boolean divider;

        private static HudRow divider() {
            return new HudRow(true);
        }

        private HudRow(boolean divider) {
            this.label = "";
            this.value = "";
            this.labelColor = 0;
            this.valueColor = 0;
            this.suffix = "";
            this.suffixColor = 0;
            this.divider = divider;
        }

        private HudRow(String label, String value, int labelColor, int valueColor) {
            this(label, value, labelColor, valueColor, "", valueColor);
        }

        private HudRow(String label, String value, int labelColor, int valueColor,
                       String suffix, int suffixColor) {
            this.label = label;
            this.value = value;
            this.labelColor = labelColor;
            this.valueColor = valueColor;
            this.suffix = suffix;
            this.suffixColor = suffixColor;
            this.divider = false;
        }
    }

    private static final class LandingResult {
        private String label;
        private String coordinate;
        private int labelColor;
        private int coordinateColor;
        private String difference;
        private int differenceColor;
        private String differenceSeparator = " ";
        private String jumpoverOffset;
        private int jumpoverOffsetColor;
        private boolean showJumpoverOffset = true;
        // Numeric jumpover offset of this landing (the string above is the display form).
        private double jumpoverOffsetValue;
        // True when the row before this Tap was a 4b Tap / 4b Speed: this Tap's own Speed then
        // can never be a 4b Speed (no two in a row).
        private boolean previousRowWas4b;
        private String turnoutDifference;
        private int turnoutDifferenceColor = 0xFF9AA8BA;
        private String extraLine;
        private int extraLineColor = BridgeColors.EXTRA_LINE;
        private String turnout;
        // --- numeric data for the personal-best statistics (see BridgeBests); display-neutral ---
        private int landingNumber = -1;
        private boolean tap;
        private double statCoordinate;
        private boolean statHasGain;
        private long statGainThousandths;
        private boolean statHasPrev;
        private double statPrevCoordinate;
        private double flatTimingSeconds = Double.NaN;
        private int speedTicks;
        private boolean statsRecorded;

        private LandingResult(String label, String coordinate,
                              int labelColor, int coordinateColor,
                              String difference, int differenceColor,
                              String jumpoverOffset, int jumpoverOffsetColor) {
            this.label = label;
            this.coordinate = coordinate;
            this.labelColor = labelColor;
            this.coordinateColor = coordinateColor;
            this.difference = difference;
            this.differenceColor = differenceColor;
            this.jumpoverOffset = jumpoverOffset;
            this.jumpoverOffsetColor = jumpoverOffsetColor;
        }
    }

    /** A landing waits for the next takeoff so its ground-delay technique can be identified. */
    private static final class PendingAdvancedLanding {
        private final LandingResult result;
        private final boolean openingFlat;
        private final boolean tap;
        private final int startClientTick;
        private final int forwardBlocks;
        // For opening flats this is the measured first-placement -> landing tick gap.
        // For later 4b flats it is the standard 3-tick (1.05) base.
        private final int baseFlatElapsedTicks;
        private final boolean hasPreviousLandingFraction;
        private final double previousLandingFraction;
        // Absolute directional coordinate of the landing before this one (for block-adjusted gains).
        private double previousLandingCoordinate;
        private int groundedTicks;
        private double lastGroundCoordinate;
        private double previousGroundCoordinate;
        private boolean sprintingOnLastGroundTick;

        private PendingAdvancedLanding(LandingResult result, boolean openingFlat, boolean tap,
                                       int startClientTick, int forwardBlocks, int baseFlatElapsedTicks,
                                       boolean hasPreviousLandingFraction,
                                       double previousLandingFraction,
                                       double landingCoordinate, double previousTickCoordinate,
                                       boolean sprinting) {
            this.result = result;
            this.openingFlat = openingFlat;
            this.tap = tap;
            this.startClientTick = startClientTick;
            this.forwardBlocks = forwardBlocks;
            this.baseFlatElapsedTicks = baseFlatElapsedTicks;
            this.hasPreviousLandingFraction = hasPreviousLandingFraction;
            this.previousLandingFraction = previousLandingFraction;
            this.groundedTicks = 0;
            this.lastGroundCoordinate = landingCoordinate;
            this.previousGroundCoordinate = previousTickCoordinate;
            this.sprintingOnLastGroundTick = sprinting;
        }
    }

    /** A short forecast is attached to a landing and starts only when the player jumps from it. */
    private static final class PendingTurnout {
        private final LandingResult result;
        private final boolean openingFlat;
        private final String direction;
        private final int takeoffClientTick;
        private final double referenceLandingFraction;
        private int verifiedFacingTicks;

        private PendingTurnout(LandingResult result, boolean openingFlat,
                               String direction, int takeoffClientTick,
                               double referenceLandingFraction) {
            this.result = result;
            this.openingFlat = openingFlat;
            this.direction = direction;
            this.takeoffClientTick = takeoffClientTick;
            this.referenceLandingFraction = referenceLandingFraction;
            this.verifiedFacingTicks = 0;
        }
    }

    // Direction detector / attempt lifecycle.
    private String lastDetectedDirection;
    // Most recently detected alignment, retained through a respawn until the next block placement.
    private String detectedDirection;
    // Direction is frozen for the duration of a timed attempt so coordinates stay consistent.
    private String attemptDirection;
    // Cardinal directions observed while preparing the next attempt. If the player turns
    // around before placing, this lets us retain the intended travel direction.
    private final Set<String> preparedAttemptDirections = new HashSet<String>();
    // Opening 1.xx-flat timing is only enabled when the first block is placed facing opposite
    // the selected travel direction. Tap/flat tracking remains enabled either way.
    private boolean openingFlatDetectionEnabled;
    // The first own-block landing is always the baseline for later taps/flats, even when the
    // opening 1.05/1.1 timing display has been disabled for this attempt.
    private boolean hasOpeningLandingBaseline;
    private double openingLandingFraction;
    // Horizontal cardinal direction sampled at the end of the previous client tick.
    private String previousFacingDirection;
    private int attempts;
    private boolean attemptActive;
    private boolean timerStarted;
    private int timerTicks;
    private int firstPlacedBlockClientTick;
    private double attemptStartY;
    // Exact starting ground-surface Y. Retain the last grounded Y across ticks so a first
    // placement made several airborne ticks after takeoff still uses the real starting level.
    private double attemptStartGroundY;
    private boolean hasLastGroundedPlayerY;
    private double lastGroundedPlayerY;
    private AttemptResult result = AttemptResult.NONE;

    // Position samples are kept as a two-tick history for exact Plate / Plate (-1t) values.
    private boolean hasPreviousPosition;
    private double previousPosX;
    private double previousPosY;
    private double previousPosZ;
    // Client-side verification fallback for block placements.
    private final List<PendingPlacement> pendingPlacements = new ArrayList<PendingPlacement>();
    private PendingLanding pendingLanding;
    private int clientTickCounter;
    private boolean insideClientTickEnd;

    // Every successful block placed by the local player is tracked for later tap/flat landings.
    private final Set<BlockPos> ownPlacedBlocks = new HashSet<BlockPos>();
    // Client tick (normalized to the ClientTick.END counter) on which each own block was placed.
    // Lets landing detection tell a block that was already there from one placed on the landing tick.
    private final Map<BlockPos, Integer> ownPlacedAtTick = new HashMap<BlockPos, Integer>();
    // Every successfully placed block can support the opening Flat, including long 5+ block runs.
    private final Set<BlockPos> openingFlatBlocks = new HashSet<BlockPos>();
    private boolean attemptWasAirborne;
    // A recognized opening flat/tap/flat can arm one jump-off sequence.
    private boolean previousPlayerOnGround;
    private boolean canStartFailedJump;
    private boolean failedJumpSequenceActive;
    private boolean waitingForOppositeFacing;
    private int failedJumpStartClientTick;
    // Y position saved at the end of the grounded tick immediately before takeoff.
    private double failedJumpPreviousGroundY;
    private boolean hasFailedTapSampleCoordinate;
    private double failedTapSampleCoordinate;
    private boolean hasFailedTapSamplePreviousCoordinate;
    private double failedTapSamplePreviousCoordinate;
    private boolean hasFailedFlatSampleCoordinate;
    private double failedFlatSampleCoordinate;
    private boolean hasFailedFlatSamplePreviousCoordinate;
    private double failedFlatSamplePreviousCoordinate;
    // Once the opposite-facing tick classifies the jump, wait for takeoff+8 / takeoff+11
    // and for the player's Y to fall below the previous grounded tick's Y.
    private FailedLandingType pendingFailedLandingType = FailedLandingType.NONE;
    private int pendingFailedLandingSampleTick;
    private boolean hasOpeningFlatResult;
    private String openingFlatType;
    private double openingFlatCoordinate;
    private double openingFlatJumpoverOffset;
    private boolean openingFlatAwaitingAdvancedResolution;
    // True only when the immediately preceding flat was left after one or more extra grounded ticks.
    private boolean previousFlatWasDelayed;
    // Tap and Flat coordinate changes share one chronological baseline.
    private boolean hasPreviousLanding;
    private int previousLandingY;
    private int tapCount;
    private int sameLevelFlatCount;
    private boolean hasUnifiedPreviousLandingCoordinate;
    private double unifiedPreviousLandingFraction;
    private double unifiedPreviousLandingCoordinate;
    // Support-block coordinate (along the attempt axis) of the MOST RECENT own-block landing,
    // whether or not that landing produced a Tap/Flat row. Block advances are always measured
    // from here. It used to advance only when a row was produced, so one short or "lost
    // momentum" landing left the baseline stale and every later landing was measured from the
    // wrong block (inflated Jumpover offsets, or no further rows at all).
    private int lastLandingSupportBlockCoordinate;
    private boolean hasLastLandingSupportBlock;
    private boolean hasUnifiedPreviousJumpoverOffset;
    private double unifiedPreviousJumpoverOffset;
    // The most recent visible Tap/Flat/Failed row (or the opening flat), used to decide whether a
    // positive-offset Tap that follows a negative-offset row is promoted to a "4b Tap".
    private LandingResult previousRowResult;
    private boolean previousRowIsOpeningFlat;
    private PendingAdvancedLanding pendingAdvancedLanding;
    // --- diagnostics only (never affect tracking) ---
    // Support block the player stood on at the end of the previous grounded tick; lets the log show
    // walking onto a different own block, which is not an airborne landing.
    private int groundedSupportBlockCoordinate;
    private boolean hasGroundedSupportBlock;
    private BlockPos lastGroundedSupportPosition;
    // Support block the player stood on when this flight began (takeoff), for jump-length diagnostics.
    private int takeoffSupportBlockCoordinate;
    private boolean hasTakeoffSupportBlock;
    // Which rule in findOwnPlacedSupport produced the most recent candidate.
    private String lastSupportRule = "none";
    // Why shouldFailAttempt last returned true.
    private String failReason = "";
    private int landingCounter;
    private final List<String> landingTable = new ArrayList<String>();
    // A positive-offset flat jumped immediately is deliberately left unclassified; the next
    // tap is then evaluated normally using its own offset and the shared landing history.
    private int attemptStartBlockCoordinate;
    private boolean hasAttemptStartBlockCoordinate;
    private final List<LandingResult> landingResults = new ArrayList<LandingResult>();

    // Personal-best statistics for the current attempt (fed to BridgeBests once rows are final).
    private final BridgeBests.AttemptTracker bestTracker = new BridgeBests.AttemptTracker();
    private double openingFlatTimingSeconds;
    private double openingFlatStatCoordinate;
    private int openingFlatLandingNumber;
    private boolean openingStatsDone;

    // The most recent visible landing waits for takeoff before its seven-tick turnout check.
    private LandingResult turnoutLandingResult;
    private boolean turnoutLandingIsOpeningFlat;
    private boolean turnoutLandingAwaitingTakeoff;
    private double turnoutLandingReferenceFraction;
    private final List<PendingTurnout> pendingTurnouts = new ArrayList<PendingTurnout>();
    private String openingFlatTurnout;
    private String openingFlatTurnoutDifference;

    // Optional advanced display fields for the opening Flat 1 row.
    private String openingFlatDisplayCoordinate;
    private String openingFlatSuffix = " (n/a)";
    private int openingFlatSuffixColor = 0xFF9AA8BA;
    private String openingFlatExtraLine;

    // Captured at the plate trigger using the two position samples immediately before detection.
    private boolean hasPlateCoordinates;
    private double plateCoordinate;
    private double platePreviousTickCoordinate;

    // --- Jump Ticks: consecutive grounded ticks after a landing, shown when the next jump starts ---
    private int groundTicksSinceLanding;
    private boolean hasJumpTicks;
    private int jumpTicks;

    // --- Bed Click Timing ---
    private static final double BED_REACH = 4.5D;
    private static final int BED_SCAN_RADIUS = 8;
    private static final int BED_EARLY_CLICK_MAX_TICKS = 100;
    private static final int BED_FORGET_TICKS = 200;

    private static final class BedState {
        private boolean inReach;
        private int entryTick;
        private boolean resolved;
        private int earlyClickTick = Integer.MIN_VALUE;
        private int lastSeenTick;
    }

    private final Map<BlockPos, BedState> bedStates = new HashMap<BlockPos, BedState>();
    private final List<Object[]> pendingBedClicks = new ArrayList<Object[]>();
    private boolean hasBedClickTiming;
    private int bedClickTiming;
    // A click on a bed ends the running attempt (handled on the tracker's next attempt tick).
    private boolean bedClickEndsAttempt;
    private boolean previousAttackDown;
    private boolean previousUseDown;

    @SubscribeEvent
    public void onClientTick(TickEvent.ClientTickEvent event) {
        if (event.phase != TickEvent.Phase.END) {
            return;
        }

        clientTickCounter++;
        BridgeLog.setContext(clientTickCounter, attempts);
        insideClientTickEnd = true;
        Minecraft minecraft = Minecraft.getMinecraft();
        EntityPlayer player = minecraft.thePlayer;
        World world = minecraft.theWorld;
        BridgeBests.playtimeTick(player != null && world != null);

        if (player == null || world == null) {
            if (attemptActive && timerStarted) {
                finishFailedAttempt("player or world unavailable (respawn, disconnect or dimension change)");
            }
            // Keep the last direction and displayed result through respawn.
            hasPreviousPosition = false;
            previousFacingDirection = null;
            previousPlayerOnGround = false;
            hasLastGroundedPlayerY = false;
            canStartFailedJump = false;
            cancelFailedJumpSequence();
            pendingPlacements.clear();
            pendingLanding = null;
            ownPlacedBlocks.clear();
            ownPlacedAtTick.clear();
            pendingTurnouts.clear();
            clearTurnoutLandingAnchor();
            insideClientTickEnd = false;
            return;
        }

        updateAttemptDirection(player);
        processPendingPlacements(minecraft, world);
        updateBedClickTiming(minecraft, player, world);

        // Jump Ticks: ground ticks counted from the landing tick (0 = jumped on the very next
        // tick). Counted on every tick, attempt or not; the value itself is taken from the real
        // client-side jump event in onPlayerJump, not inferred from leaving the ground.
        if (player.onGround) {
            groundTicksSinceLanding = previousPlayerOnGround ? groundTicksSinceLanding + 1 : 0;
        }

        if (attemptActive && timerStarted) {
            // Retry a landing captured on an earlier tick after any delayed block updates have
            // been applied. This runs before the live support scan and takeoff handling below.
            processPendingLanding(world);
            // Scan the direction-aligned 3x3 support neighbourhood on EVERY active client tick.
            // The candidate is only consumed as a landing when an airborne-to-ground
            // transition occurs, but sampling continuously keeps edge support detection
            // synchronized with the player's changing position.
            BlockPos supportGridCandidate = findOwnPlacedSupport(world, player);
            if (BridgeLog.isPerTick()) {
                logTick(player, world, supportGridCandidate);
            }

            // One client tick is exactly 0.050 seconds at Minecraft's 20 TPS tick rate.
            timerTicks++;

            // Resolve pending Tap/Flat technique classifications. The opening Flat is always
            // held for at least one post-landing tick; later positive/negative-offset rules keep
            // their existing grounded-tick behavior.
            processPendingAdvancedLanding(player);

            // Capture the exact takeoff+8 and takeoff+11 coordinates while airborne.
            // The opposite-facing tick classifies the jump; landing before the target cancels it.
            captureFailedJumpSamples(player);
            processPendingFailedLanding(player);
            finalizeBestStats(false);

            // A real landing cancels any unresolved failed-jump sequence.
            if (player.onGround && failedJumpSequenceActive) {
                cancelFailedJumpSequence("player is on the ground again (landed before the failed result was shown)");
            }

            if (!player.onGround && previousPlayerOnGround) {
                hasTakeoffSupportBlock = hasGroundedSupportBlock;
                takeoffSupportBlockCoordinate = groundedSupportBlockCoordinate;
                if (BridgeLog.isEnabled()) {
                    logTakeoff(player);
                }
            }

            // Arm only when leaving a previously recognized own-block flat/tap while facing
            // along the attempt. Then wait until the player turns to face back toward the start.
            if (!player.onGround && previousPlayerOnGround && canStartFailedJump) {
                canStartFailedJump = false;
                String facingAtTakeoff = getHorizontalFacingDirection(player.rotationYaw);
                if (openingFlatDetectionEnabled && isSameDirection(facingAtTakeoff, attemptDirection)) {
                    startFailedJumpSequence(player);
                }
                // Turnout's seven-tick facing window begins after takeoff. It is independent
                // of the opposite-facing check used by the failed-jump detector.
                if (player.motionY > 0.0D) {
                    beginTurnoutForecastAtTakeoff();
                } else {
                    clearTurnoutLandingAnchor();
                }
            }
            if (!player.onGround && waitingForOppositeFacing) {
                String currentFacing = getHorizontalFacingDirection(player.rotationYaw);
                if (isOppositeDirection(currentFacing, attemptDirection)) {
                    waitingForOppositeFacing = false;
                    if (player.motionY > 0.0D) {
                        classifyFailedJump(player, FailedLandingType.TAP, 8);
                    } else if (player.motionY < 0.0D) {
                        classifyFailedJump(player, FailedLandingType.FLAT, 11);
                    } else {
                        cancelFailedJumpSequence("motionY == 0 on the opposite-facing tick");
                    }
                }
            }

            // Each airborne-to-ground transition is considered once. The support must be
            // one of this attempt's own placed blocks, found under the saved player hitbox.
            boolean landedThisTick = false;
            BlockPos landingSupportForGroundedTick = null;
            if (!player.onGround) {
                attemptWasAirborne = true;
            } else if (attemptWasAirborne) {
                waitingForOppositeFacing = false;
                landedThisTick = true;
                boolean wasFacingOpposite = isOppositeDirection(
                        previousFacingDirection, attemptDirection
                );
                if (BridgeLog.isEnabled()) {
                    logLandingScan(player, world, supportGridCandidate, wasFacingOpposite);
                }
                double landingCoordinate = getDirectionalCoordinate(
                        player.posX, player.posZ, attemptDirection
                );
                double previousTickCoordinate = getPreviousTickDirectionalCoordinate(landingCoordinate);
                boolean excludeLandingTickPlacements = isOpeningFlatLanding(player);
                // A still-unresolved earlier snapshot must not be silently overwritten: that
                // would leave the baselines stale and make the next landing look like the first.
                if (pendingLanding != null) {
                    PendingLanding stale = pendingLanding;
                    pendingLanding = null;
                    resolveLandingWithFallback(world, stale, "superseded by a newer landing");
                }
                PendingLanding snapshot = new PendingLanding(
                        player, clientTickCounter, previousTickCoordinate, wasFacingOpposite,
                        excludeLandingTickPlacements
                );
                snapshot.liveScanSupport = supportGridCandidate;

                // Select support from the immutable landing snapshot. On the opening flat only,
                // a block registered on this exact tick is considered part of the next run, not
                // the platform that was already under the player when they touched down.
                BlockPos landingSupport = findOwnPlacedSupport(world, snapshot);
                landingSupportForGroundedTick = landingSupport;
                snapshot.lockedSupportPosition = landingSupport;
                snapshot.lockedStable = landingSupport != null && lastSupportRule.startsWith("centre-same-tick");
                boolean placementStillPending = hasPendingPlacementInSupportGrid(
                        world, snapshot, landingSupport != null && lastSupportRuleIsEarly());
                boolean stableSupport = snapshot.lockedStable;
                if (BridgeLog.isEnabled()) {
                    BridgeLog.log("LAND-SUPPORT", "landing tick=" + snapshot.clientTick
                            + " opening=" + excludeLandingTickPlacements
                            + " picked=" + BridgeLog.pos(landingSupport) + " rule=" + lastSupportRule
                            + " stable=" + stableSupport + " unresolvedPlacement=" + placementStillPending
                            + " cells=" + describeExcludedLandingTickPlacements(world, snapshot)
                            + " pending=" + describePendingPlacements(world));
                }
                if (landingSupport != null && (!placementStillPending || stableSupport)) {
                    if (BridgeLog.isEnabled() && excludeLandingTickPlacements) {
                        BridgeLog.log("LAND-OPENING-LOCK", "opening flat locked support="
                                + BridgeLog.pos(landingSupport) + " rule=" + lastSupportRule
                                + " cells=" + describeExcludedLandingTickPlacements(world, snapshot)
                                + "; blocks placed after the landing tick stay tracked for the next landing."
                                + " pending=" + describePendingPlacements(world));
                    }
                    recordLanding(landingSupport, snapshot);
                } else {
                    pendingLanding = snapshot;
                    if (BridgeLog.isEnabled()) {
                        BridgeLog.log("LAND-WAIT", "captured landing at tick=" + snapshot.clientTick
                                + " pos=(" + BridgeLog.f(snapshot.posX) + "," + BridgeLog.f(snapshot.posZ)
                                + ") candidate=" + BridgeLog.pos(landingSupport)
                                + " rule=" + lastSupportRule + " unresolvedSupportPlacement="
                                + placementStillPending + " excludeLandingTickPlacements="
                                + excludeLandingTickPlacements + " pending=" + describePendingPlacements(world)
                                + "; will retry without allowing later placements to replace a locked support");
                    }
                }
                // The flight has ended. The snapshot above remains live until its support is
                // verified, even if the player starts the next jump before the retry succeeds.
                attemptWasAirborne = false;
            }
            if (player.onGround) {
                // The snapshot's support is authoritative for this transition. The live scan
                // may already see a same-tick block that belongs to the next run.
                BlockPos groundedSupport = landedThisTick && landingSupportForGroundedTick != null
                        ? landingSupportForGroundedTick : supportGridCandidate;
                trackGroundedSupport(groundedSupport, landedThisTick);
            }

            if (bedClickEndsAttempt) {
                finishCompletedAttempt(player, false);
            } else if (isOverGoldenPressurePlate(world, player)) {
                finishCompletedAttempt(player, true);
            } else if (shouldFailAttempt(player)) {
                finishFailedAttempt(failReason);
            }
        }

        // Turnout forecasts continue for their full 7 live + 7 simulated tick window
        // (14 ticks total after takeoff), even if the attempt reaches its plate/failure state.
        processPendingTurnouts(player, minecraft);

        // Save this tick's position after checks so previousPos* still means exactly T-1 above.
        previousPosX = player.posX;
        previousPosY = player.posY;
        previousPosZ = player.posZ;
        hasPreviousPosition = true;
        previousFacingDirection = getHorizontalFacingDirection(player.rotationYaw);
        previousPlayerOnGround = player.onGround;
        bedClickEndsAttempt = false;
        if (player.onGround) {
            hasLastGroundedPlayerY = true;
            lastGroundedPlayerY = player.posY;
        }
        insideClientTickEnd = false;
    }

    /**
     * The client-side jump event (EntityPlayerSP.jump): Jump Ticks is the number of ground ticks
     * between the last landing and this jump. Because it does not depend on the player visibly
     * leaving the ground, stair boosts (where the player is back on the ground by the end of the
     * jump tick) are measured too. The count restarts so the jump tick itself is the next tick 0.
     */
    public void onPlayerJump(Object entity) {
        Minecraft minecraft = Minecraft.getMinecraft();
        if (entity == null || entity != minecraft.thePlayer || minecraft.theWorld == null
                || !minecraft.theWorld.isRemote) {
            return;
        }
        jumpTicks = Math.max(0, groundTicksSinceLanding);
        hasJumpTicks = true;
        groundTicksSinceLanding = -1;
    }

    private void updateAttemptDirection(EntityPlayer player) {
        String direction = getAttemptDirection(player.rotationYaw, player.rotationPitch);
        if (direction == null) {
            // Leaving exact cardinal alignment allows the next alignment to be sampled.
            lastDetectedDirection = null;
            return;
        }

        if (!attemptActive) {
            preparedAttemptDirections.add(direction);
        }
        if (direction.equals(lastDetectedDirection)) {
            return;
        }

        lastDetectedDirection = direction;
        detectedDirection = direction;

        // Direction detection is silent and only updates internal attempt-direction state.
    }

    /** Called by the Forge-event bridge for a real client-side block placement. */
    public void onBlockPlaced(BlockEvent.PlaceEvent event) {
        Minecraft minecraft = Minecraft.getMinecraft();
        if (event == null || !event.world.isRemote || minecraft.thePlayer == null
                || event.player != minecraft.thePlayer) {
            return;
        }
        if (BridgeLog.isEnabled()) {
            BridgeLog.log("INTERACT", "PlaceEvent (direct) pos=" + BridgeLog.pos(event.pos));
        }
        int placementClientTick = clientTickCounter + (insideClientTickEnd ? 0 : 1);
        registerOwnBlockPlacement(
                minecraft.thePlayer, event.pos, event.player.rotationYaw, event.player.rotationPitch,
                "PlaceEvent", placementClientTick
        );
    }

    /**
     * PlayerInteractEvent is a pre-use event. Verify that the target changes on a following
     * client tick before considering it a real placement.
     */
    public void onPlayerInteract(PlayerInteractEvent event) {
        Minecraft minecraft = Minecraft.getMinecraft();
        recordBedClick(event, minecraft);
        if (event == null || !event.world.isRemote || minecraft.thePlayer == null
                || event.entityPlayer != minecraft.thePlayer
                || event.action != PlayerInteractEvent.Action.RIGHT_CLICK_BLOCK
                || event.face == null) {
            return;
        }

        ItemStack heldItem = minecraft.thePlayer.getHeldItem();
        if (heldItem == null || !(heldItem.getItem() instanceof ItemBlock)) {
            return;
        }

        BlockPos targetPosition = event.pos.offset(event.face);
        net.minecraft.block.state.IBlockState beforeState = event.world.getBlockState(targetPosition);
        if (BridgeLog.isEnabled()) {
            BridgeLog.log("INTERACT", "right-click on " + BridgeLog.pos(event.pos) + " face=" + event.face
                    + " -> target " + BridgeLog.pos(targetPosition) + " before=" + beforeState.getBlock()
                    + " pendingNow=" + pendingPlacements.size() + " attemptActive=" + attemptActive
                    + " yaw=" + BridgeLog.f(minecraft.thePlayer.rotationYaw)
                    + " pitch=" + BridgeLog.f(minecraft.thePlayer.rotationPitch));
        }
        if (pendingPlacements.size() >= 12) {
            pendingPlacements.remove(0);
        }
        int placementClientTick = clientTickCounter + (insideClientTickEnd ? 0 : 1);
        pendingPlacements.add(new PendingPlacement(
                targetPosition, beforeState, clientTickCounter, placementClientTick,
                minecraft.thePlayer.rotationYaw, minecraft.thePlayer.rotationPitch
        ));
    }

    // ------------------------------------------------------------------ Bed Click Timing

    /** Foot block of the bed at pos (the head and foot halves share one key), or null. */
    private BlockPos getBedFoot(World world, BlockPos pos) {
        net.minecraft.block.state.IBlockState state = world.getBlockState(pos);
        if (!(state.getBlock() instanceof BlockBed)) {
            return null;
        }
        if (state.getValue(BlockBed.PART) == BlockBed.EnumPartType.HEAD) {
            return pos.offset(((net.minecraft.util.EnumFacing) state.getValue(BlockBed.FACING)).getOpposite());
        }
        return pos;
    }

    private void queueBedClick(World world, BlockPos pos) {
        if (world == null || pos == null) {
            return;
        }
        BlockPos foot = getBedFoot(world, pos);
        if (foot != null) {
            if (attemptActive && timerStarted) {
                bedClickEndsAttempt = true;
            }
            if (!BridgeConfig.isEnabled(BridgeConfig.Setting.BED_CLICK_TIMING)) {
                return;
            }
            // Input is handled before this tick's END event, so the click belongs to the tick
            // that is about to finish (or to the current one when already inside END).
            // A click is handled before the player moves on its tick, so it uses the position
            // saved at the END of the previous tick: that is the tick it is timed against.
            int tick = clientTickCounter - (insideClientTickEnd ? 1 : 0);
            pendingBedClicks.add(new Object[] {foot, Integer.valueOf(tick)});
        }
    }

    private void recordBedClick(PlayerInteractEvent event, Minecraft minecraft) {
        if (event == null || event.world == null || !event.world.isRemote
                || minecraft.thePlayer == null || event.entityPlayer != minecraft.thePlayer
                || (event.action != PlayerInteractEvent.Action.RIGHT_CLICK_BLOCK
                && event.action != PlayerInteractEvent.Action.LEFT_CLICK_BLOCK)) {
            return;
        }
        queueBedClick(event.world, event.pos);
    }

    /**
     * Distance from the player's head (eye position, independent of yaw / pitch) to the closest of
     * the eight corners of the bed's combined two-block box.
     */
    private double distanceToClosestBedCorner(EntityPlayer player, World world, BlockPos foot) {
        net.minecraft.block.state.IBlockState state = world.getBlockState(foot);
        if (!(state.getBlock() instanceof BlockBed)) {
            return Double.MAX_VALUE;
        }
        BlockPos head = foot.offset((net.minecraft.util.EnumFacing) state.getValue(BlockBed.FACING));
        double minX = Math.min(foot.getX(), head.getX());
        double maxX = Math.max(foot.getX(), head.getX()) + 1.0D;
        double minZ = Math.min(foot.getZ(), head.getZ());
        double maxZ = Math.max(foot.getZ(), head.getZ()) + 1.0D;
        double minY = foot.getY();
        double maxY = foot.getY() + 0.5625D;
        double eyeX = player.posX;
        double eyeY = player.posY + player.getEyeHeight();
        double eyeZ = player.posZ;
        double best = Double.MAX_VALUE;
        for (int cx = 0; cx < 2; cx++) {
            for (int cy = 0; cy < 2; cy++) {
                for (int cz = 0; cz < 2; cz++) {
                    double dx = eyeX - (cx == 0 ? minX : maxX);
                    double dy = eyeY - (cy == 0 ? minY : maxY);
                    double dz = eyeZ - (cz == 0 ? minZ : maxZ);
                    best = Math.min(best, Math.sqrt(dx * dx + dy * dy + dz * dz));
                }
            }
        }
        return best;
    }

    /**
     * Each tick: finds beds around the player, tracks when each one enters reach (< 4.5 from the
     * head to its closest corner) and turns the next click on it into a timing. Clicking on the
     * tick the bed enters reach is 0t, each later tick adds 1t, and a click made before the bed
     * was in reach is negative by how many ticks early it was.
     */
    private void updateBedClickTiming(Minecraft minecraft, EntityPlayer player, World world) {
        boolean attackDown = minecraft.gameSettings.keyBindAttack.isKeyDown();
        boolean useDown = minecraft.gameSettings.keyBindUseItem.isKeyDown();

        // Fallback click detection from the mouse buttons, for when Forge does not post an
        // interact event (for example a left click that starts breaking the bed).
        if (((attackDown && !previousAttackDown) || (useDown && !previousUseDown))
                && minecraft.objectMouseOver != null
                && minecraft.objectMouseOver.typeOfHit
                == net.minecraft.util.MovingObjectPosition.MovingObjectType.BLOCK) {
            queueBedClick(world, minecraft.objectMouseOver.getBlockPos());
        }
        previousAttackDown = attackDown;
        previousUseDown = useDown;

        if (!BridgeConfig.isEnabled(BridgeConfig.Setting.BED_CLICK_TIMING)) {
            bedStates.clear();
            pendingBedClicks.clear();
            return;
        }

        int tick = clientTickCounter;
        int px = MathHelper.floor_double(player.posX);
        int py = MathHelper.floor_double(player.posY + player.getEyeHeight());
        int pz = MathHelper.floor_double(player.posZ);
                for (int dx = -BED_SCAN_RADIUS; dx <= BED_SCAN_RADIUS; dx++) {
            for (int dy = -BED_SCAN_RADIUS; dy <= BED_SCAN_RADIUS; dy++) {
                for (int dz = -BED_SCAN_RADIUS; dz <= BED_SCAN_RADIUS; dz++) {
                    BlockPos scan = new BlockPos(px + dx, py + dy, pz + dz);
                    if (world.getBlockState(scan).getBlock() instanceof BlockBed) {
                        BlockPos foot = getBedFoot(world, scan);
                        if (foot != null && !bedStates.containsKey(foot)) {
                            bedStates.put(foot, new BedState());
                        }
                        if (foot != null) {
                            bedStates.get(foot).lastSeenTick = tick;
                        }
                    }
                }
            }
        }
        // Beds that were only known from a click (outside the scan) get created on demand below.
        for (Object[] click : pendingBedClicks) {
            BlockPos foot = (BlockPos) click[0];
            if (!bedStates.containsKey(foot)) {
                BedState created = new BedState();
                created.lastSeenTick = tick;
                bedStates.put(foot, created);
            }
        }

        Iterator<Map.Entry<BlockPos, BedState>> iterator = bedStates.entrySet().iterator();
        while (iterator.hasNext()) {
            Map.Entry<BlockPos, BedState> entry = iterator.next();
            BedState state = entry.getValue();
            double distance = distanceToClosestBedCorner(player, world, entry.getKey());
            if (distance == Double.MAX_VALUE
                    || tick - state.lastSeenTick > BED_FORGET_TICKS) {
                iterator.remove();
                continue;
            }
            boolean inReach = distance < BED_REACH;
            if (inReach && !state.inReach) {
                state.entryTick = tick;
                state.resolved = false;
                // A click made while the bed was still out of reach: report how early it was.
                if (state.earlyClickTick != Integer.MIN_VALUE) {
                    int early = tick - state.earlyClickTick;
                    state.earlyClickTick = Integer.MIN_VALUE;
                    if (early >= 0 && early <= BED_EARLY_CLICK_MAX_TICKS) {
                        bedClickTiming = -early;
                        hasBedClickTiming = true;
                        state.resolved = true;
                    }
                }
            }
            state.inReach = inReach;
        }

        for (Object[] click : pendingBedClicks) {
            BedState state = bedStates.get((BlockPos) click[0]);
            int clickTick = ((Integer) click[1]).intValue();
            if (state == null) {
                continue;
            }
            if (state.inReach) {
                if (!state.resolved) {
                    // Negative when the click was made one tick before the bed entered reach.
                    bedClickTiming = clickTick - state.entryTick;
                    hasBedClickTiming = true;
                    state.resolved = true;
                }
            } else {
                state.earlyClickTick = clickTick;
            }
        }
        pendingBedClicks.clear();
    }

    private void processPendingPlacements(Minecraft minecraft, World world) {
        if (pendingPlacements.isEmpty()) {
            return;
        }

        // Work on a snapshot: registering the first block of an attempt starts the attempt, and
        // two clicks can land in the same client tick. Every pending placement must be judged on
        // its own. (Previously the first verified block cleared all the others, so a second block
        // placed in the same tick was never tracked and a landing on it was lost.)
        List<PendingPlacement> snapshot = new ArrayList<PendingPlacement>(pendingPlacements);
        for (PendingPlacement pending : snapshot) {
            int age = clientTickCounter - pending.createdAtTick;
            if (age <= 0) {
                continue;
            }

            net.minecraft.block.state.IBlockState currentState = world.getBlockState(pending.position);
            Block currentBlock = currentState.getBlock();
            if (!currentState.equals(pending.beforeState) && currentBlock != Blocks.air) {
                pendingPlacements.remove(pending);
                registerOwnBlockPlacement(
                        minecraft.thePlayer, pending.position,
                        pending.placementYaw, pending.placementPitch, "verified-click",
                        pending.placementClientTick
                );
                continue;
            }

            if (age >= PENDING_PLACEMENT_MAX_AGE) {
                pendingPlacements.remove(pending);
                if (BridgeLog.isEnabled()) {
                    BridgeLog.log("PLACE", "pending click at " + BridgeLog.pos(pending.position)
                            + " expired after " + age + " ticks without the block changing "
                            + "(before=" + pending.beforeState.getBlock() + ", now=" + currentBlock
                            + ") -> not a placement");
                }
            }
        }
    }

    private void registerOwnBlockPlacement(EntityPlayer player, BlockPos position,
                                           float placementYaw, float placementPitch,
                                           String source, int placementClientTick) {
        if (player == null || position == null) {
            return;
        }

        // The first successful placement starts a fresh attempt. startTimer clears the prior
        // attempt's placement and landing history before this new block is recorded.
        if (!attemptActive && !startTimer(player, placementYaw, placementPitch, placementClientTick)) {
            if (BridgeLog.isEnabled()) {
                BridgeLog.log("PLACE", "block at " + BridgeLog.pos(position) + " via " + source
                        + " IGNORED: no attempt is running and no travel direction has been detected yet "
                        + "(align to a cardinal yaw with pitch 0.0 first)");
            }
            return;
        }
        if (!attemptActive || !timerStarted) {
            return;
        }

        // Forge's PlaceEvent and the right-click verification fallback may both see the same
        // placement. Count a position only once even if both paths observe it.
        if (ownPlacedBlocks.add(position)) {
            // Both event paths carry the normalized tick when the placement was made.
            // Do not substitute the later verification tick for the actual placement tick.
            int placedAt = placementClientTick;
            ownPlacedAtTick.put(position, placedAt);
            if (!hasAttemptStartBlockCoordinate) {
                attemptStartBlockCoordinate = getDirectionalBlockCoordinate(position, attemptDirection);
                hasAttemptStartBlockCoordinate = true;
            }
            // Do not cap this at five placements: long opening runs can land beyond block five.
            openingFlatBlocks.add(position);
            if (BridgeLog.isEnabled()) {
                BridgeLog.log("PLACE", "registered " + BridgeLog.pos(position) + " via " + source
                        + " axisBlock=" + getDirectionalBlockCoordinate(position, attemptDirection)
                        + " placedAtTick=" + placedAt + " ownBlocks=" + ownPlacedBlocks.size()
                        + " pendingLeft=" + pendingPlacements.size());
            }
        } else if (BridgeLog.isEnabled()) {
            BridgeLog.log("PLACE", "duplicate of " + BridgeLog.pos(position) + " via " + source
                    + " (already tracked)");
        }
    }

    private boolean startTimer(EntityPlayer player, float placementYaw, float placementPitch,
                               int placementClientTick) {
        if (player == null || attemptActive) {
            return false;
        }

        // If placement and alignment happen in the same tick, include the current cardinal
        // direction before choosing the travel direction. When the player has first aligned
        // with the travel direction and then turns around to place, select the opposite of the
        // placement-facing direction from the directions observed during this setup period.
        String currentDirection = getAttemptDirection(placementYaw, placementPitch);
        if (currentDirection != null) {
            preparedAttemptDirections.add(currentDirection);
            detectedDirection = currentDirection;
            lastDetectedDirection = currentDirection;
        }

        String placementFacingDirection = getHorizontalFacingDirection(placementYaw);
        String selectedAttemptDirection = null;
        for (String candidate : preparedAttemptDirections) {
            if (isOppositeDirection(placementFacingDirection, candidate)) {
                selectedAttemptDirection = candidate;
                break;
            }
        }
        if (selectedAttemptDirection == null) {
            selectedAttemptDirection = detectedDirection;
        }
        if (selectedAttemptDirection == null) {
            return false;
        }

        // A successful first placement is the only action that increments Attempts or clears
        // the previous attempt's HUD data.
        attemptDirection = selectedAttemptDirection;
        openingFlatDetectionEnabled = isOppositeDirection(
                placementFacingDirection, attemptDirection
        );
        preparedAttemptDirections.clear();
        attempts++;
        BridgeBests.addAttempt();
        BridgeLog.setContext(clientTickCounter, attempts);
        attemptActive = true;
        timerStarted = true;
        timerTicks = 0;
        // This timestamp was captured when the first placement click/event occurred. It is
        // identical for direct PlaceEvent and delayed verification paths.
        firstPlacedBlockClientTick = placementClientTick;
        attemptStartY = player.posY;
        double startGroundReferenceY = player.posY;
        if (player.onGround) {
            startGroundReferenceY = player.posY;
        } else if (hasLastGroundedPlayerY) {
            startGroundReferenceY = lastGroundedPlayerY;
        } else if (previousPlayerOnGround && hasPreviousPosition) {
            startGroundReferenceY = previousPosY;
        }
        attemptStartGroundY = startGroundReferenceY;
        result = AttemptResult.NONE;
        hasPlateCoordinates = false;
        plateCoordinate = 0.0D;
        platePreviousTickCoordinate = 0.0D;
        hasOpeningFlatResult = false;
        openingFlatType = null;
        openingFlatCoordinate = 0.0D;
        openingFlatJumpoverOffset = 0.0D;
        openingFlatAwaitingAdvancedResolution = false;
        previousFlatWasDelayed = false;
        hasOpeningLandingBaseline = false;
        openingLandingFraction = 0.0D;
        attemptWasAirborne = false;
        previousPlayerOnGround = player.onGround;
        canStartFailedJump = false;
        pendingAdvancedLanding = null;
        pendingLanding = null;
        cancelFailedJumpSequence();
        ownPlacedBlocks.clear();
        ownPlacedAtTick.clear();
        openingFlatBlocks.clear();
        hasPreviousLanding = false;
        previousLandingY = 0;
        tapCount = 0;
        // The opening 1.xx Flat is already Flat 1, so later same-level landings begin at Flat 2.
        sameLevelFlatCount = 1;
        hasUnifiedPreviousLandingCoordinate = false;
        unifiedPreviousLandingFraction = 0.0D;
        unifiedPreviousLandingCoordinate = 0.0D;
        lastLandingSupportBlockCoordinate = 0;
        hasLastLandingSupportBlock = false;
        groundedSupportBlockCoordinate = 0;
        hasGroundedSupportBlock = false;
        takeoffSupportBlockCoordinate = 0;
        hasTakeoffSupportBlock = false;
        lastSupportRule = "none";
        failReason = "";
        landingCounter = 0;
        landingTable.clear();
        hasUnifiedPreviousJumpoverOffset = false;
        unifiedPreviousJumpoverOffset = 0.0D;
        previousRowResult = null;
        previousRowIsOpeningFlat = false;
        // The attempt's first tick on the ground is tick 0, so start one below it.
        groundTicksSinceLanding = -1;
        openingFlatDisplayCoordinate = null;
        openingFlatSuffix = " (n/a)";
        openingFlatSuffixColor = 0xFF9AA8BA;
        openingFlatExtraLine = null;
        openingFlatTurnout = null;
        openingFlatTurnoutDifference = null;
        attemptStartBlockCoordinate = 0;
        hasAttemptStartBlockCoordinate = false;
        clearTurnoutLandingAnchor();
        pendingTurnouts.clear();
        landingResults.clear();
        bestTracker.reset();
        openingFlatTimingSeconds = 0.0D;
        openingFlatStatCoordinate = 0.0D;
        openingFlatLandingNumber = 0;
        openingStatsDone = false;
        // Do NOT clear pendingPlacements here: a second block clicked in the same tick as the
        // attempt's first block is still waiting to be verified and must not be discarded.
        if (BridgeLog.isEnabled()) {
            BridgeLog.log("ATTEMPT-START", "===== ATTEMPT " + attempts + " START ===== direction="
                    + attemptDirection + " placementFacing=" + placementFacingDirection
                    + " openingFlatDetection=" + openingFlatDetectionEnabled
                    + " selectedFrom=" + selectedAttemptDirection
                    + " firstPlacedBlockClientTick=" + firstPlacedBlockClientTick
                    + " startPos=(" + BridgeLog.f(player.posX) + "," + BridgeLog.f(player.posY) + ","
                    + BridgeLog.f(player.posZ) + ") onGround=" + player.onGround
                    + " startGroundY=" + BridgeLog.f(attemptStartGroundY)
                    + " yaw=" + BridgeLog.f(placementYaw) + " pitch=" + BridgeLog.f(placementPitch)
                    + " timerEnabled=" + BridgeConfig.isEnabled(BridgeConfig.Setting.TIMER)
                    + " tapsFlatsEnabled=" + BridgeConfig.isEnabled(BridgeConfig.Setting.TAPS_FLATS)
                    + " turnoutEnabled=" + BridgeConfig.isEnabled(BridgeConfig.Setting.TURNOUT));
        }
        return true;
    }

    /**
     * True only for the first landing, at the same feet height as the attempt start.
     * Same-tick placements are excluded from support selection for this one landing so a
     * fifth block placed as the player touches down on the four-block opening is not mistaken
     * for the opening platform's support. The placement itself remains in ownPlacedBlocks.
     */
    private boolean isOpeningFlatLanding(EntityPlayer player) {
        if (hasPreviousLanding || player == null) {
            return false;
        }
        return Math.abs(player.posY - attemptStartGroundY) < 0.75D;
    }

    /**
     * Safety net: a block placed on/after the landing tick that comes out exactly five blocks past
     * the previous landing is almost certainly the mistaken extra block, not the block the player
     * landed on. If the block one step back was placed earlier, use that one instead.
     */
    private BlockPos correctLateForwardSupport(BlockPos support, PendingLanding snap) {
        if (!hasLastLandingSupportBlock || snap == null || attemptDirection == null) {
            return support;
        }
        Integer placedAt = ownPlacedAtTick.get(support);
        if (placedAt == null || placedAt.intValue() < snap.clientTick) {
            return support;
        }
        int axis = getDirectionalBlockCoordinate(support, attemptDirection);
        if (axis - lastLandingSupportBlockCoordinate != 5) {
            return support;
        }
        boolean xAxis = "east".equals(attemptDirection) || "west".equals(attemptDirection);
        for (BlockPos own : ownPlacedBlocks) {
            if (own.getY() != support.getY()
                    || getDirectionalBlockCoordinate(own, attemptDirection) != axis - 1
                    || (xAxis ? own.getZ() != support.getZ() : own.getX() != support.getX())) {
                continue;
            }
            Integer ownTick = ownPlacedAtTick.get(own);
            if (ownTick != null && ownTick.intValue() >= snap.clientTick) {
                continue;
            }
            if (BridgeLog.isEnabled()) {
                BridgeLog.anomaly("FORWARD5-CORRECT", "support " + BridgeLog.pos(support)
                        + " placed at t" + placedAt + " (landing t" + snap.clientTick
                        + ") gave forward=5; using the earlier block " + BridgeLog.pos(own) + " instead");
            }
            return own;
        }
        return support;
    }

    /**
     * True when the previous Tap/Flat row is already a 4b Speed, a 1.1 / 1.15+ 5b (opening or
     * later 5b Flat), or a 4b Tap, so the positive-after-negative 4b Tap promotion must not apply.
     */
    private boolean previousRowBlocks4bTapPromotion() {
        String label = previousRowIsOpeningFlat ? openingFlatType
                : previousRowResult == null ? null : previousRowResult.label;
        if (label == null) {
            return false;
        }
        return label.contains("4b Speed") || label.contains("4b Tap") || label.contains("5b");
    }

    private boolean previousRowIs4bTapOrSpeed() {
        String label = previousRowIsOpeningFlat ? openingFlatType
                : previousRowResult == null ? null : previousRowResult.label;
        return label != null && (label.contains("4b Tap") || label.contains("4b Speed"));
    }

    private void recordLanding(BlockPos supportPosition, PendingLanding landingSnapshot) {
        supportPosition = correctLateForwardSupport(supportPosition, landingSnapshot);
        int landingY = supportPosition.getY() + 1;
        // Taps/flats (and turnout) are only recorded when the first block was placed facing
        // opposite the attempt direction; otherwise only jump ticks, plate, bed timing etc.
        boolean wasFacingOpposite = landingSnapshot.wasFacingOpposite && openingFlatDetectionEnabled;
        boolean sprintingAtLanding = landingSnapshot.sprinting;
        int landingTick = landingSnapshot.clientTick;
        double landingCoordinate = getDirectionalCoordinate(
                landingSnapshot.posX, landingSnapshot.posZ, attemptDirection
        );
        double landingFraction = getDisplayedCoordinateFraction(landingCoordinate);
        int landingBlockCoordinate = getDirectionalBlockCoordinate(supportPosition, attemptDirection);

        // Jumpover offset is fractional-only. Tap/Flat classification below uses the actual
        // support-block advance along the attempt axis (X or Z), independently of that offset.
        double previousTickCoordinate = landingSnapshot.previousTickCoordinate;
        double previousTickFraction = getDisplayedCoordinateFraction(previousTickCoordinate);
        double jumpoverOffset = getJumpoverOffset(previousTickFraction);
        boolean hasPreviousLandingFraction = hasUnifiedPreviousLandingCoordinate;
        double previousLandingFraction = hasPreviousLandingFraction
                ? unifiedPreviousLandingFraction : 0.0D;

        // Block advance is measured from the IMMEDIATELY PREVIOUS own-block landing, whether or
        // not that landing produced a Tap/Flat row. Only the very first landing falls back to
        // the first block placed in the attempt.
        int forwardBlocks = -1;
        int baselineBlock = 0;
        String baselineSource = "none";
        if (hasLastLandingSupportBlock) {
            baselineBlock = lastLandingSupportBlockCoordinate;
            forwardBlocks = landingBlockCoordinate - baselineBlock;
            baselineSource = "previous-landing";
        } else if (hasAttemptStartBlockCoordinate) {
            baselineBlock = attemptStartBlockCoordinate;
            forwardBlocks = landingBlockCoordinate - baselineBlock;
            baselineSource = "first-placed-block";
        }

        landingCounter++;
        int landingNumber = landingCounter;

        if (!hasPreviousLanding) {
            // The first own-block landing is not automatically a hidden baseline: it can be
            // the first Tap. Compare its support height to the player's starting ground level
            // and count the first placed block as block 1 of the opening forward run.
            double initialHeightDelta = landingY - attemptStartGroundY;
            int initialHeightDifference = initialHeightDelta >= 0.75D ? 1
                    : initialHeightDelta <= -0.75D ? -1 : 0;
            int initialForwardBlocks = forwardBlocks >= 0 ? forwardBlocks + 1 : -1;
            // Opening run length affects only technique classification. Jumpover offset is
            // always a wrapped fraction and never receives any whole-block carry.
            jumpoverOffset = getJumpoverOffset(previousTickFraction);
            String decision;

            hasOpeningLandingBaseline = true;
            openingLandingFraction = landingFraction;
            previousRowResult = null;
            previousRowIsOpeningFlat = false;
            if (initialHeightDifference != 0) {
                // If the first landing is not the opening same-height Flat, no Flat 1 has
                // occurred yet; the first later same-height result should be Flat 1.
                sameLevelFlatCount = 0;
            }

            if (wasFacingOpposite && initialHeightDifference == 1
                    && (initialForwardBlocks == 3 || initialForwardBlocks == 4)) {
                // A one-block-up first landing is a real opening Tap, not an invisible
                // baseline. There is no prior Tap/Flat decimal yet, so it starts with (n/a).
                tapCount++;
                String firstTapLabel = initialForwardBlocks == 4
                        ? "4b Tap - " : "Tap " + tapCount + " - ";
                LandingResult firstTap = new LandingResult(
                        firstTapLabel,
                        formatCoordinateDecimal(landingCoordinate),
                        0xFF67D9FF,
                        0xFFC4A7FF,
                        "(n/a)",
                        0xFF9AA8BA,
                        formatSignedDecimalOffset(jumpoverOffset),
                        getDifferenceColor(jumpoverOffset)
                );
                firstTap.jumpoverOffsetValue = jumpoverOffset;
                firstTap.landingNumber = landingNumber;
                firstTap.tap = true;
                firstTap.statCoordinate = landingCoordinate;
                landingResults.add(firstTap);
                previousRowResult = firstTap;
                setTurnoutLandingAnchor(firstTap, false, landingFraction);
                pendingAdvancedLanding = new PendingAdvancedLanding(
                        firstTap, false, true, landingTick,
                        initialForwardBlocks, 0, false, 0.0D,
                        landingCoordinate, previousTickCoordinate, sprintingAtLanding
                );
                canStartFailedJump = true;
                previousFlatWasDelayed = false;
                decision = "FIRST-TAP row='" + firstTapLabel.trim() + "'";
            } else {
                // Compute the opening placement-to-landing gap once for both the visible row
                // and the pending advanced-landing state below. Keeping it in this scope also
                // ensures the hidden-opening-flat path retains the same timing baseline.
                int ticksFromFirstPlacement = Math.max(
                        0, landingTick - firstPlacedBlockClientTick
                );

                // A same-height first landing is the special opening Flat. It intentionally
                // has its own first-placement-facing requirement and can be supported by any
                // own block in the opening run; the later 3b/4b classifier must not suppress it.
                // recordLanding is called only with a support returned by findOwnPlacedSupport,
                // which has already verified that the block is one of this attempt's live own
                // placements. Do not require a second, duplicate set lookup here: a placement
                // observed through the alternate Forge verification path can be in the primary
                // set before the opening-run preference set is populated.
                boolean openingFlatVisible = openingFlatDetectionEnabled
                        && initialHeightDifference == 0
                        && ownPlacedBlocks.contains(supportPosition);
                if (openingFlatVisible) {
                    // One formula covers both timings: a 3-tick gap is 1.05, a 4-tick
                    // gap is 1.10, a 5-tick gap is 1.15, and so on. No 1.05 minimum
                    // clamp hides a real one-tick difference.
                    double flatSeconds = calculateFlatTime(ticksFromFirstPlacement, 0);
                    openingFlatType = formatFlatTime(flatSeconds) + " Flat";
                    // A 1.1 with a 5th block already down by the ground tick and a positive
                    // jumpover offset is a 1.1 5b, not a plain 1.1 Flat.
                    net.minecraft.world.World labelWorld = Minecraft.getMinecraft().theWorld;
                    int openingBlocksDown = labelWorld == null ? 0
                            : countOpeningBlocksThroughLanding(labelWorld, landingTick);
                    if (ticksFromFirstPlacement == 4 && openingBlocksDown >= 5 && jumpoverOffset > 0.0D) {
                        openingFlatType = formatFlatTime(flatSeconds) + " 5b";
                    }
                    openingFlatCoordinate = landingCoordinate;
                    openingFlatJumpoverOffset = jumpoverOffset;
                    openingFlatTimingSeconds = flatSeconds;
                    openingFlatStatCoordinate = landingCoordinate;
                    openingFlatLandingNumber = landingNumber;
                    hasOpeningFlatResult = true;
                    openingFlatDisplayCoordinate = null;
                    openingFlatSuffix = " (n/a)";
                    openingFlatSuffixColor = 0xFF9AA8BA;
                    openingFlatExtraLine = null;
                    openingFlatAwaitingAdvancedResolution = true;
                    previousRowIsOpeningFlat = true;
                    setTurnoutLandingAnchor(null, true, landingFraction);
                    decision = "OPENING-FLAT row='" + openingFlatType + "' firstPlacedTick="
                            + firstPlacedBlockClientTick + " landingTick=" + landingTick
                            + " gapTicks=" + ticksFromFirstPlacement;
                    if (ticksFromFirstPlacement < 3 && BridgeLog.isEnabled()) {
                        BridgeLog.anomaly("OPENING-FLAT-SHORT-GAP", "first placement tick="
                                + firstPlacedBlockClientTick + " landing tick=" + landingTick
                                + " gap=" + ticksFromFirstPlacement
                                + " (expected 3 ticks for 1.05 or 4 for 1.10)");
                    }
                } else if (initialHeightDifference == 0) {
                    decision = "OPENING-FLAT-HIDDEN (openingFlatDetectionEnabled="
                            + openingFlatDetectionEnabled + ", supportIsOwn="
                            + ownPlacedBlocks.contains(supportPosition) + ") -> still the baseline";
                } else {
                    decision = "FIRST-LANDING-NO-ROW (heightVsStart=" + initialHeightDifference
                            + ", forward(1-based)=" + initialForwardBlocks + ", facingOpposite="
                            + wasFacingOpposite + ") -> still the baseline";
                }

                // Preserve the opening-flat wait state even when its 1.xx display is disabled
                // by the first-placement-facing rule. This still lets later Tap/Flat landings
                // use the correct immediate-versus-delayed opening jump history.
                if (initialHeightDifference == 0) {
                    pendingAdvancedLanding = new PendingAdvancedLanding(
                            null, true, false, landingTick,
                            Math.max(0, initialForwardBlocks), ticksFromFirstPlacement,
                            false, 0.0D, landingCoordinate, previousTickCoordinate, sprintingAtLanding
                    );
                } else {
                    pendingAdvancedLanding = null;
                }
                canStartFailedJump = true;
                previousFlatWasDelayed = false;
            }

            // The first actual landing, whether Tap or Flat, becomes the shared chronological
            // coordinate and support-block baseline for every subsequent landing and Turnout.
            hasUnifiedPreviousLandingCoordinate = true;
            unifiedPreviousLandingFraction = landingFraction;
            unifiedPreviousLandingCoordinate = landingCoordinate;
            hasUnifiedPreviousJumpoverOffset = true;
            unifiedPreviousJumpoverOffset = jumpoverOffset;
            finishLandingRecord(supportPosition, landingNumber, landingTick,
                    landingBlockCoordinate, landingY, baselineBlock, baselineSource, forwardBlocks,
                    "first landing: heightVsStart=" + initialHeightDifference
                            + " forward(1-based)=" + initialForwardBlocks
                            + " offsetMode=fractional-wrap[-.500,+.500]",
                    wasFacingOpposite, landingCoordinate, landingFraction, previousTickCoordinate,
                    previousTickFraction, jumpoverOffset,
                    sprintingAtLanding, overlapsHitbox(supportPosition, landingSnapshot.playerBox), decision);
            return;
        }

        int heightDifference = landingY - previousLandingY;
        // Tap/Flat block counts are classification inputs only; they never alter offset.
        jumpoverOffset = getJumpoverOffset(previousTickFraction);
        String decision;
        if (wasFacingOpposite && heightDifference == 1
                && (forwardBlocks == 3 || forwardBlocks == 4)) {
            boolean cameAfterDelayedFlat = previousFlatWasDelayed;
            previousFlatWasDelayed = false;
            tapCount++;
            double differenceValue = hasUnifiedPreviousLandingCoordinate
                    ? getBlockAdjustedDifference(landingCoordinate, unifiedPreviousLandingCoordinate,
                            3)
                    : 0.0D;
            String difference = hasUnifiedPreviousLandingCoordinate
                    ? formatCoordinateDifference(differenceValue) : "(n/a)";
            int differenceColor = hasUnifiedPreviousLandingCoordinate
                    ? getDifferenceColor(differenceValue) : 0xFF9AA8BA;
            // A positive-offset Tap right after a negative-offset Tap/Flat is a 4b Tap, unless the
            // previous row was already a 4b Speed, a 1.1 / 1.15+ 5b, or a 4b Tap.
            boolean promoteTo4bTap = jumpoverOffset > 0.0D
                    && hasUnifiedPreviousJumpoverOffset
                    && unifiedPreviousJumpoverOffset < 0.0D
                    && !previousRowBlocks4bTapPromotion();
            // A 4b Tap can never lose momentum. If the gain (coordinate change minus 3 blocks)
            // is negative, this was an ordinary Tap whose fraction only wrapped past the block
            // boundary, so it is never a 4b Tap (5b flats are unaffected: they use a separate path).
            boolean lostMomentum = hasUnifiedPreviousLandingCoordinate && differenceValue < 0.0D;
            if (promoteTo4bTap && lostMomentum) {
                // The offset looked positive only because of the lost momentum: show it that much
                // lower (e.g. +.400 with .200 lost -> +.200), wrapped into [-.500, +.500].
                double adjusted = jumpoverOffset + differenceValue;
                if (adjusted > 0.500D) {
                    adjusted -= 1.000D;
                } else if (adjusted < -0.500D) {
                    adjusted += 1.000D;
                }
                jumpoverOffset = Math.round(adjusted * 1000.0D) / 1000.0D;
            }
            // Never two 4b taps in a row: after a 4b Tap (or the 4b Speed it became), the next Tap
            // is a plain "Tap N".
            boolean previousWas4b = previousRowIs4bTapOrSpeed();
            String tapLabel = !previousWas4b && !lostMomentum
                    && (promoteTo4bTap || (forwardBlocks == 4 && !cameAfterDelayedFlat))
                    ? "4b Tap - " : "Tap " + tapCount + " - ";
            LandingResult landing = new LandingResult(
                    tapLabel,
                    formatCoordinateDecimal(landingCoordinate),
                    0xFF67D9FF,
                    0xFFC4A7FF,
                    difference,
                    differenceColor,
                    formatSignedDecimalOffset(jumpoverOffset),
                    getDifferenceColor(jumpoverOffset)
            );
            landing.jumpoverOffsetValue = jumpoverOffset;
            landing.previousRowWas4b = previousWas4b;
            fillLandingStats(landing, landingNumber, true, landingCoordinate,
                    hasUnifiedPreviousLandingCoordinate, differenceValue);
            landingResults.add(landing);
            previousRowResult = landing;
            previousRowIsOpeningFlat = false;
            setTurnoutLandingAnchor(landing, false, landingFraction);
            canStartFailedJump = true;

            pendingAdvancedLanding = new PendingAdvancedLanding(
                    landing, false, true, landingTick,
                    Math.max(0, forwardBlocks), 0,
                    hasPreviousLandingFraction, previousLandingFraction,
                    landingCoordinate, previousTickCoordinate, sprintingAtLanding
            );
            pendingAdvancedLanding.previousLandingCoordinate = unifiedPreviousLandingCoordinate;
            unifiedPreviousLandingFraction = landingFraction;
            unifiedPreviousLandingCoordinate = landingCoordinate;
            hasUnifiedPreviousLandingCoordinate = true;
            unifiedPreviousJumpoverOffset = jumpoverOffset;
            hasUnifiedPreviousJumpoverOffset = true;
            decision = "TAP row='" + tapLabel.trim() + "' diff=" + difference
                    + " cameAfterDelayedFlat=" + cameAfterDelayedFlat;
        } else if (wasFacingOpposite && heightDifference == 0 && forwardBlocks == 4) {
            previousFlatWasDelayed = false;
            sameLevelFlatCount++;
            double differenceValue = hasUnifiedPreviousLandingCoordinate
                    ? getBlockAdjustedDifference(landingCoordinate, unifiedPreviousLandingCoordinate,
                            4)
                    : 0.0D;
            String difference = hasUnifiedPreviousLandingCoordinate
                    ? formatCoordinateDifference(differenceValue) : "(n/a)";
            int differenceColor = hasUnifiedPreviousLandingCoordinate
                    ? getDifferenceColor(differenceValue) : 0xFF9AA8BA;
            LandingResult landing = new LandingResult(
                    "Flat " + sameLevelFlatCount + " - ",
                    formatCoordinateDecimal(landingCoordinate),
                    BridgeColors.FLAT_LABEL,
                    0xFF9CFFB5,
                    difference,
                    differenceColor,
                    formatSignedDecimalOffset(jumpoverOffset),
                    getDifferenceColor(jumpoverOffset)
            );
            landing.jumpoverOffsetValue = jumpoverOffset;
            fillLandingStats(landing, landingNumber, false, landingCoordinate,
                    hasUnifiedPreviousLandingCoordinate, differenceValue);
            landingResults.add(landing);
            previousRowResult = landing;
            previousRowIsOpeningFlat = false;
            setTurnoutLandingAnchor(landing, false, landingFraction);
            canStartFailedJump = true;
            pendingAdvancedLanding = new PendingAdvancedLanding(
                    landing, false, false, landingTick,
                    Math.max(0, forwardBlocks), 3,
                    hasPreviousLandingFraction, previousLandingFraction,
                    landingCoordinate, previousTickCoordinate, sprintingAtLanding
            );
            pendingAdvancedLanding.previousLandingCoordinate = unifiedPreviousLandingCoordinate;
            unifiedPreviousLandingFraction = landingFraction;
            unifiedPreviousLandingCoordinate = landingCoordinate;
            hasUnifiedPreviousLandingCoordinate = true;
            unifiedPreviousJumpoverOffset = jumpoverOffset;
            hasUnifiedPreviousJumpoverOffset = true;
            decision = "FLAT row='Flat " + sameLevelFlatCount + "' diff=" + difference;
        } else {
            // Not displayed. The block / height baselines below still advance to this landing.
            String why;
            if (!wasFacingOpposite) {
                why = "player was not facing opposite the attempt direction on the previous tick";
            } else if (heightDifference == 1) {
                why = "dY=+1 (tap) needs forward 3 or 4 but forward=" + forwardBlocks;
            } else if (heightDifference == 0) {
                why = "dY=0 (flat) needs forward 4 but forward=" + forwardBlocks;
            } else {
                why = "dY=" + heightDifference + " is neither +1 (tap) nor 0 (flat)";
            }
            decision = "IGNORED (no row): " + why;
            if (BridgeLog.isEnabled() && wasFacingOpposite
                    && (heightDifference == 0 || heightDifference == 1)
                    && (forwardBlocks < 0 || forwardBlocks > 4)) {
                BridgeLog.anomaly("FORWARD-OUT-OF-RANGE", "forward=" + forwardBlocks
                        + " blocks from the previous landing (baseline " + baselineSource + " block "
                        + baselineBlock + "). A real long jump is possible, but this also appears when "
                        + "an earlier landing was missed or its support block was mis-picked.");
            }
        }

        finishLandingRecord(supportPosition, landingNumber, landingTick,
                landingBlockCoordinate, landingY, baselineBlock, baselineSource, forwardBlocks,
                "dY=" + heightDifference + " (prevLandingY=" + previousLandingY + ")",
                wasFacingOpposite, landingCoordinate, landingFraction, previousTickCoordinate,
                previousTickFraction, jumpoverOffset, sprintingAtLanding,
                overlapsHitbox(supportPosition, landingSnapshot.playerBox), decision);
    }

    /**
     * Common tail of every own-block landing: advances the block and height baselines to THIS
     * landing (whether or not it produced a row) and writes the full decision trace to the log.
     */
    private void finishLandingRecord(BlockPos supportPosition, int landingNumber, int landingTick,
                                     int landingBlockCoordinate, int landingY,
                                     int baselineBlock, String baselineSource, int forwardBlocks,
                                     String heightText, boolean wasFacingOpposite,
                                     double landingCoordinate, double landingFraction,
                                     double previousTickCoordinate, double previousTickFraction,
                                     double finalOffset,
                                     boolean sprintingAtLanding, boolean supportUnderHitbox,
                                     String decision) {
        boolean hadBlock = hasLastLandingSupportBlock;
        int oldBlock = lastLandingSupportBlockCoordinate;
        boolean hadY = hasPreviousLanding;
        int oldY = previousLandingY;

        lastLandingSupportBlockCoordinate = landingBlockCoordinate;
        hasLastLandingSupportBlock = true;
        // Every own-block landing becomes the height reference for the next landing, even when
        // it is not reported because the facing or height condition did not match.
        previousLandingY = landingY;
        hasPreviousLanding = true;

        if (!BridgeLog.isEnabled()) {
            return;
        }
        int centreBlock = getDirectionalPlayerBlockCoordinate(landingCoordinate, attemptDirection);
        String takeoffText = "takeoffBlock=none";
        if (hasTakeoffSupportBlock) {
            int fromTakeoff = landingBlockCoordinate - takeoffSupportBlockCoordinate;
            takeoffText = "takeoffBlock=" + takeoffSupportBlockCoordinate + " jumpLengthFromTakeoff=" + fromTakeoff;
            if (hadBlock && fromTakeoff != forwardBlocks) {
                BridgeLog.anomaly("WALKED-BEFORE-JUMP", "this jump started on axis block "
                        + takeoffSupportBlockCoordinate + ", not on the previous landing block "
                        + oldBlock + ": the real jump length is " + fromTakeoff
                        + " but the Tap/Flat block-count rule uses " + forwardBlocks
                        + " (measured from the previous landing). Jumpover offset is fractional only. "
                        + "The player walked/slid "
                        + (takeoffSupportBlockCoordinate - oldBlock) + " block(s) on the ground before jumping.");
            }
        }
        hasTakeoffSupportBlock = false;
        BridgeLog.log("LAND", "#" + landingNumber + " support=" + BridgeLog.pos(supportPosition)
                + " rule=" + lastSupportRule
                + " | axisBlock=" + landingBlockCoordinate + " centreBlock=" + centreBlock
                + " baseline=" + baselineSource + ":" + baselineBlock
                + " forward=" + forwardBlocks + " " + takeoffText
                + " | " + heightText
                + " | facingOpposite=" + wasFacingOpposite + " (prevFacing=" + previousFacingDirection
                + ", dir=" + attemptDirection + ")"
                + " | coord=" + BridgeLog.f(landingCoordinate) + " frac=" + BridgeLog.f(landingFraction)
                + " prevTickCoord=" + BridgeLog.f(previousTickCoordinate)
                + " prevTickFrac=" + BridgeLog.f(previousTickFraction)
                + " | fractionalOffset=" + BridgeLog.f(finalOffset)
                + " (shown " + formatSignedDecimalOffset(finalOffset) + ")"
                + " | sprint=" + sprintingAtLanding
                + " | => " + decision);
        BridgeLog.log("BASELINE", "block " + (hadBlock ? Integer.toString(oldBlock) : "none") + " -> "
                + landingBlockCoordinate + " | landingY " + (hadY ? Integer.toString(oldY) : "none")
                + " -> " + landingY + " | coordFraction=" + BridgeLog.f(unifiedPreviousLandingFraction)
                + " (only Tap/Flat/failed rows move the coordinate-difference baseline)");
        if (!supportUnderHitbox) {
            BridgeLog.anomaly("SUPPORT-OUTSIDE-HITBOX", "selected support axis=" + landingBlockCoordinate
                    + " but the saved landing hitbox does not overlap it (centreAxis=" + centreBlock
                    + ", rule=" + lastSupportRule + "). The positional fallback selected a neighbouring block.");
        } else if (centreBlock != landingBlockCoordinate) {
            // This is expected when the player straddles a block edge. The furthest-forward
            // block can be the real support even while the player's centre floors to the old cell.
            BridgeLog.log("SUPPORT-EDGE", "centreAxis=" + centreBlock + " selectedSupportAxis="
                    + landingBlockCoordinate + " because the player's saved hitbox overlaps it"
                    + " (rule=" + lastSupportRule + ").");
        }
        landingTable.add(String.format(Locale.US,
                "#%d tick=%d support=%s axis=%d centre=%d fwd=%d %s opp=%s offset=%s -> %s",
                landingNumber, landingTick, BridgeLog.pos(supportPosition),
                landingBlockCoordinate, centreBlock, forwardBlocks, heightText, wasFacingOpposite,
                formatSignedDecimalOffset(finalOffset), decision));
    }

    /**
     * Counts grounded ticks after a recognized Tap/Flat. The opening Flat always waits through
     * the first post-landing tick before its row is revealed, irrespective of offset sign.
     * Samples are refreshed on each qualifying grounded tick so the final one is retained.
     */
    private void processPendingAdvancedLanding(EntityPlayer player) {
        PendingAdvancedLanding pending = pendingAdvancedLanding;
        if (pending == null || clientTickCounter <= pending.startClientTick) {
            return;
        }

        if (player.onGround) {
            // This pending opening-flat state is created only after the landing itself has
            // already been confirmed against the direction-aligned 3x3 support neighbourhood
            // and this attempt's own placed blocks. Count every grounded tick from that confirmed
            // landing. Requiring the grid to rediscover the same edge support on every later tick
            // drops valid 1.1/1.15+ openings when the player's center shifts at the block edge.
            // The 3x3 grid is still scanned every active client tick for new landings.
            pending.groundedTicks++;
            double currentCoordinate = getDirectionalCoordinate(
                    player.posX, player.posZ, attemptDirection
            );
            pending.previousGroundCoordinate = pending.lastGroundCoordinate;
            pending.lastGroundCoordinate = currentCoordinate;
            pending.sprintingOnLastGroundTick = player.isSprinting();
            return;
        }

        // Walking/falling off is not a jump. Keep the opening row pending until attempt failure;
        // for later landings, discard the unclassified pending result.
        if (player.motionY <= 0.0D) {
            if (!pending.openingFlat) {
                if (BridgeLog.isEnabled()) {
                    BridgeLog.log("ADV", "left the ground without a jump (motionY="
                            + BridgeLog.f(player.motionY) + ") after " + pending.groundedTicks
                            + " grounded tick(s) -> pending " + (pending.tap ? "Tap" : "Flat")
                            + " row '" + pending.result.label.trim() + "' keeps its plain label");
                }
                pendingAdvancedLanding = null;
            }
            return;
        }

        int groundedTicks = pending.groundedTicks;
        final String labelBefore = pending.openingFlat ? openingFlatType
                : (pending.result == null ? "(none)" : pending.result.label);
        if (pending.openingFlat) {
            // The initial Flat is resolved on the takeoff itself: immediate = normal 1.xx,
            // one grounded tick = Tapped 5b Flat, and each further tick adds 0.05.
            // A delayed flat or a "1.1 5b" (its 5th block is already the extra jumpover block)
            // makes the following 4-block Tap a plain "Tap N" instead of a "4b Tap".
            previousFlatWasDelayed = groundedTicks > 0
                    || (hasOpeningFlatResult && openingFlatType != null && openingFlatType.endsWith(" 5b"));
            if (groundedTicks == 0) {
                // Keep the original 1.05 / 1.1 / ... opening Flat row.
            } else if (groundedTicks == 1) {
                applyAdvancedSpeedResult(pending, "Tapped 5b Flat");
            } else if (groundedTicks > 1) {
                double adjustedFlatTime = calculateFlatTime(
                        pending.baseFlatElapsedTicks, groundedTicks
                );
                applyLongGroundedFlatResult(
                        pending, formatFlatTime(adjustedFlatTime) + " 5b Flat"
                );
            }
            openingFlatAwaitingAdvancedResolution = false;
            if (BridgeLog.isEnabled()) {
                BridgeLog.log("ADV", "OPENING FLAT resolved on takeoff: groundedTicks=" + groundedTicks
                        + " firstToLandingTicks=" + pending.baseFlatElapsedTicks
                        + " totalFlatTicks=" + (pending.baseFlatElapsedTicks + groundedTicks)
                        + " flatSeconds=" + BridgeLog.f(calculateFlatTime(
                                pending.baseFlatElapsedTicks, groundedTicks))
                        + " sprint=" + pending.sprintingOnLastGroundTick
                        + " lastGroundCoord=" + BridgeLog.f(pending.lastGroundCoordinate)
                        + " | label '" + labelBefore + "' -> '" + openingFlatType + "'"
                        + " | visible=" + hasOpeningFlatResult);
            }
            pendingAdvancedLanding = null;
            return;
        }

        if (!pending.tap) {
            previousFlatWasDelayed = groundedTicks > 0 && pending.forwardBlocks == 4;
        }

        if (groundedTicks == 0) {
            // The Tap / 4b Tap name was decided at landing using actual support-block progress
            // and whether the preceding Flat was left immediately or after a delayed run-up.
        } else if (groundedTicks == 1) {
            if (pending.tap) {
                if (pending.forwardBlocks == 4 && !pending.result.previousRowWas4b) {
                    applyAdvancedSpeedResult(pending, "4b Speed");
                } else if (pending.forwardBlocks == 3 || pending.forwardBlocks == 4) {
                    applyNegativeOffsetTapSpeed(pending);
                }
            } else if (pending.forwardBlocks == 4) {
                applyAdvancedSpeedResult(pending, "Tapped 5b Flat");
            }
        } else if (pending.tap && (pending.forwardBlocks == 3 || pending.forwardBlocks == 4)) {
            String speedLabel = pending.result != null && !pending.result.previousRowWas4b
                    && (pending.forwardBlocks == 4 || pending.result.jumpoverOffsetValue > 0.0D)
                    ? "4b Speed" : "Speed";
            applyLongGroundedTapResult(pending, groundedTicks + "t " + speedLabel);
        } else if (!pending.tap && pending.forwardBlocks == 4) {
            double adjustedFlatTime = calculateFlatTime(
                    pending.baseFlatElapsedTicks, groundedTicks
            );
            applyLongGroundedFlatResult(pending, formatFlatTime(adjustedFlatTime) + " 5b Flat");
        }

        if (BridgeLog.isEnabled()) {
            BridgeLog.log("ADV", (pending.tap ? "TAP" : "FLAT") + " resolved on takeoff: groundedTicks="
                    + groundedTicks + " forwardBlocks=" + pending.forwardBlocks
                    + " baseFlatTicks=" + pending.baseFlatElapsedTicks
                    + " sprint=" + pending.sprintingOnLastGroundTick
                    + " lastGroundCoord=" + BridgeLog.f(pending.lastGroundCoordinate)
                    + " | label '" + labelBefore.trim() + "' -> '"
                    + (pending.result == null ? "(none)" : pending.result.label.trim()) + "'");
        }
        pendingAdvancedLanding = null;
    }

    /**
     * A 3b Tap followed by one extra grounded tick is a normal Speed. A Speed is measured one tick
     * later than its Tap: the coordinate is the grounded tick's, and the Jumpover offset comes from
     * the landing tick (the tick before the grounded one). A positive offset makes it a 4b Speed.
     */
    private void applyNegativeOffsetTapSpeed(PendingAdvancedLanding pending) {
        if (pending.result == null) {
            return;
        }
        LandingResult result = pending.result;
        double shiftedOffset = getJumpoverOffset(
                getDisplayedCoordinateFraction(pending.previousGroundCoordinate)
        );
        result.jumpoverOffsetValue = shiftedOffset;
        result.jumpoverOffset = formatSignedDecimalOffset(shiftedOffset);
        result.jumpoverOffsetColor = getDifferenceColor(shiftedOffset);
        unifiedPreviousJumpoverOffset = shiftedOffset;
        hasUnifiedPreviousJumpoverOffset = true;

        if (shiftedOffset > 0.0D && !result.previousRowWas4b) {
            // Every 4b Speed shows its coordinate and Sprint / No Sprint.
            applyAdvancedSpeedResult(pending, "4b Speed");
            return;
        }
        result.label = "Speed - ";
        result.labelColor = 0xFF5BB8FF;
        result.showJumpoverOffset = true;
        result.coordinate = formatCoordinateDecimal(pending.lastGroundCoordinate);
        if (pending.hasPreviousLandingFraction) {
            double diff = getBlockAdjustedDifference(
                    pending.lastGroundCoordinate, pending.previousLandingCoordinate,
                    pending.tap ? 3 : 4
            );
            result.difference = formatCoordinateDifference(diff);
            result.differenceColor = getDifferenceColor(diff);
        }
        result.extraLine = null;
        updateDisplayedLandingCoordinateReference(pending, pending.lastGroundCoordinate);
    }

    /** Applies the one-tick and long-ground speed/flat variants. */
    private void applyAdvancedSpeedResult(PendingAdvancedLanding pending, String label) {
        if (label.contains("5b Flat")) {
            noteFiveBTiming(pending);
        } else if (label.equals("4b Speed") && pending.result != null) {
            pending.result.speedTicks = pending.groundedTicks;
        }
        String lastGroundDecimal = formatCoordinateDecimal(pending.lastGroundCoordinate);
        String sprintText = pending.sprintingOnLastGroundTick ? "Sprint" : "No Sprint";
        int sprintColor = pending.sprintingOnLastGroundTick ? 0xFF72F29A : 0xFFFF6677;

        if (pending.openingFlat) {
            if (!hasOpeningFlatResult) {
                return;
            }
            openingFlatType = label;
            openingFlatDisplayCoordinate = lastGroundDecimal;
            openingFlatSuffix = " - " + sprintText;
            openingFlatSuffixColor = sprintColor;
            openingFlatExtraLine = null;
            updateDisplayedLandingCoordinateReference(pending, pending.lastGroundCoordinate);
            return;
        }

        LandingResult result = pending.result;
        result.label = label + " - ";
        result.labelColor = pending.tap ? 0xFF5BB8FF : BridgeColors.FLAT_LABEL;
        result.coordinate = lastGroundDecimal;
        result.coordinateColor = 0xFFC4E6FF;
        result.difference = sprintText;
        result.differenceColor = sprintColor;
        result.differenceSeparator = " - ";
        result.showJumpoverOffset = true;
        String previousGroundDecimal = formatCoordinateDecimal(pending.previousGroundCoordinate);
        result.extraLine = previousGroundDecimal + " -1t";
        result.extraLineColor = BridgeColors.EXTRA_LINE;
        if (pending.tap && pending.hasPreviousLandingFraction) {
            double groundDifference = getBlockAdjustedDifference(
                    pending.previousGroundCoordinate, pending.previousLandingCoordinate,
                    pending.tap ? 3 : 4
            );
            result.extraLine += " " + formatCoordinateDifference(groundDifference);
            result.extraLineColor = getDifferenceColor(groundDifference);
        }
        updateDisplayedLandingCoordinateReference(pending, pending.lastGroundCoordinate);
    }

    /** Longer Tap run-ups use only the grounded-tick technique label, as requested. */
    private void applyLongGroundedTapResult(PendingAdvancedLanding pending, String label) {
        if (pending.openingFlat) {
            // The opening landing is a Flat, so this is only defensive.
            return;
        }
        LandingResult result = pending.result;
        if (label.contains("4b Speed")) {
            result.speedTicks = pending.groundedTicks;
        }
        result.label = label;
        result.labelColor = 0xFF5BB8FF;
        result.coordinate = "";
        result.difference = "";
        if (label.contains("4b Speed")) {
            // Every 4b Speed shows whether the player was sprinting on the last ground tick.
            result.difference = pending.sprintingOnLastGroundTick ? "Sprint" : "No Sprint";
            result.differenceColor = pending.sprintingOnLastGroundTick ? 0xFF72F29A : 0xFFFF6677;
            result.differenceSeparator = " - ";
        }
        result.showJumpoverOffset = true;
        result.extraLine = null;
    }

    /** Long-ground Flat uses 1.05 plus 0.05 per extra grounded tick before takeoff. */
    private void applyLongGroundedFlatResult(PendingAdvancedLanding pending, String label) {
        noteFiveBTiming(pending);
        String lastGroundDecimal = formatCoordinateDecimal(pending.lastGroundCoordinate);
        String previousGroundDecimal = formatCoordinateDecimal(pending.previousGroundCoordinate);
        String sprintText = pending.sprintingOnLastGroundTick ? "Sprint" : "No Sprint";
        int sprintColor = pending.sprintingOnLastGroundTick ? 0xFF72F29A : 0xFFFF6677;

        if (pending.openingFlat) {
            if (!hasOpeningFlatResult) {
                return;
            }
            openingFlatType = label;
            openingFlatDisplayCoordinate = lastGroundDecimal;
            openingFlatSuffix = " - " + sprintText;
            openingFlatSuffixColor = sprintColor;
            openingFlatExtraLine = previousGroundDecimal + " -1t";
            updateDisplayedLandingCoordinateReference(pending, pending.lastGroundCoordinate);
            return;
        }

        LandingResult result = pending.result;
        result.label = label + " - ";
        result.labelColor = BridgeColors.FLAT_LABEL;
        result.coordinate = lastGroundDecimal;
        result.coordinateColor = 0xFF9CFFB5;
        result.difference = sprintText;
        result.differenceColor = sprintColor;
        result.differenceSeparator = " - ";
        result.showJumpoverOffset = true;
        result.extraLine = null;
        result.extraLineColor = BridgeColors.EXTRA_LINE;
        updateDisplayedLandingCoordinateReference(pending, pending.lastGroundCoordinate);
    }

    /** Stores the numbers the personal-best statistics need on a freshly created Tap / Flat row. */
    private void fillLandingStats(LandingResult landing, int landingNumber, boolean tap,
                                  double landingCoordinate, boolean hasPrevious, double gainValue) {
        // A failed row's coordinate is a mid-air sample, never a ground tick to measure from.
        boolean usable = hasPrevious && !(previousRowResult != null
                && previousRowResult.label.startsWith("FAILED"));
        landing.landingNumber = landingNumber;
        landing.tap = tap;
        landing.statCoordinate = landingCoordinate;
        landing.statHasPrev = usable;
        landing.statPrevCoordinate = unifiedPreviousLandingCoordinate;
        landing.statHasGain = usable;
        landing.statGainThousandths = usable ? Math.round(gainValue * 1000.0D) : 0L;
    }

    /** Records the flat time of a 5b flat the moment its grounded-tick count is known. */
    private void noteFiveBTiming(PendingAdvancedLanding pending) {
        double seconds = calculateFlatTime(pending.baseFlatElapsedTicks, pending.groundedTicks);
        if (pending.openingFlat) {
            openingFlatTimingSeconds = seconds;
        } else if (pending.result != null) {
            pending.result.flatTimingSeconds = seconds;
        }
    }

    /**
     * Feeds rows whose label / coordinate can no longer change to the personal-best statistics,
     * in order. A row is final once it is no longer the pending advanced landing; {@code force}
     * (attempt over) treats everything that is left as final too.
     */
    private void finalizeBestStats(boolean force) {
        if (!openingStatsDone && hasOpeningFlatResult
                && (force || !openingFlatAwaitingAdvancedResolution || !landingResults.isEmpty())) {
            openingStatsDone = true;
            boolean plain = openingFlatType != null && !openingFlatType.contains("5b");
            bestTracker.openingFlat(plain, openingFlatTimingSeconds, openingFlatStatCoordinate,
                    openingFlatLandingNumber);
        }
        for (LandingResult row : landingResults) {
            if (row.statsRecorded) {
                continue;
            }
            boolean stillPending = pendingAdvancedLanding != null && pendingAdvancedLanding.result == row;
            if (stillPending && !force) {
                break;
            }
            row.statsRecorded = true;
            BridgeBests.Row data = new BridgeBests.Row();
            data.failed = row.landingNumber <= 0;
            data.landingNumber = row.landingNumber;
            data.tap = row.tap;
            data.held = row.label != null
                    && (row.label.contains("Speed") || row.label.contains("Tapped 5b Flat"));
            data.coordinate = row.statCoordinate;
            data.hasGain = row.statHasGain;
            data.gain = row.statGainThousandths;
            data.hasPrev = row.statHasPrev;
            data.prevCoordinate = row.statPrevCoordinate;
            data.jumpoverOffset = row.jumpoverOffsetValue;
            data.flatTimingSeconds = row.flatTimingSeconds;
            data.speedTicks = row.speedTicks;
            bestTracker.row(data);
        }
    }

    /** Keep cross-type differences and the Turnout delta aligned with the decimal shown in the row. */
    private void updateDisplayedLandingCoordinateReference(PendingAdvancedLanding pending,
                                                             double displayedCoordinate) {
        double fraction = getDisplayedCoordinateFraction(displayedCoordinate);
        if (pending.openingFlat) {
            openingFlatStatCoordinate = displayedCoordinate;
        } else if (pending.result != null) {
            pending.result.statCoordinate = displayedCoordinate;
            if (pending.hasPreviousLandingFraction && pending.result.statHasPrev) {
                pending.result.statHasGain = true;
                pending.result.statGainThousandths = Math.round(getBlockAdjustedDifference(
                        displayedCoordinate, pending.previousLandingCoordinate,
                        pending.tap ? 3 : 4) * 1000.0D);
            }
        }
        unifiedPreviousLandingCoordinate = displayedCoordinate;
        unifiedPreviousLandingFraction = fraction;
        hasUnifiedPreviousLandingCoordinate = true;
        if (turnoutLandingAwaitingTakeoff) {
            if (pending.openingFlat && turnoutLandingIsOpeningFlat) {
                turnoutLandingReferenceFraction = fraction;
            } else if (!pending.openingFlat && turnoutLandingResult == pending.result) {
                turnoutLandingReferenceFraction = fraction;
            }
        }
    }

    private void setTurnoutLandingAnchor(LandingResult result, boolean openingFlat,
                                         double landingFraction) {
        turnoutLandingResult = result;
        turnoutLandingIsOpeningFlat = openingFlat;
        turnoutLandingReferenceFraction = landingFraction;
        turnoutLandingAwaitingTakeoff = true;
    }

    private void clearTurnoutLandingAnchor() {
        turnoutLandingResult = null;
        turnoutLandingIsOpeningFlat = false;
        turnoutLandingAwaitingTakeoff = false;
        turnoutLandingReferenceFraction = 0.0D;
    }

    /** Starts the 7-tick facing validation window on the jump immediately after a landing. */
    private void beginTurnoutForecastAtTakeoff() {
        if (!turnoutLandingAwaitingTakeoff || attemptDirection == null
                || !openingFlatDetectionEnabled
                || !BridgeConfig.isEnabled(BridgeConfig.Setting.TURNOUT)) {
            clearTurnoutLandingAnchor();
            return;
        }
        if (BridgeLog.isEnabled()) {
            BridgeLog.log("TURNOUT", "forecast armed at takeoff for "
                    + (turnoutLandingIsOpeningFlat ? "the opening flat"
                    : turnoutLandingResult == null ? "(no row)" : "row '" + turnoutLandingResult.label.trim() + "'")
                    + " referenceFraction=" + BridgeLog.f(turnoutLandingReferenceFraction));
        }
        pendingTurnouts.add(new PendingTurnout(
                turnoutLandingResult,
                turnoutLandingIsOpeningFlat,
                attemptDirection,
                clientTickCounter,
                turnoutLandingReferenceFraction
        ));
        clearTurnoutLandingAnchor();
    }

    /**
     * Verifies the player's facing direction for seven live ticks after takeoff. At the end of
     * that window, runs seven additional ticks through a detached vanilla 1.8.9 EntityPlayer,
     * freezing the then-current key state and yaw/pitch for the complete projection (14 ticks
     * total after takeoff).
     */
    private void processPendingTurnouts(EntityPlayer player, Minecraft minecraft) {
        if (pendingTurnouts.isEmpty() || player == null || minecraft == null
                || minecraft.gameSettings == null) {
            return;
        }

        Iterator<PendingTurnout> iterator = pendingTurnouts.iterator();
        while (iterator.hasNext()) {
            PendingTurnout pending = iterator.next();
            if (clientTickCounter <= pending.takeoffClientTick) {
                continue;
            }

            String currentFacing = getHorizontalFacingDirection(player.rotationYaw);
            if (currentFacing == null || isOppositeDirection(currentFacing, pending.direction)) {
                if (BridgeLog.isEnabled()) {
                    BridgeLog.log("TURNOUT", "forecast CANCELLED: facing " + currentFacing + " is opposite of "
                            + pending.direction + " after " + pending.verifiedFacingTicks
                            + " verified tick(s)");
                }
                iterator.remove();
                continue;
            }

            pending.verifiedFacingTicks++;
            if (pending.verifiedFacingTicks < TURNOUT_FACING_CHECK_TICKS) {
                continue;
            }

            net.minecraft.client.settings.GameSettings settings = minecraft.gameSettings;
            float fixedYaw = player.rotationYaw;
            float fixedPitch = player.rotationPitch;
            TurnoutSimulator simulator = new TurnoutSimulator(
                    player,
                    net.minecraft.client.settings.GameSettings.isKeyDown(settings.keyBindForward),
                    net.minecraft.client.settings.GameSettings.isKeyDown(settings.keyBindBack),
                    net.minecraft.client.settings.GameSettings.isKeyDown(settings.keyBindLeft),
                    net.minecraft.client.settings.GameSettings.isKeyDown(settings.keyBindRight),
                    net.minecraft.client.settings.GameSettings.isKeyDown(settings.keyBindJump),
                    net.minecraft.client.settings.GameSettings.isKeyDown(settings.keyBindSneak),
                    net.minecraft.client.settings.GameSettings.isKeyDown(settings.keyBindSprint)
            );
            simulator.simulate(TURNOUT_PROJECTION_TICKS, fixedYaw, fixedPitch);

            double projectedCoordinate = getDirectionalCoordinate(
                    simulator.posX, simulator.posZ, pending.direction
            );
            String decimal = formatCoordinateDecimal(projectedCoordinate);
            double projectedFraction = getDisplayedCoordinateFraction(projectedCoordinate);
            double turnoutDifference = getWrappedCoordinateDifference(
                    projectedFraction, pending.referenceLandingFraction
            );
            BridgeBests.offerTurnout(Math.round(turnoutDifference * 1000.0D));
            String differenceText = formatCoordinateDifference(turnoutDifference);
            int differenceColor = getDifferenceColor(turnoutDifference);
            if (BridgeLog.isEnabled()) {
                BridgeLog.log("TURNOUT", "forecast done: projected=" + BridgeLog.f(projectedCoordinate)
                        + " shown=" + decimal + " diff=" + differenceText + " (reference "
                        + BridgeLog.f(pending.referenceLandingFraction) + ")");
            }
            if (pending.openingFlat) {
                openingFlatTurnout = decimal;
                openingFlatTurnoutDifference = differenceText;
            } else if (pending.result != null) {
                pending.result.turnout = decimal;
                pending.result.turnoutDifference = differenceText;
                pending.result.turnoutDifferenceColor = differenceColor;
            }
            iterator.remove();
        }
    }

    /** Begins a failed-jump sequence at takeoff and saves the last grounded tick's Y. */
    private void startFailedJumpSequence(EntityPlayer player) {
        failedJumpSequenceActive = true;
        waitingForOppositeFacing = true;
        failedJumpStartClientTick = clientTickCounter;
        // previousPosY was captured at the end of the tick when the player was still grounded.
        failedJumpPreviousGroundY = hasPreviousPosition ? previousPosY : player.posY;
        hasFailedTapSampleCoordinate = false;
        failedTapSampleCoordinate = 0.0D;
        hasFailedTapSamplePreviousCoordinate = false;
        failedTapSamplePreviousCoordinate = 0.0D;
        hasFailedFlatSampleCoordinate = false;
        failedFlatSampleCoordinate = 0.0D;
        hasFailedFlatSamplePreviousCoordinate = false;
        failedFlatSamplePreviousCoordinate = 0.0D;
        pendingFailedLandingType = FailedLandingType.NONE;
        pendingFailedLandingSampleTick = 0;
        if (BridgeLog.isEnabled()) {
            BridgeLog.log("FAILED", "sequence STARTED at takeoff (facing along the attempt); previousGroundY="
                    + BridgeLog.f(failedJumpPreviousGroundY)
                    + " -> waiting for the player to face opposite, then sampling +8 (tap) / +11 (flat)");
        }
    }

    /** Captures the coordinate on takeoff+8 and takeoff+11, while the player is airborne. */
    private void captureFailedJumpSamples(EntityPlayer player) {
        if (!failedJumpSequenceActive || player.onGround) {
            return;
        }

        int elapsed = clientTickCounter - failedJumpStartClientTick;
        double coordinate = getDirectionalCoordinate(player.posX, player.posZ, attemptDirection);
        double previousTickCoordinate = getPreviousTickDirectionalCoordinate(coordinate);
        if (BridgeLog.isEnabled() && (elapsed == 8 || elapsed == 11)) {
            BridgeLog.log("FAILED", "sample at takeoff+" + elapsed + ": coord=" + BridgeLog.f(coordinate)
                    + " prevTickCoord=" + BridgeLog.f(previousTickCoordinate)
                    + " axisBlock=" + getDirectionalPlayerBlockCoordinate(coordinate, attemptDirection)
                    + " y=" + BridgeLog.f(player.posY));
        }
        if (elapsed == 8) {
            failedTapSampleCoordinate = coordinate;
            hasFailedTapSampleCoordinate = true;
            failedTapSamplePreviousCoordinate = previousTickCoordinate;
            hasFailedTapSamplePreviousCoordinate = true;
        } else if (elapsed == 11) {
            failedFlatSampleCoordinate = coordinate;
            hasFailedFlatSampleCoordinate = true;
            failedFlatSamplePreviousCoordinate = previousTickCoordinate;
            hasFailedFlatSamplePreviousCoordinate = true;
        }
    }

    /** The opposite-facing tick classifies the sequence using the sign of vertical velocity. */
    private void classifyFailedJump(EntityPlayer player, FailedLandingType type, int sampleTick) {
        pendingFailedLandingType = type;
        pendingFailedLandingSampleTick = sampleTick;
        if (BridgeLog.isEnabled()) {
            BridgeLog.log("FAILED", "opposite facing reached: motionY=" + BridgeLog.f(player.motionY)
                    + " -> classified " + type + " (sample tick takeoff+" + sampleTick + ", elapsed="
                    + (clientTickCounter - failedJumpStartClientTick) + ", y=" + BridgeLog.f(player.posY)
                    + ", needs y < " + BridgeLog.f(failedJumpPreviousGroundY) + ")");
        }
        // If the sample tick has already passed, this can publish immediately once the
        // previous-ground-Y threshold is met. Otherwise processPendingFailedLanding waits.
        processPendingFailedLanding(player);
    }

    /**
     * Shows the failed result only after its takeoff-relative sample tick AND after the
     * player's Y drops below the last grounded tick's Y. Any landing cancels the failure.
     */
    private void processPendingFailedLanding(EntityPlayer player) {
        if (pendingFailedLandingType == FailedLandingType.NONE) {
            return;
        }
        if (player.onGround) {
            cancelFailedJumpSequence("player touched down before the failed result was shown");
            return;
        }

        int elapsed = clientTickCounter - failedJumpStartClientTick;
        if (elapsed < pendingFailedLandingSampleTick
                || player.posY >= failedJumpPreviousGroundY) {
            return;
        }

        double coordinate = getFailedJumpSampleCoordinate(player, pendingFailedLandingType);
        double previousTickCoordinate = getFailedJumpSamplePreviousCoordinate(
                pendingFailedLandingType, coordinate
        );
        recordFailedLanding(coordinate, previousTickCoordinate, pendingFailedLandingType);
        cancelFailedJumpSequence("failed row published");
    }

    private double getFailedJumpSampleCoordinate(EntityPlayer player, FailedLandingType type) {
        if (type == FailedLandingType.TAP && hasFailedTapSampleCoordinate) {
            return failedTapSampleCoordinate;
        }
        if (type == FailedLandingType.FLAT && hasFailedFlatSampleCoordinate) {
            return failedFlatSampleCoordinate;
        }
        // Defensive fallback for a skipped client tick; normal play captures the exact sample.
        return getDirectionalCoordinate(player.posX, player.posZ, attemptDirection);
    }

    private double getFailedJumpSamplePreviousCoordinate(FailedLandingType type,
                                                          double fallbackCoordinate) {
        if (type == FailedLandingType.TAP && hasFailedTapSamplePreviousCoordinate) {
            return failedTapSamplePreviousCoordinate;
        }
        if (type == FailedLandingType.FLAT && hasFailedFlatSamplePreviousCoordinate) {
            return failedFlatSamplePreviousCoordinate;
        }
        // Defensive fallback for a skipped sample tick.
        return getPreviousTickDirectionalCoordinate(fallbackCoordinate);
    }

    private void cancelFailedJumpSequence() {
        cancelFailedJumpSequence("reset (attempt start/end, respawn or normal completion)");
    }

    private void cancelFailedJumpSequence(String reason) {
        if (BridgeLog.isEnabled() && (failedJumpSequenceActive
                || pendingFailedLandingType != FailedLandingType.NONE)) {
            BridgeLog.log("FAILED", "sequence CANCELLED/ENDED: " + reason
                    + " (type=" + pendingFailedLandingType + ")");
        }
        failedJumpSequenceActive = false;
        waitingForOppositeFacing = false;
        failedJumpStartClientTick = 0;
        hasFailedTapSampleCoordinate = false;
        failedTapSampleCoordinate = 0.0D;
        hasFailedTapSamplePreviousCoordinate = false;
        failedTapSamplePreviousCoordinate = 0.0D;
        hasFailedFlatSampleCoordinate = false;
        failedFlatSampleCoordinate = 0.0D;
        hasFailedFlatSamplePreviousCoordinate = false;
        failedFlatSamplePreviousCoordinate = 0.0D;
        pendingFailedLandingType = FailedLandingType.NONE;
        pendingFailedLandingSampleTick = 0;
        failedJumpPreviousGroundY = 0.0D;
    }

    /** Adds a FAILED TAP / FAILED FLAT row using the shared Tap/Flat coordinate history. */
    private void recordFailedLanding(double coordinate, double previousTickCoordinate,
                                      FailedLandingType type) {
        if (type == FailedLandingType.NONE) {
            return;
        }

        double fraction = getDisplayedCoordinateFraction(coordinate);
        boolean isTap = type == FailedLandingType.TAP;
        double differenceValue = hasUnifiedPreviousLandingCoordinate
                ? getBlockAdjustedDifference(coordinate, unifiedPreviousLandingCoordinate, isTap ? 3 : 4)
                : 0.0D;
        String difference = hasUnifiedPreviousLandingCoordinate
                ? formatCoordinateDifference(differenceValue) : "(n/a)";
        int differenceColor = hasUnifiedPreviousLandingCoordinate
                ? getDifferenceColor(differenceValue) : 0xFF9AA8BA;
        int sampledBlockCoordinate = getDirectionalPlayerBlockCoordinate(coordinate, attemptDirection);
        // The reference is the support block of the last REAL landing. A failed sample is a
        // mid-air position, so it never becomes a landing baseline itself.
        int referenceBlockCoordinate = hasLastLandingSupportBlock
                ? lastLandingSupportBlockCoordinate : attemptStartBlockCoordinate;
        String referenceSource = hasLastLandingSupportBlock ? "previous-landing"
                : hasAttemptStartBlockCoordinate ? "first-placed-block" : "none";
        int failedForwardBlocks = hasLastLandingSupportBlock || hasAttemptStartBlockCoordinate
                ? Math.max(0, sampledBlockCoordinate - referenceBlockCoordinate) : 0;
        // Failed rows use the tick immediately before their takeoff-relative sample tick.
        double decimalOffset = getJumpoverOffset(
                getDisplayedCoordinateFraction(previousTickCoordinate)
        );
        double jumpoverOffset = decimalOffset;

        LandingResult failed = new LandingResult(
                isTap ? "FAILED TAP - " : "FAILED FLAT - ",
                formatCoordinateDecimal(coordinate),
                isTap ? BridgeColors.FAILED_TAP : BridgeColors.FAILED_FLAT,
                isTap ? BridgeColors.FAILED_TAP_VALUE : BridgeColors.FAILED_FLAT_VALUE,
                difference,
                differenceColor,
                formatSignedDecimalOffset(jumpoverOffset),
                getDifferenceColor(jumpoverOffset)
        );
        // Failed rows count towards the personal bests too (landingNumber stays <= 0).
        boolean failedUsable = hasUnifiedPreviousLandingCoordinate && !(previousRowResult != null
                && previousRowResult.label.startsWith("FAILED"));
        failed.tap = isTap;
        failed.statCoordinate = coordinate;
        failed.statHasGain = failedUsable;
        failed.statGainThousandths = failedUsable ? Math.round(differenceValue * 1000.0D) : 0L;
        failed.statHasPrev = failedUsable;
        failed.jumpoverOffsetValue = jumpoverOffset;
        landingResults.add(failed);
        previousRowResult = failed;
        previousRowIsOpeningFlat = false;
        unifiedPreviousLandingFraction = fraction;
        unifiedPreviousLandingCoordinate = coordinate;
        hasUnifiedPreviousLandingCoordinate = true;
        unifiedPreviousJumpoverOffset = jumpoverOffset;
        hasUnifiedPreviousJumpoverOffset = true;

        if (BridgeLog.isEnabled()) {
            String offsetRule = "fractional-wrap[-.500,+.500]";
            BridgeLog.log("FAILED", "ROW PUBLISHED " + (isTap ? "FAILED TAP" : "FAILED FLAT")
                    + " coord=" + BridgeLog.f(coordinate) + " shown=" + formatCoordinateDecimal(coordinate)
                    + " diff=" + difference
                    + " | sampledAxisBlock=" + sampledBlockCoordinate + " reference=" + referenceSource
                    + ":" + referenceBlockCoordinate + " forward=" + failedForwardBlocks
                    + " | prevTickCoord=" + BridgeLog.f(previousTickCoordinate)
                    + " decimalOffset=" + BridgeLog.f(decimalOffset)
                    + " offsetRule=" + offsetRule
                    + " finalOffset=" + BridgeLog.f(jumpoverOffset)
                    + " (shown " + formatSignedDecimalOffset(jumpoverOffset) + ")");
        }
    }

    /** Fractional coordinate rounded exactly as it appears in the HUD (0.000 through 0.999). */
    private double getDisplayedCoordinateFraction(double coordinate) {
        String formatted = String.format(Locale.US, "%.3f", Math.abs(coordinate));
        int decimalPoint = formatted.indexOf('.');
        int thousandths = Integer.parseInt(formatted.substring(decimalPoint + 1));
        // A rounded .9996 displays as .000 after carrying into the next block.
        return thousandths / 1000.0D;
    }

    /**
     * Compare displayed fractional coordinates while accounting for crossing a block boundary.
     * For example, .995 -> .012 is +.017 rather than -.983.
     */
    private double getWrappedCoordinateDifference(double current, double previous) {
        double difference = current - previous;
        if (difference > 0.5D) {
            difference -= 1.0D;
        } else if (difference < -0.5D) {
            difference += 1.0D;
        }
        return Math.round(difference * 1000.0D) / 1000.0D;
    }

    /** "2t late" for a late click, "1t early" for one made before the bed was in reach. */
    private static String formatBedClickTiming(int ticks) {
        return ticks < 0 ? (-ticks) + "t early" : ticks + "t late";
    }

    /**
     * Gain over the previous landing: (current - previous) along the travel direction minus the
     * number of blocks the jump covers (3 for a tap, 4 for a flat; height does not matter).
     * Done in whole thousandths so the result matches the three decimals shown in the HUD.
     */
    private double getBlockAdjustedDifference(double current, double previous, int blocks) {
        long thousandths = Math.round(current * 1000.0D) - Math.round(previous * 1000.0D)
                - blocks * 1000L;
        return thousandths / 1000.0D;
    }

    private String formatCoordinateDifference(double difference) {
        long thousandths = Math.round(Math.abs(difference) * 1000.0D);
        String decimal = thousandths >= 1000
                ? String.format(Locale.US, "%d.%03d", thousandths / 1000, thousandths % 1000)
                : String.format(Locale.US, ".%03d", thousandths);
        String sign = difference > 0.0D ? "+" : difference < 0.0D ? "-" : "";
        return "(" + sign + decimal + ")";
    }

    private int getDifferenceColor(double difference) {
        if (difference > 0.0D) {
            return 0xFF72F29A;
        }
        if (difference < 0.0D) {
            return 0xFFFF6677;
        }
        return 0xFFB9C3D0;
    }

    private int getDifferenceColorFromFormatted(String difference) {
        if (difference == null || difference.length() < 3) {
            return 0xFFB9C3D0;
        }
        if (difference.charAt(1) == '+') {
            return 0xFF72F29A;
        }
        if (difference.charAt(1) == '-') {
            return 0xFFFF6677;
        }
        return 0xFFB9C3D0;
    }

    /**
     * Jumpover offset uses only the previous tick's fractional coordinate. Subtract .300 and
     * wrap once across the block boundary so the result is always between -.500 and +.500.
     * Examples: .700 -> +.400; .804 -> -.496. Tap/Flat block counts never enter this math.
     */
    private double getJumpoverOffset(double previousTickFraction) {
        double offset = previousTickFraction - 0.300D;
        if (offset > 0.500D) {
            offset -= 1.000D;
        } else if (offset < -0.500D) {
            offset += 1.000D;
        }
        return Math.round(offset * 1000.0D) / 1000.0D;
    }

    /**
     * Returns the direction-normalized coordinate from the immediately preceding client tick.
     * The fallback is used only before a previous position sample exists.
     */
    private double getPreviousTickDirectionalCoordinate(double fallbackCoordinate) {
        if (hasPreviousPosition) {
            return getDirectionalCoordinate(previousPosX, previousPosZ, attemptDirection);
        }
        return fallbackCoordinate;
    }

    /** Formats a signed fractional offset only, e.g. +.400 or -.496. */
    private String formatSignedDecimalOffset(double offset) {
        long thousandths = Math.round(Math.abs(offset) * 1000.0D);
        // Defensive clamp: the calculation above should already enforce this range.
        thousandths = Math.min(500L, thousandths);
        String decimal = String.format(Locale.US, ".%03d", thousandths);
        if (offset > 0.0D) {
            return "+" + decimal;
        }
        if (offset < 0.0D) {
            return "-" + decimal;
        }
        return decimal;
    }

    /**
     * Flat time is derived from one tick-gap formula everywhere: 3 elapsed ticks = 1.05,
     * 4 = 1.10, 5 = 1.15. Extra grounded ticks extend the same base rather than using
     * a separate hard-coded 1.05 branch. The opening Flat supplies its measured placement
     * -> landing gap; later 4b Flats supply the standard three-tick base.
     */
    private double calculateFlatTime(int baseElapsedTicks, int extraGroundedTicks) {
        int totalElapsedTicks = Math.max(0, baseElapsedTicks) + Math.max(0, extraGroundedTicks);
        return 0.90D + totalElapsedTicks * TIMER_STEP_SECONDS;
    }

    private String formatFlatTime(double seconds) {
        String formatted = String.format(Locale.US, "%.2f", seconds);
        while (formatted.indexOf('.') >= 0 && formatted.endsWith("0")) {
            formatted = formatted.substring(0, formatted.length() - 1);
        }
        if (formatted.endsWith(".")) {
            formatted = formatted.substring(0, formatted.length() - 1);
        }
        return formatted;
    }

    /**
     * True when a click not yet confirmed by the client targets one of the saved landing's
     * actual under-hitbox support cells. In that case an older neighbouring own block must not
     * win merely because its block update arrived first.
     */
    /** True when the last support rule picked an older (pre-landing-tick) block. */
    private boolean lastSupportRuleIsEarly() {
        return lastSupportRule != null && (lastSupportRule.startsWith("physical-early")
                || lastSupportRule.startsWith("opening-run-early")
                || lastSupportRule.startsWith("tracked-early"));
    }

    /**
     * @param earlyCandidate the chosen support is an older block. A click still unresolved on the
     *        landing tick then cannot beat it, except at the opening landing when that click is
     *        directly under the player's centre. Waiting on such a click used to delay the
     *        landing past the takeoff tick, so the following Tap/Flat never armed or logged.
     */
    private boolean hasPendingPlacementInSupportGrid(World world, PendingLanding landing,
                                                     boolean earlyCandidate) {
        BlockPos[] grid = buildSupportGrid(landing.playerBox, landing.posX, landing.posZ);
        if (grid == null || pendingPlacements.isEmpty()) {
            return false;
        }
        if (earlyCandidate && (hasPreviousLanding || countEarlyOpeningBlocks(world, landing) >= 4)) {
            return false;
        }
        for (PendingPlacement pending : pendingPlacements) {
            if (earlyCandidate && !pending.position.equals(grid[0])) {
                continue;
            }
            if (!overlapsHitbox(pending.position, landing.playerBox)) {
                continue;
            }
            // A click made after the landing tick can never have supported this landing.
            if (pending.placementClientTick > landing.clientTick) {
                continue;
            }
            for (BlockPos cell : grid) {
                if (cell.equals(pending.position)) {
                    Block current = world.getBlockState(pending.position).getBlock();
                    if (current == Blocks.air || !ownPlacedBlocks.contains(pending.position)) {
                        return true;
                    }
                }
            }
        }
        return false;
    }

    /**
     * Lists confirmed own blocks in the saved support grid that were first registered on or
     * after this landing tick. They remain tracked for later landings; only this opening-flat
     * support selection excludes them.
     */
    private String describeExcludedLandingTickPlacements(World world, PendingLanding landing) {
        BlockPos[] grid = buildSupportGrid(landing.playerBox, landing.posX, landing.posZ);
        if (grid == null) {
            return "no-grid";
        }
        StringBuilder out = new StringBuilder();
        for (int i = 0; i < grid.length; i++) {
            BlockPos position = grid[i];
            if (!ownPlacedBlocks.contains(position)
                    || world.getBlockState(position).getBlock() == Blocks.air) {
                continue;
            }
            int ageClass = placementAgeClass(position, landing.clientTick);
            if (out.length() > 0) {
                out.append(';');
            }
            out.append(SUPPORT_GRID_LABELS[i]).append(BridgeLog.pos(position)).append("@t")
                    .append(ownPlacedAtTick.get(position))
                    .append(ageClass < 0 ? "[early]" : ageClass == 0 ? "[same-tick]" : "[LATE-excluded]");
        }
        return out.length() == 0 ? "none" : out.toString();
    }

    /** -1 = placed before the landing tick (or unknown), 0 = on the landing tick, 1 = after it. */
    private int placementAgeClass(BlockPos position, int landingTick) {
        Integer placedAt = ownPlacedAtTick.get(position);
        if (placedAt == null || placedAt.intValue() < landingTick) {
            return -1;
        }
        return placedAt.intValue() == landingTick ? 0 : 1;
    }

    /**
     * Resolve a delayed landing against its original snapshot. A later placement must never
     * change which block this landing established as the baseline.
     */
    private void processPendingLanding(World world) {
        PendingLanding pending = pendingLanding;
        if (pending == null) {
            return;
        }

        BlockPos candidate = null;
        // Only a centre-cell, landing-tick support is immune to re-evaluation. Any other pick is
        // re-run while a click is unresolved, because that click may be the real support.
        if (pending.lockedStable && pending.lockedSupportPosition != null
                && isTrackedPlacementAt(world, pending.lockedSupportPosition, pending)) {
            candidate = pending.lockedSupportPosition;
            lastSupportRule = "locked-landing-support";
        } else {
            candidate = findOwnPlacedSupport(world, pending);
            if (candidate != null) {
                pending.lockedSupportPosition = candidate;
                pending.lockedStable = lastSupportRule.startsWith("centre-same-tick");
            }
        }

        boolean unresolvedPlacement = hasPendingPlacementInSupportGrid(
                world, pending, candidate != null && lastSupportRuleIsEarly());
        int age = clientTickCounter - pending.clientTick;
        boolean stableOpeningSupport = pending.lockedStable && candidate != null;

        if (candidate != null && (!unresolvedPlacement || stableOpeningSupport)) {
            pendingLanding = null;
            if (BridgeLog.isEnabled()) {
                BridgeLog.log("LAND-RESOLVE", "saved landing tick=" + pending.clientTick
                        + " resolved after=" + age + " support=" + BridgeLog.pos(candidate)
                        + " rule=" + lastSupportRule + " locked="
                        + BridgeLog.pos(pending.lockedSupportPosition)
                        + " openingSameTickExclusion=" + pending.excludeLandingTickPlacements
                        + " grid=" + describeGridDetailed(world, pending.playerBox,
                                pending.posX, pending.posZ));
            }
            groundedSupportBlockCoordinate = getDirectionalBlockCoordinate(candidate, attemptDirection);
            hasGroundedSupportBlock = true;
            recordLanding(candidate, pending);
            return;
        }

        if (age >= PENDING_LANDING_MAX_AGE) {
            pendingLanding = null;
            if (candidate != null) {
                if (BridgeLog.isEnabled()) {
                    BridgeLog.log("LAND-RESOLVE", "verification timed out; resolving with locked support="
                            + BridgeLog.pos(candidate) + " unresolvedPlacement=" + unresolvedPlacement);
                }
                groundedSupportBlockCoordinate = getDirectionalBlockCoordinate(candidate, attemptDirection);
                hasGroundedSupportBlock = true;
                recordLanding(candidate, pending);
            } else {
                resolveLandingWithFallback(world, pending, "no eligible support after " + age + " ticks");
            }
            return;
        }

        if (BridgeLog.isEnabled()) {
            BridgeLog.log("LAND-WAIT", "age=" + age + "/" + PENDING_LANDING_MAX_AGE
                    + " candidate=" + BridgeLog.pos(candidate)
                    + " locked=" + BridgeLog.pos(pending.lockedSupportPosition)
                    + " unresolvedPlacement=" + unresolvedPlacement
                    + " openingSameTickExclusion=" + pending.excludeLandingTickPlacements
                    + " cells=" + describeExcludedLandingTickPlacements(world, pending)
                    + " grid=" + describeGridDetailed(world, pending.playerBox, pending.posX, pending.posZ)
                    + " pending=" + describePendingPlacements(world));
        }
    }

    /**
     * Last resort for a landing that never found an eligible support. Without this the landing is
     * dropped, no baseline is stored, and the NEXT real Tap/Flat is mistaken for the first landing
     * (so it is swallowed as a silent baseline). Order: the live scan from the landing tick, then
     * the last grounded support. Blocks placed after the landing tick are never used, and a landing
     * with no own block anywhere near it is still dropped (not an own-block landing).
     */
    private void resolveLandingWithFallback(World world, PendingLanding snapshot, String reason) {
        BlockPos fallback = null;
        String source = "none";
        if (snapshot.liveScanSupport != null
                && isTrackedPlacementAt(world, snapshot.liveScanSupport, snapshot)) {
            fallback = snapshot.liveScanSupport;
            source = "live-scan-at-landing";
        } else if (lastGroundedSupportPosition != null
                && isTrackedPlacementAt(world, lastGroundedSupportPosition, snapshot)
                && overlapsHitbox(lastGroundedSupportPosition, snapshot.playerBox)) {
            fallback = lastGroundedSupportPosition;
            source = "last-grounded-support";
        }
        if (fallback == null) {
            if (BridgeLog.isEnabled()) {
                BridgeLog.anomaly("LAND-DROPPED", "landing tick=" + snapshot.clientTick + " (" + reason
                        + ") has no usable support even after fallback; baselines NOT advanced. cells="
                        + describeExcludedLandingTickPlacements(world, snapshot)
                        + " pending=" + describePendingPlacements(world));
            }
            return;
        }
        if (BridgeLog.isEnabled()) {
            BridgeLog.anomaly("LAND-FALLBACK", "landing tick=" + snapshot.clientTick + " (" + reason
                    + ") recorded with fallback support=" + BridgeLog.pos(fallback) + " source=" + source
                    + " cells=" + describeExcludedLandingTickPlacements(world, snapshot));
        }
        lastSupportRule = "fallback:" + source;
        groundedSupportBlockCoordinate = getDirectionalBlockCoordinate(fallback, attemptDirection);
        hasGroundedSupportBlock = true;
        recordLanding(fallback, snapshot);
    }

    private String describePendingPlacements(World world) {
        if (pendingPlacements.isEmpty()) {
            return "none";
        }
        StringBuilder out = new StringBuilder();
        for (PendingPlacement pending : pendingPlacements) {
            if (out.length() > 0) {
                out.append("; ");
            }
            Block current = world.getBlockState(pending.position).getBlock();
            out.append(BridgeLog.pos(pending.position))
                    .append("@t").append(pending.createdAtTick)
                    .append(" age=").append(clientTickCounter - pending.createdAtTick)
                    .append(" before=").append(pending.beforeState.getBlock())
                    .append(" now=").append(current);
        }
        return out.toString();
    }

    /** Grid cell names, in the order produced by buildSupportGrid. */
    private static final String[] SUPPORT_GRID_LABELS = {
            "below", "forward", "behind", "side+", "side-",
            "forwardSide+", "forwardSide-", "behindSide+", "behindSide-"
    };

    /**
     * The nine cells of a direction-aligned 3-by-3 neighbourhood: the player's centre row,
     * one row forward, one row behind, and the two perpendicular side positions for each row.
     * The forward row matters at an edge landing: the player's 0.6-wide hitbox can overlap a
     * newly placed forward block before the player's centre crosses into that block.
     */
    private BlockPos[] buildSupportGrid(EntityPlayer player) {
        return buildSupportGrid(player.getEntityBoundingBox(), player.posX, player.posZ);
    }

    private BlockPos[] buildSupportGrid(AxisAlignedBB playerBox, double posX, double posZ) {
        if (attemptDirection == null) {
            return null;
        }
        int supportY = MathHelper.floor_double(playerBox.minY - 0.001D);
        int centerX = MathHelper.floor_double(posX);
        int centerZ = MathHelper.floor_double(posZ);

        int forwardX = centerX;
        int forwardZ = centerZ;
        int behindX = centerX;
        int behindZ = centerZ;
        int sideX = 0;
        int sideZ = 0;
        if ("south".equals(attemptDirection)) {
            forwardZ += 1;
            behindZ -= 1;
            sideX = 1;
        } else if ("north".equals(attemptDirection)) {
            forwardZ -= 1;
            behindZ += 1;
            sideX = 1;
        } else if ("east".equals(attemptDirection)) {
            forwardX += 1;
            behindX -= 1;
            sideZ = 1;
        } else if ("west".equals(attemptDirection)) {
            forwardX -= 1;
            behindX += 1;
            sideZ = 1;
        } else {
            return null;
        }
        return new BlockPos[] {
                new BlockPos(centerX, supportY, centerZ),
                new BlockPos(forwardX, supportY, forwardZ),
                new BlockPos(behindX, supportY, behindZ),
                new BlockPos(centerX + sideX, supportY, centerZ + sideZ),
                new BlockPos(centerX - sideX, supportY, centerZ - sideZ),
                new BlockPos(forwardX + sideX, supportY, forwardZ + sideZ),
                new BlockPos(forwardX - sideX, supportY, forwardZ - sideZ),
                new BlockPos(behindX + sideX, supportY, behindZ + sideZ),
                new BlockPos(behindX - sideX, supportY, behindZ - sideZ)
        };
    }

    /**
     * Checks a direction-aligned 3-by-3 neighbourhood. Physical support selection considers
     * every cell that intersects the player's horizontal hitbox and chooses the furthest-forward
     * own block. This includes the forward cell, which the old 3x2 grid omitted. A landing at the
     * edge of a newly placed block can therefore be attributed to that block before the player's
     * centre crosses its coordinate boundary. Placement tick does not affect eligibility.
     *
     * If no cell physically intersects the hitbox, the original positional fallback rules remain
     * available for edge cases where Minecraft's onGround flag and the sampled box differ by a
     * small amount.
     */
    private BlockPos findOwnPlacedSupport(World world, EntityPlayer player) {
        return findOwnPlacedSupport(world, player.getEntityBoundingBox(), player.posX, player.posZ);
    }

    private BlockPos findOwnPlacedSupport(World world, PendingLanding landing) {
        return findOwnPlacedSupport(world, landing.playerBox, landing.posX, landing.posZ, landing);
    }

    private BlockPos findOwnPlacedSupport(World world, AxisAlignedBB playerBox,
                                          double posX, double posZ) {
        return findOwnPlacedSupport(world, playerBox, posX, posZ, null);
    }

    private BlockPos findOwnPlacedSupport(World world, AxisAlignedBB playerBox,
                                          double posX, double posZ, PendingLanding landingContext) {
        lastSupportRule = "none";
        if (ownPlacedBlocks.isEmpty()) {
            lastSupportRule = "no-own-blocks";
            return null;
        }
        BlockPos[] grid = buildSupportGrid(playerBox, posX, posZ);
        if (grid == null) {
            lastSupportRule = "no-direction";
            return null;
        }

        // Landing snapshots use age-aware selection: blocks placed after the landing tick are
        // never eligible, and blocks placed ON the landing tick only win when they are under the
        // player's centre (1.05 flat / 1.15 5b) or when nothing older can support the player.
        if (landingContext != null) {
            return findLandingSupport(world, grid, playerBox, landingContext);
        }

        BlockPos below = grid[0];
        BlockPos forward = grid[1];
        BlockPos behind = grid[2];

        // Prefer the furthest-forward physically overlapping support. If the player straddles
        // two adjacent bridge blocks, choosing the older centre block undercounts the landing.
        BlockPos physicalSupport = null;
        int furthestForward = Integer.MIN_VALUE;
        int physicalIndex = -1;
        for (int i = 0; i < grid.length; i++) {
            if (isPhysicalOwnSupport(world, grid[i], playerBox, landingContext)) {
                int axis = getDirectionalBlockCoordinate(grid[i], attemptDirection);
                if (axis > furthestForward) {
                    physicalSupport = grid[i];
                    furthestForward = axis;
                    physicalIndex = i;
                }
            }
        }
        if (physicalSupport != null) {
            lastSupportRule = "physical:" + SUPPORT_GRID_LABELS[physicalIndex];
            return physicalSupport;
        }

        // On the first landing, prefer a tracked opening-run block in the whole neighbourhood.
        // Long opening runs are not limited to five placements.
        if (!hasPreviousLanding) {
            for (int i = 0; i < grid.length; i++) {
                if (isOpeningFlatPlacementAt(world, grid[i], landingContext)) {
                    lastSupportRule = "opening-run:" + SUPPORT_GRID_LABELS[i];
                    return grid[i];
                }
            }
        }

        // Primary fallback positions: directly below the centre, forward, then behind.
        if (isTrackedPlacementAt(world, below, landingContext)) {
            lastSupportRule = "tracked:below";
            return below;
        }
        if (isTrackedPlacementAt(world, forward, landingContext)) {
            lastSupportRule = "tracked:forward";
            return forward;
        }
        if (isTrackedPlacementAt(world, behind, landingContext)) {
            lastSupportRule = "tracked:behind";
            return behind;
        }

        // A non-own block in a centre/forward/behind cell must not prevent checking the
        // perpendicular and diagonal cells for this attempt's real support.

        for (int i = 3; i < grid.length; i++) {
            if (isTrackedPlacementAt(world, grid[i], landingContext)) {
                lastSupportRule = "tracked:" + SUPPORT_GRID_LABELS[i];
                return grid[i];
            }
        }

        lastSupportRule = "no-own-block-in-grid";
        return null;
    }

    /**
     * Age-aware support choice for a saved landing. Block age is relative to the landing tick:
     * early (before), same-tick (the ground tick itself) or late (after, never eligible).
     *  1. A same-tick block directly under the player's centre is the support. This is the
     *     1.05 flat (4th block placed on the ground tick) and the 1.15 5b opening landing.
     *  2. Otherwise the furthest-forward EARLY block overlapping the hitbox. A same-tick block
     *     that only overlaps the hitbox edge (the mistaken 5th block) loses to the older block
     *     the player actually landed on.
     *  3. Early opening-run blocks, then early blocks in the positional cells.
     *  4. Same-tick blocks as a last resort, so a landing is never lost just because its only
     *     support was placed on the ground tick.
     */
    private BlockPos findLandingSupport(World world, BlockPos[] grid, AxisAlignedBB playerBox,
                                        PendingLanding landing) {
        int tick = landing.clientTick;

        // Only the opening landing (1.05 flat / 1.15 5b) may land on a block placed on the ground
        // tick. Mid-attempt flats always have their 4th block placed earlier, so a same-tick block
        // there is the mistaken extra block and must not outrank the block actually stood on.
        // Even at the opening landing, a same-tick block is the support only when it completes a
        // 4-block opening (1.05: 3 older blocks + this one). With 4 older blocks already down
        // (1.1 flat, 5th block placed on the ground tick) it is the extra block, so the older
        // 4th block the player is standing on must win.
        BlockPos centre = grid[0];
        if (!hasPreviousLanding && countEarlyOpeningBlocks(world, landing) < 4
                && isTrackedPlacementAt(world, centre, landing)
                && placementAgeClass(centre, tick) == 0) {
            lastSupportRule = "centre-same-tick:" + SUPPORT_GRID_LABELS[0];
            return centre;
        }

        BlockPos earlyPhysical = null;
        int furthestForward = Integer.MIN_VALUE;
        int earlyIndex = -1;
        for (int i = 0; i < grid.length; i++) {
            if (isTrackedEarly(world, grid[i], landing) && overlapsHitbox(grid[i], playerBox)) {
                int axis = getDirectionalBlockCoordinate(grid[i], attemptDirection);
                if (axis > furthestForward) {
                    earlyPhysical = grid[i];
                    furthestForward = axis;
                    earlyIndex = i;
                }
            }
        }
        if (earlyPhysical != null) {
            lastSupportRule = "physical-early:" + SUPPORT_GRID_LABELS[earlyIndex];
            return earlyPhysical;
        }

        if (!hasPreviousLanding) {
            for (int i = 0; i < grid.length; i++) {
                if (openingFlatBlocks.contains(grid[i]) && isTrackedEarly(world, grid[i], landing)) {
                    lastSupportRule = "opening-run-early:" + SUPPORT_GRID_LABELS[i];
                    return grid[i];
                }
            }
        }
        for (int i = 0; i < grid.length; i++) {
            if (isTrackedEarly(world, grid[i], landing)) {
                lastSupportRule = "tracked-early:" + SUPPORT_GRID_LABELS[i];
                return grid[i];
            }
        }

        BlockPos sameTick = null;
        furthestForward = Integer.MIN_VALUE;
        int sameIndex = -1;
        for (int i = 0; i < grid.length; i++) {
            if (isTrackedPlacementAt(world, grid[i], landing) && overlapsHitbox(grid[i], playerBox)) {
                int axis = getDirectionalBlockCoordinate(grid[i], attemptDirection);
                if (axis > furthestForward) {
                    sameTick = grid[i];
                    furthestForward = axis;
                    sameIndex = i;
                }
            }
        }
        if (sameTick != null) {
            lastSupportRule = "same-tick-physical:" + SUPPORT_GRID_LABELS[sameIndex];
            return sameTick;
        }
        for (int i = 0; i < grid.length; i++) {
            if (isTrackedPlacementAt(world, grid[i], landing)) {
                lastSupportRule = "same-tick-tracked:" + SUPPORT_GRID_LABELS[i];
                return grid[i];
            }
        }
        lastSupportRule = "no-own-block-in-grid";
        return null;
    }

    /** Number of live opening-run blocks placed strictly before the landing tick. */
    private int countEarlyOpeningBlocks(World world, PendingLanding landing) {
        int count = 0;
        for (BlockPos position : openingFlatBlocks) {
            if (world.getBlockState(position).getBlock() != Blocks.air
                    && placementAgeClass(position, landing.clientTick) < 0) {
                count++;
            }
        }
        return count;
    }

    /** Live opening-run blocks placed on or before the landing tick (late blocks not counted). */
    private int countOpeningBlocksThroughLanding(World world, int landingTick) {
        int count = 0;
        for (BlockPos position : openingFlatBlocks) {
            if (world.getBlockState(position).getBlock() != Blocks.air
                    && placementAgeClass(position, landingTick) <= 0) {
                count++;
            }
        }
        return count;
    }

    /** Live own block placed strictly before the landing tick. */
    private boolean isTrackedEarly(World world, BlockPos position, PendingLanding landing) {
        return isTrackedPlacementAt(world, position, landing)
                && placementAgeClass(position, landing.clientTick) < 0;
    }

    /** Returns the horizontal cardinal direction nearest to the player's yaw. */
    private String getHorizontalFacingDirection(float yaw) {
        double normalizedYaw = yaw % 360.0D;
        if (normalizedYaw < 0.0D) {
            normalizedYaw += 360.0D;
        }
        int sector = ((int) Math.floor((normalizedYaw + 45.0D) / 90.0D)) & 3;
        switch (sector) {
            case 0: return "south";
            case 1: return "west";
            case 2: return "north";
            default: return "east";
        }
    }

    private boolean isSameDirection(String facingDirection, String travelDirection) {
        return facingDirection != null && facingDirection.equals(travelDirection);
    }

    private boolean isOppositeDirection(String facingDirection, String travelDirection) {
        if (facingDirection == null || travelDirection == null) {
            return false;
        }
        if ("south".equals(travelDirection)) return "north".equals(facingDirection);
        if ("north".equals(travelDirection)) return "south".equals(facingDirection);
        if ("east".equals(travelDirection)) return "west".equals(facingDirection);
        if ("west".equals(travelDirection)) return "east".equals(facingDirection);
        return false;
    }

    /** Returns true for any live opening-run block successfully placed by the local player. */
    private boolean isOpeningFlatPlacementAt(World world, BlockPos position) {
        return isOpeningFlatPlacementAt(world, position, null);
    }

    private boolean isOpeningFlatPlacementAt(World world, BlockPos position, PendingLanding landing) {
        return openingFlatBlocks.contains(position) && isTrackedPlacementAt(world, position, landing);
    }

    /** True for a live own block whose top face overlaps the saved player hitbox. */
    private boolean isPhysicalOwnSupport(World world, BlockPos position, AxisAlignedBB playerBox) {
        return isPhysicalOwnSupport(world, position, playerBox, null);
    }

    private boolean isPhysicalOwnSupport(World world, BlockPos position, AxisAlignedBB playerBox,
                                         PendingLanding landing) {
        return isTrackedPlacementAt(world, position, landing) && overlapsHitbox(position, playerBox);
    }

    private boolean overlapsHitbox(BlockPos position, AxisAlignedBB playerBox) {
        return position.getX() < playerBox.maxX && position.getX() + 1 > playerBox.minX
                && position.getZ() < playerBox.maxZ && position.getZ() + 1 > playerBox.minZ;
    }

    /** Returns true only for a live block placed by the local player during this attempt. */
    private boolean isTrackedPlacementAt(World world, BlockPos position) {
        return isTrackedPlacementAt(world, position, null);
    }

    private boolean isTrackedPlacementAt(World world, BlockPos position, PendingLanding landing) {
        if (!ownPlacedBlocks.contains(position)
                || world.getBlockState(position).getBlock() == Blocks.air) {
            return false;
        }
        // A block placed after the landing tick cannot have supported that landing. Blocks placed
        // on the landing tick stay eligible here; findLandingSupport ranks them below older ones.
        if (landing != null && placementAgeClass(position, landing.clientTick) > 0) {
            return false;
        }
        return true;
    }

    // ------------------------------------------------------------------ diagnostics (log only)

    /** One compact character per 3x3 support-grid cell: N this-tick own, O own, X other solid, - air. */
    private String describeGridCompact(World world, EntityPlayer player) {
        BlockPos[] grid = buildSupportGrid(player);
        if (grid == null) {
            return "no-grid";
        }
        StringBuilder out = new StringBuilder();
        for (int i = 0; i < grid.length; i++) {
            char c;
            if (isTrackedPlacementAt(world, grid[i])) {
                Integer placedAt = ownPlacedAtTick.get(grid[i]);
                c = placedAt != null && placedAt >= clientTickCounter ? 'N' : 'O';
            } else if (world.getBlockState(grid[i]).getBlock() != Blocks.air) {
                c = 'X';
            } else {
                c = '-';
            }
            if (i > 0) {
                out.append(' ');
            }
            out.append(SUPPORT_GRID_LABELS[i]).append('=').append(c);
        }
        return out.toString();
    }

    /** Verbose per-cell description used when a landing is evaluated. */
    private String describeGridDetailed(World world, EntityPlayer player) {
        return describeGridDetailed(world, player.getEntityBoundingBox(), player.posX, player.posZ);
    }

    private String describeGridDetailed(World world, AxisAlignedBB box, double posX, double posZ) {
        BlockPos[] grid = buildSupportGrid(box, posX, posZ);
        if (grid == null) {
            return "no-grid";
        }
        StringBuilder out = new StringBuilder();
        for (int i = 0; i < grid.length; i++) {
            if (i > 0) {
                out.append("; ");
            }
            out.append(SUPPORT_GRID_LABELS[i]).append(BridgeLog.pos(grid[i])).append('=')
                    .append(world.getBlockState(grid[i]).getBlock());
            Integer placedAt = ownPlacedAtTick.get(grid[i]);
            if (ownPlacedBlocks.contains(grid[i])) {
                out.append("/own@t").append(placedAt);
                if (placedAt != null && placedAt >= clientTickCounter) {
                    out.append("(THIS-TICK)");
                }
            }
            out.append(overlapsHitbox(grid[i], box) ? "/under-hitbox" : "/outside-hitbox");
            out.append("/axis=").append(getDirectionalBlockCoordinate(grid[i], attemptDirection));
        }
        return out.toString();
    }

    private void logTick(EntityPlayer player, World world, BlockPos support) {
        double coordinate = getDirectionalCoordinate(player.posX, player.posZ, attemptDirection);
        String advanced = pendingAdvancedLanding == null ? "-"
                : (pendingAdvancedLanding.openingFlat ? "opening" : pendingAdvancedLanding.tap ? "tap" : "flat")
                + "(g=" + pendingAdvancedLanding.groundedTicks + ")";
        BridgeLog.log("TICK", "timer=" + timerTicks
                + " pos=(" + BridgeLog.f5(player.posX) + "," + BridgeLog.f5(player.posY) + ","
                + BridgeLog.f5(player.posZ) + ") coord=" + BridgeLog.f5(coordinate)
                + " motion=(" + BridgeLog.f5(player.motionX) + "," + BridgeLog.f5(player.motionY) + ","
                + BridgeLog.f5(player.motionZ) + ")"
                + " ground=" + player.onGround + " sprint=" + player.isSprinting()
                + " yaw=" + BridgeLog.f2(player.rotationYaw) + " pitch=" + BridgeLog.f2(player.rotationPitch)
                + " facing=" + getHorizontalFacingDirection(player.rotationYaw)
                + " prevFacing=" + previousFacingDirection
                + " airborne=" + attemptWasAirborne
                + " support=" + BridgeLog.pos(support) + "[" + lastSupportRule + "]"
                + " grid{" + describeGridCompact(world, player) + "}"
                + " adv=" + advanced
                + " pendingLanding=" + (pendingLanding == null ? "-"
                        : ("t" + pendingLanding.clientTick + "/age" + (clientTickCounter - pendingLanding.clientTick)
                                + "@" + BridgeLog.f5(getDirectionalCoordinate(
                                        pendingLanding.posX, pendingLanding.posZ, attemptDirection))))
                + " pendingClicks=" + describePendingPlacements(world)
                + " failedSeq=" + (failedJumpSequenceActive
                        ? (waitingForOppositeFacing ? "waitOpposite" : pendingFailedLandingType.toString())
                        : "-")
                + " turnouts=" + pendingTurnouts.size()
                + " lastLandingBlock=" + (hasLastLandingSupportBlock
                        ? Integer.toString(lastLandingSupportBlockCoordinate) : "none"));
    }

    private void logTakeoff(EntityPlayer player) {
        BridgeLog.log("TAKEOFF", "motionY=" + BridgeLog.f(player.motionY)
                + " pos=(" + BridgeLog.f(player.posX) + "," + BridgeLog.f(player.posY) + ","
                + BridgeLog.f(player.posZ) + ")"
                + " facingNow=" + getHorizontalFacingDirection(player.rotationYaw)
                + " prevFacing=" + previousFacingDirection + " dir=" + attemptDirection
                + " canStartFailedJump=" + canStartFailedJump
                + " pendingAdv=" + (pendingAdvancedLanding == null ? "-"
                        : (pendingAdvancedLanding.openingFlat ? "opening" : pendingAdvancedLanding.tap ? "tap" : "flat")
                        + "(g=" + pendingAdvancedLanding.groundedTicks + ")")
                + " lastLandingBlock=" + (hasLastLandingSupportBlock
                        ? Integer.toString(lastLandingSupportBlockCoordinate) : "none")
                + " groundedSupportBlock=" + (hasGroundedSupportBlock
                        ? Integer.toString(groundedSupportBlockCoordinate) : "none"));
    }

    private void logLandingScan(EntityPlayer player, World world, BlockPos candidate,
                                boolean wasFacingOpposite) {
        BridgeLog.log("LAND-SCAN", "airborne -> ground. candidate=" + BridgeLog.pos(candidate)
                + " rule=" + lastSupportRule
                + " facingOpposite=" + wasFacingOpposite + " (prevFacing=" + previousFacingDirection
                + ", facingNow=" + getHorizontalFacingDirection(player.rotationYaw) + ")"
                + " pos=(" + BridgeLog.f(player.posX) + "," + BridgeLog.f(player.posY) + ","
                + BridgeLog.f(player.posZ) + ")"
                + " ownBlocks=" + ownPlacedBlocks.size() + " pendingClicks=" + describePendingPlacements(world));
        BridgeLog.log("LAND-SCAN", "grid: " + describeGridDetailed(world, player));
    }

    /**
     * Notes when the supporting own block changes while the player stays grounded (walking or
     * sliding onto a neighbouring block). That is not an airborne landing, so it does not move the
     * block baseline; the log line makes such a difference visible when a forward count is off.
     */
    private void trackGroundedSupport(BlockPos support, boolean landedThisTick) {
        if (support == null) {
            hasGroundedSupportBlock = false;
            return;
        }
        lastGroundedSupportPosition = support;
        int coordinate = getDirectionalBlockCoordinate(support, attemptDirection);
        if (!landedThisTick && hasGroundedSupportBlock && coordinate != groundedSupportBlockCoordinate
                && BridgeLog.isEnabled()) {
            BridgeLog.log("WALK", "support block changed while grounded: axis " + groundedSupportBlockCoordinate
                    + " -> " + coordinate + " (not a landing, baseline stays at axis "
                    + (hasLastLandingSupportBlock ? Integer.toString(lastLandingSupportBlockCoordinate) : "none")
                    + "; a jump from here will be measured from that baseline)");
        }
        groundedSupportBlockCoordinate = coordinate;
        hasGroundedSupportBlock = true;
    }

    /** Writes the end-of-attempt summary: counters, landing table and the exact HUD rows. */
    private void logAttemptEnd(String outcome, String reason) {
        if (!BridgeLog.isEnabled()) {
            return;
        }
        int anomalies = BridgeLog.takeAnomalyCount();
        if (pendingLanding != null) {
            BridgeLog.log("ATTEMPT-END", "WARNING: unresolved landing snapshot from tick="
                    + pendingLanding.clientTick + " was still pending at attempt end");
        }
        BridgeLog.log("ATTEMPT-END", "===== ATTEMPT " + attempts + " END: " + outcome + " ===== reason="
                + reason + " timerTicks=" + timerTicks + " ("
                + String.format(Locale.US, "%.3f", timerTicks * TIMER_STEP_SECONDS) + "s)"
                + " landings=" + landingCounter + " rows=" + landingResults.size()
                + " ownBlocks=" + ownPlacedBlocks.size() + " anomalies=" + anomalies
                + " lastLandingBlock=" + (hasLastLandingSupportBlock
                        ? Integer.toString(lastLandingSupportBlockCoordinate) : "none"));

        List<String> cells = new ArrayList<String>();
        for (BlockPos own : ownPlacedBlocks) {
            cells.add(String.format(Locale.US, "%08d", getDirectionalBlockCoordinate(own, attemptDirection) + 1000000)
                    + ":" + getDirectionalBlockCoordinate(own, attemptDirection) + "@y" + own.getY());
        }
        java.util.Collections.sort(cells);
        StringBuilder cellText = new StringBuilder();
        for (String cell : cells) {
            cellText.append(cell.substring(cell.indexOf(':') + 1)).append(' ');
        }
        BridgeLog.log("ATTEMPT-END", "own blocks (axisBlock@y): " + cellText.toString().trim());

        if (landingTable.isEmpty()) {
            BridgeLog.log("TABLE", "(no own-block landings were recorded)");
        }
        for (String line : landingTable) {
            BridgeLog.log("TABLE", line);
        }

        BridgeLog.log("HUD", "opening: hasResult=" + hasOpeningFlatResult
                + " awaitingResolution=" + openingFlatAwaitingAdvancedResolution
                + " type='" + openingFlatType + "' coordinate="
                + (hasOpeningFlatResult ? formatCoordinateDecimal(openingFlatCoordinate) : "-")
                + " jumpoverOffset=" + (hasOpeningFlatResult
                        ? formatSignedDecimalOffset(openingFlatJumpoverOffset) : "-"));
        for (LandingResult landing : landingResults) {
            BridgeLog.log("HUD", landing.label + landing.coordinate
                    + (landing.difference.length() == 0 ? "" : landing.differenceSeparator + landing.difference)
                    + " | Jumpover offset: " + landing.jumpoverOffset
                    + (landing.extraLine == null ? "" : " | " + landing.extraLine)
                    + (landing.turnout == null ? "" : " | Turnout: " + landing.turnout));
        }
        BridgeLog.flush();
    }

    private boolean shouldFailAttempt(EntityPlayer player) {
        if (player.isDead || player.getHealth() <= 0.0F) {
            failReason = "player died";
            return true;
        }
        if (player.posY < attemptStartY - FALL_DISTANCE_TO_FAIL) {
            failReason = "fell more than " + FALL_DISTANCE_TO_FAIL + " blocks below the start height (y="
                    + BridgeLog.f(player.posY) + ", startY=" + BridgeLog.f(attemptStartY) + ")";
            return true;
        }
        if (hasPreviousPosition) {
            double dx = player.posX - previousPosX;
            double dy = player.posY - previousPosY;
            double dz = player.posZ - previousPosZ;
            if (dx * dx + dy * dy + dz * dz > TELEPORT_DISTANCE_SQUARED_TO_FAIL) {
                failReason = "teleported/moved a large distance in one tick (dx=" + BridgeLog.f(dx)
                        + ", dy=" + BridgeLog.f(dy) + ", dz=" + BridgeLog.f(dz) + ")";
                return true;
            }
        }
        return false;
    }

    /**
     * True when the player ends the attempt on a golden pressure plate. Along the attempt axis the
     * player's centre must have reached the configured end coordinate of the plate block (default
     * .825, i.e. the vanilla edge of the plate's sensitive area); sideways the player's box must
     * overlap the sensitive area. The jump-plate height raises the sensitive volume upwards (0 =
     * the normal plate hitbox).
     */
    private boolean isOverGoldenPressurePlate(World world, EntityPlayer player) {
        AxisAlignedBB playerBox = player.getEntityBoundingBox();
        double endOffset = BridgeConfig.getPlateEndOffset();
        double extraHeight = BridgeConfig.getJumpPlateHeight();
        String direction = attemptDirection == null ? "south" : attemptDirection;
        boolean alongX = "east".equals(direction) || "west".equals(direction);
        double centre = getDirectionalCoordinate(player.posX, player.posZ, direction);

        int minX = MathHelper.floor_double(playerBox.minX) - 1;
        int maxX = MathHelper.floor_double(playerBox.maxX) + 1;
        int minZ = MathHelper.floor_double(playerBox.minZ) - 1;
        int maxZ = MathHelper.floor_double(playerBox.maxZ) + 1;
        int feetY = MathHelper.floor_double(playerBox.minY);
        int lowestY = MathHelper.floor_double(playerBox.minY - 0.25D - extraHeight);

        for (int x = minX; x <= maxX; x++) {
            for (int z = minZ; z <= maxZ; z++) {
                for (int y = lowestY; y <= feetY; y++) {
                    BlockPos pos = new BlockPos(x, y, z);
                    if (world.getBlockState(pos).getBlock() != Blocks.light_weighted_pressure_plate) {
                        continue;
                    }
                    // Vanilla weighted plates sense entities in a narrower inset volume (0.125 in
                    // from each side, 0.25 high); the jump-plate height extends it upwards.
                    if (playerBox.minY > y + 0.25D + extraHeight || playerBox.maxY < y) {
                        continue;
                    }
                    double lateralMin = alongX ? playerBox.minZ : playerBox.minX;
                    double lateralMax = alongX ? playerBox.maxZ : playerBox.maxX;
                    int lateralBlock = alongX ? z : x;
                    if (lateralMax <= lateralBlock + 0.125D || lateralMin >= lateralBlock + 0.875D) {
                        continue;
                    }
                    double blockStart = "south".equals(direction) ? z
                            : "east".equals(direction) ? x
                            : "north".equals(direction) ? -z - 1 : -x - 1;
                    if (centre >= blockStart + endOffset && centre < blockStart + 1.175D) {
                        return true;
                    }
                }
            }
        }
        return false;
    }

    private void finishCompletedAttempt(EntityPlayer player, boolean onPlate) {
        if (!attemptActive || !timerStarted || player == null) {
            return;
        }
        finalizeBestStats(true);

        // The exact current tick is the tick whose player bounding box entered the vanilla
        // pressure plate sensitive area. The cached position is the immediately previous tick.
        plateCoordinate = getDirectionalCoordinate(player.posX, player.posZ, attemptDirection);
        if (hasPreviousPosition) {
            platePreviousTickCoordinate = getDirectionalCoordinate(
                    previousPosX, previousPosZ, attemptDirection
            );
        } else {
            platePreviousTickCoordinate = plateCoordinate;
        }

        if (!onPlate) {
            // A bed click is handled before the player's tick, so it happened one tick before
            // this END event: take that tick off the timer.
            timerTicks = Math.max(0, timerTicks - 1);
        }
        hasPlateCoordinates = onPlate;
        result = AttemptResult.COMPLETED;
        logAttemptEnd("COMPLETED", onPlate ? "stepped on the golden pressure plate (plate coord "
                + formatCoordinateDecimal(plateCoordinate) + ", -1t "
                + formatCoordinateDecimal(platePreviousTickCoordinate) + ")"
                : "clicked a bed");
        attemptActive = false;
        if (attemptDirection != null) {
            preparedAttemptDirections.add(attemptDirection);
        }
        canStartFailedJump = false;
        clearTurnoutLandingAnchor();
        pendingAdvancedLanding = null;
        pendingLanding = null;
        openingFlatAwaitingAdvancedResolution = false;
        cancelFailedJumpSequence();
        ownPlacedBlocks.clear();
        ownPlacedAtTick.clear();
        pendingPlacements.clear();
    }

    private void finishFailedAttempt(String reason) {
        if (!attemptActive || !timerStarted) {
            return;
        }
        finalizeBestStats(true);
        // Preserve timer and any flat result. The next actual placement resets the display.
        result = AttemptResult.FAILED;
        logAttemptEnd("FAILED", reason);
        attemptActive = false;
        if (attemptDirection != null) {
            preparedAttemptDirections.add(attemptDirection);
        }
        canStartFailedJump = false;
        clearTurnoutLandingAnchor();
        pendingAdvancedLanding = null;
        pendingLanding = null;
        openingFlatAwaitingAdvancedResolution = false;
        cancelFailedJumpSequence();
        ownPlacedBlocks.clear();
        ownPlacedAtTick.clear();
        pendingPlacements.clear();
    }

    private double getDirectionalCoordinate(double x, double z, String direction) {
        if ("south".equals(direction)) {
            return z;
        }
        if ("north".equals(direction)) {
            return -z;
        }
        if ("east".equals(direction)) {
            return x;
        }
        return -x;
    }

    /** Integer support-block coordinate along the attempt axis (south/north use Z, east/west X). */
    private int getDirectionalBlockCoordinate(BlockPos position, String direction) {
        if ("south".equals(direction)) {
            return position.getZ();
        }
        if ("north".equals(direction)) {
            return -position.getZ();
        }
        if ("east".equals(direction)) {
            return position.getX();
        }
        return -position.getX();
    }


    /** Converts a direction-normalized player coordinate into its occupied block index. */
    private int getDirectionalPlayerBlockCoordinate(double coordinate, String direction) {
        // North/west negate the world axis, so the occupied block index is ceil(-worldPos).
        // South/east use the world axis directly and therefore use floor(worldPos).
        if ("north".equals(direction) || "west".equals(direction)) {
            return (int) Math.ceil(coordinate);
        }
        return MathHelper.floor_double(coordinate);
    }

    /** Formats only the three digits after the decimal point, such as .349. */
    private String formatCoordinateDecimal(double coordinate) {
        String formatted = String.format(Locale.US, "%.3f", Math.abs(coordinate));
        return formatted.substring(formatted.indexOf('.'));
    }

    /** Returns the compass direction for a level cardinal aim, or null. */
    private String getAttemptDirection(float yaw, float pitch) {
        double roundedPitchMagnitude = Math.round(Math.abs((double) pitch) * PITCH_DISPLAY_SCALE)
                / PITCH_DISPLAY_SCALE;
        if (roundedPitchMagnitude != 0.0D) {
            return null;
        }

        double normalizedYaw = yaw % 360.0D;
        if (normalizedYaw < 0.0D) {
            normalizedYaw += 360.0D;
        }
        if (isAtYaw(normalizedYaw, 0.0D)) {
            return "south";
        }
        if (isAtYaw(normalizedYaw, 90.0D)) {
            return "west";
        }
        if (isAtYaw(normalizedYaw, 180.0D)) {
            return "north";
        }
        if (isAtYaw(normalizedYaw, 270.0D)) {
            return "east";
        }
        return null;
    }

    private boolean isAtYaw(double actualYaw, double targetYaw) {
        double difference = (actualYaw - targetYaw + 540.0D) % 360.0D - 180.0D;
        double roundedDifference = Math.round(Math.abs(difference) * YAW_DISPLAY_SCALE)
                / YAW_DISPLAY_SCALE;
        return roundedDifference == 0.0D;
    }

    /** Which HUD pieces are currently switched on (a child switch counts only while its parent is on). */
    private static final class HudFlags {
        private final boolean timer = BridgeConfig.isEnabled(BridgeConfig.Setting.TIMER);
        private final boolean tapsFlats = BridgeConfig.isEnabled(BridgeConfig.Setting.TAPS_FLATS);
        private final boolean turnout = BridgeConfig.isEnabled(BridgeConfig.Setting.TURNOUT);
        private final boolean plate = BridgeConfig.isEnabled(BridgeConfig.Setting.PLATE_COORDINATES);
        private final boolean attempts = BridgeConfig.isEnabled(BridgeConfig.Setting.ATTEMPTS);
        private final boolean jumpTicks = BridgeConfig.isEnabled(BridgeConfig.Setting.JUMP_TICKS);
        private final boolean bedClickTiming = BridgeConfig.isEnabled(BridgeConfig.Setting.BED_CLICK_TIMING);
        private final boolean difference = BridgeConfig.isActive(BridgeConfig.Setting.TAP_DIFFERENCE);
        private final boolean jumpoverOffset = BridgeConfig.isActive(BridgeConfig.Setting.JUMPOVER_OFFSET);
        private final boolean sprint = BridgeConfig.isActive(BridgeConfig.Setting.SPRINT_STATUS);
        private final boolean previousTick = BridgeConfig.isActive(BridgeConfig.Setting.PREVIOUS_TICK);
    }

    /** Draws the HUD panel with its live attempt data. */
    public void renderHud() {
        Minecraft minecraft = Minecraft.getMinecraft();
        if (minecraft.thePlayer == null || minecraft.theWorld == null) {
            return;
        }
        drawHudRows(minecraft, buildLiveRows());
    }

    /**
     * Used behind the settings menu: the live HUD while an attempt is on screen, otherwise a sample
     * so every switch, the size and the position can be judged while adjusting them.
     */
    public void renderHudPreview() {
        Minecraft minecraft = Minecraft.getMinecraft();
        if (minecraft.thePlayer == null || minecraft.theWorld == null) {
            return;
        }
        drawHudRows(minecraft, timerStarted ? buildLiveRows() : buildSampleRows());
    }

    /** A Speed / delayed-flat style detail ("Sprint" / "No Sprint"), as opposed to a +/- difference. */
    private static boolean isSprintText(String text) {
        return text != null && text.endsWith("Sprint");
    }

    /** Adds a Tap / Flat / failed landing row and the optional detail rows under it. */
    private void addLandingRows(List<HudRow> rows, LandingResult landing, HudFlags flags) {
        String suffix = "";
        if (landing.difference.length() > 0) {
            boolean show = isSprintText(landing.difference) ? flags.sprint : flags.difference;
            if (show) {
                suffix = landing.differenceSeparator + landing.difference;
            }
        }
        rows.add(new HudRow(
                landing.label,
                landing.coordinate,
                landing.labelColor,
                landing.coordinateColor,
                suffix,
                landing.differenceColor
        ));
        if (flags.jumpoverOffset && landing.showJumpoverOffset) {
            rows.add(new HudRow(
                    "Jumpover offset: ",
                    landing.jumpoverOffset,
                    BridgeColors.JUMPOVER_LABEL,
                    landing.jumpoverOffsetColor
            ));
        }
        if (flags.previousTick && landing.extraLine != null) {
            rows.add(new HudRow("", landing.extraLine,
                    landing.extraLineColor, landing.extraLineColor));
        }
    }

    /**
     * Joins the three HUD groups - timer + attempts, taps & flats, turnout + plate - skipping empty
     * ones and putting a divider between neighbours when divider lines are switched on.
     */
    private List<HudRow> assembleRows(List<HudRow> top, List<HudRow> taps, List<HudRow> misc,
                                      List<HudRow> timings) {
        List<HudRow> out = new ArrayList<HudRow>();
        boolean dividers = BridgeConfig.isDividersEnabled();
        for (List<HudRow> group : java.util.Arrays.asList(top, taps, misc, timings)) {
            if (group.isEmpty()) {
                continue;
            }
            if (dividers && !out.isEmpty()) {
                out.add(HudRow.divider());
            }
            out.addAll(group);
        }
        return out;
    }

    private List<HudRow> buildLiveRows() {
        List<HudRow> top = new ArrayList<HudRow>();
        List<HudRow> taps = new ArrayList<HudRow>();
        List<HudRow> misc = new ArrayList<HudRow>();
        List<HudRow> timings = new ArrayList<HudRow>();
        HudFlags flags = new HudFlags();
        if (timerStarted) {
            if (flags.timer) {
                top.add(new HudRow(
                        "Timer: ",
                        String.format(Locale.US, "%.3f", timerTicks * TIMER_STEP_SECONDS),
                        BridgeColors.TIMER_LABEL,
                        0xFFFFFFFF
                ));
            }
            if ((flags.tapsFlats || flags.turnout) && hasOpeningFlatResult
                    && !openingFlatAwaitingAdvancedResolution) {
                if (flags.tapsFlats) {
                    String openingCoordinateText = openingFlatDisplayCoordinate == null
                            ? formatCoordinateDecimal(openingFlatCoordinate)
                            : openingFlatDisplayCoordinate;
                    String openingSuffix = "";
                    if (openingFlatSuffix.length() > 0) {
                        boolean show = isSprintText(openingFlatSuffix) ? flags.sprint : flags.difference;
                        if (show) {
                            openingSuffix = openingFlatSuffix;
                        }
                    }
                    taps.add(new HudRow(
                            openingFlatType + (openingFlatType.endsWith(" ") ? "" : " - "),
                            openingCoordinateText,
                            BridgeColors.FLAT_LABEL,
                            BridgeColors.FLAT_VALUE,
                            openingSuffix,
                            openingFlatSuffixColor
                    ));
                    if (flags.jumpoverOffset) {
                        taps.add(new HudRow(
                                "Jumpover offset: ",
                                formatSignedDecimalOffset(openingFlatJumpoverOffset),
                                BridgeColors.JUMPOVER_LABEL,
                                getDifferenceColor(openingFlatJumpoverOffset)
                        ));
                    }
                    if (flags.previousTick && openingFlatExtraLine != null) {
                        taps.add(new HudRow("", openingFlatExtraLine,
                                BridgeColors.EXTRA_LINE, BridgeColors.EXTRA_LINE));
                    }
                }
                if (flags.turnout && openingFlatTurnout != null) {
                    misc.add(new HudRow(
                            "Turnout: ",
                            openingFlatTurnout,
                            BridgeColors.TURNOUT_LABEL,
                            BridgeColors.TURNOUT_VALUE,
                            openingFlatTurnoutDifference == null ? ""
                                    : " " + openingFlatTurnoutDifference,
                            openingFlatTurnoutDifference == null ? BridgeColors.NA
                                    : getDifferenceColorFromFormatted(openingFlatTurnoutDifference)
                    ));
                }
            }
            for (LandingResult landing : landingResults) {
                if (flags.tapsFlats) {
                    addLandingRows(taps, landing, flags);
                }
                if (flags.turnout && landing.turnout != null) {
                    misc.add(new HudRow(
                            "Turnout: ",
                            landing.turnout,
                            BridgeColors.TURNOUT_LABEL,
                            BridgeColors.TURNOUT_VALUE,
                            landing.turnoutDifference == null ? ""
                                    : " " + landing.turnoutDifference,
                            landing.turnoutDifferenceColor
                    ));
                }
            }
            if (flags.plate && result == AttemptResult.COMPLETED && hasPlateCoordinates) {
                misc.add(new HudRow(
                        "Plate: ",
                        formatCoordinateDecimal(plateCoordinate),
                        BridgeColors.PLATE_LABEL,
                        0xFFFFFFFF
                ));
                misc.add(new HudRow(
                        "Plate (-1t): ",
                        formatCoordinateDecimal(platePreviousTickCoordinate),
                        BridgeColors.PLATE_PREV_LABEL,
                        0xFFFFFFFF
                ));
            }
        }

        // Jump Ticks and Bed Click Timing stay on screen at all times (outside attempts too);
        // before the first measurement they show a dash.
        if (flags.jumpTicks) {
            timings.add(new HudRow("Jump Ticks: ", hasJumpTicks ? jumpTicks + "t" : "--",
                    BridgeColors.JUMP_TICKS_LABEL, 0xFFFFFFFF));
        }
        if (flags.bedClickTiming) {
            timings.add(new HudRow("Bed Click Timing: ", hasBedClickTiming ? formatBedClickTiming(bedClickTiming) : "--",
                    BridgeColors.BED_LABEL, 0xFFFFFFFF));
        }
        if (flags.attempts) {
            top.add(new HudRow("Session Attempts: ", Integer.toString(attempts),
                    BridgeColors.ATTEMPTS_LABEL, 0xFFFFFFFF));
        }
        return assembleRows(top, taps, misc, timings);
    }

    /** Fixed example rows for the settings preview; they go through the same switches as real rows. */
    private List<HudRow> buildSampleRows() {
        List<HudRow> top = new ArrayList<HudRow>();
        List<HudRow> taps = new ArrayList<HudRow>();
        List<HudRow> misc = new ArrayList<HudRow>();
        List<HudRow> timings = new ArrayList<HudRow>();
        HudFlags flags = new HudFlags();
        if (flags.timer) {
            top.add(new HudRow("Timer: ", "1.250", BridgeColors.TIMER_LABEL, 0xFFFFFFFF));
        }
        if (flags.attempts) {
            top.add(new HudRow("Session Attempts: ", "12", BridgeColors.ATTEMPTS_LABEL, 0xFFFFFFFF));
        }
        if (flags.tapsFlats) {
            addLandingRows(taps, new LandingResult("1.15 Flat - ", ".583",
                    BridgeColors.FLAT_LABEL, BridgeColors.FLAT_VALUE,
                    "(n/a)", BridgeColors.NA, "+.283", BridgeColors.POSITIVE), flags);
            addLandingRows(taps, new LandingResult("Tap 1 - ", ".612",
                    BridgeColors.TAP_LABEL, BridgeColors.TAP_VALUE,
                    "(+.029)", BridgeColors.POSITIVE, "-.118", BridgeColors.NEGATIVE), flags);
            LandingResult speed = new LandingResult("Flat 2 - ", ".540",
                    BridgeColors.FLAT_LABEL, BridgeColors.FLAT_VALUE,
                    "Sprint", BridgeColors.POSITIVE, "+.201", BridgeColors.POSITIVE);
            speed.differenceSeparator = " - ";
            speed.extraLine = ".512 -1t";
            addLandingRows(taps, speed, flags);
        }
        if (flags.turnout) {
            misc.add(new HudRow("Turnout: ", ".455", BridgeColors.TURNOUT_LABEL,
                    BridgeColors.TURNOUT_VALUE, " (+.012)", BridgeColors.POSITIVE));
        }
        if (flags.plate) {
            misc.add(new HudRow("Plate: ", ".349", BridgeColors.PLATE_LABEL, 0xFFFFFFFF));
            misc.add(new HudRow("Plate (-1t): ", ".301", BridgeColors.PLATE_PREV_LABEL, 0xFFFFFFFF));
        }
        if (flags.jumpTicks) {
            timings.add(new HudRow("Jump Ticks: ", "1t", BridgeColors.JUMP_TICKS_LABEL, 0xFFFFFFFF));
        }
        if (flags.bedClickTiming) {
            timings.add(new HudRow("Bed Click Timing: ", "2t late", BridgeColors.BED_LABEL, 0xFFFFFFFF));
        }
        return assembleRows(top, taps, misc, timings);
    }

    /** Thin line that fades out towards both ends. */
    private static void drawDividerLine(int x, int y, int width) {
        int segments = Math.max(1, width / 2);
        for (int i = 0; i < segments; i++) {
            float t = (i + 0.5F) / segments;
            float edge = Math.min(1.0F, Math.min(t, 1.0F - t) / 0.3F);
            int alpha = Math.round(0x70 * edge * edge);
            int xa = x + width * i / segments;
            int xb = x + width * (i + 1) / segments;
            if (xb > xa && alpha > 0) {
                Gui.drawRect(xa, y, xb, y + 1, BridgeRender.withAlpha(BridgeColors.DIVIDER, alpha));
            }
        }
    }

    /** Draws the panel (no border) at the configured size, position and background opacity. */
    private void drawHudRows(Minecraft minecraft, List<HudRow> rows) {
        if (rows.isEmpty()) {
            return;
        }

        FontRenderer font = minecraft.fontRendererObj;
        int contentWidth = 0;
        int rowHeight = 12;
        int dividerHeight = 8;
        int contentHeight = 0;
        for (HudRow row : rows) {
            contentHeight += row.divider ? dividerHeight : rowHeight;
            contentWidth = Math.max(contentWidth,
                    font.getStringWidth(row.label) + font.getStringWidth(row.value)
                            + font.getStringWidth(row.suffix));
        }

        int panelWidth = contentWidth + 18;
        int panelHeight = contentHeight + 10;

        // Snap to one of five screen anchors; the panel always stays fully on screen.
        ScaledResolution resolution = new ScaledResolution(minecraft);
        float scale = BridgeConfig.getOverlayScale();
        float margin = 7.0F;
        BridgeConfig.Position position = BridgeConfig.getOverlayPosition();
        float freeX = Math.max(0.0F, resolution.getScaledWidth() - panelWidth * scale - margin * 2);
        float freeY = Math.max(0.0F, resolution.getScaledHeight() - panelHeight * scale - margin * 2);
        float x = margin + freeX * position.column / 2.0F;
        float y = margin + freeY * position.row;

        GlStateManager.pushMatrix();
        GlStateManager.translate(x, y, 0.0F);
        GlStateManager.scale(scale, scale, 1.0F);

        int backgroundAlpha = Math.round(BridgeConfig.getOverlayOpacity() * 255 / 100.0F);
        if (backgroundAlpha > 0) {
            Gui.drawRect(0, 0, panelWidth, panelHeight,
                    BridgeRender.withAlpha(BridgeColors.PANEL, backgroundAlpha));
        }

        int textY = 6;
        // Lines hug the screen border the panel is anchored to: left, centred or right.
        int leftEdge = 8;
        int rightEdge = panelWidth - 8;
        for (HudRow row : rows) {
            if (row.divider) {
                drawDividerLine(8, textY + 1, panelWidth - 16);
                textY += dividerHeight;
                continue;
            }
            int rowWidth = font.getStringWidth(row.label) + font.getStringWidth(row.value)
                    + font.getStringWidth(row.suffix);
            int textX = position.column == 0 ? leftEdge
                    : position.column == 1 ? (panelWidth - rowWidth) / 2
                    : rightEdge - rowWidth;
            font.drawStringWithShadow(row.label, textX, textY, row.labelColor);
            int valueX = textX + font.getStringWidth(row.label);
            font.drawStringWithShadow(row.value, valueX, textY, row.valueColor);
            int suffixX = valueX + font.getStringWidth(row.value);
            font.drawStringWithShadow(row.suffix, suffixX, textY, row.suffixColor);
            textY += rowHeight;
        }

        GlStateManager.popMatrix();
        GlStateManager.color(1.0F, 1.0F, 1.0F, 1.0F);
    }
}
