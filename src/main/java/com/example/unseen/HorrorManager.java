package com.example.unseen;

import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import net.minecraft.block.Block;
import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.block.LanternBlock;
import net.minecraft.block.TorchBlock;
import net.minecraft.entity.effect.StatusEffectInstance;
import net.minecraft.entity.effect.StatusEffects;
import net.minecraft.network.packet.s2c.play.PlaySoundS2CPacket;
import net.minecraft.registry.Registries;
import net.minecraft.registry.entry.RegistryEntry;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.sound.SoundCategory;
import net.minecraft.sound.SoundEvent;
import net.minecraft.state.property.Properties;
import net.minecraft.util.Identifier;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.MathHelper;
import net.minecraft.util.math.Vec3d;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.UUID;
import java.util.function.Consumer;

/**
 * Per-player horror timeline (server side, everything is sent only to the affected player):
 *  Phase 1 (0-3 min):  whispers, footsteps behind, silhouettes that vanish when looked at.
 *  Phase 2 (3-6 min):  nearby lights flicker, 1-second blindness pulses (+ phase 1 effects).
 *  Phase 3 (6-8 min):  heartbeat, then a sudden scream, entity behind the player, 1 damage, timer reset.
 */
public final class HorrorManager {
    public static final int P2_START = 3 * 60 * 20;
    public static final int P3_START = 6 * 60 * 20;
    public static final int MAX_END = 8 * 60 * 20;
    public static final float SCARE_DAMAGE = 1.0f; // 1 = half a heart

    public static boolean enabled = true;

    private static final Random RNG = new Random();
    private static final Map<UUID, State> STATES = new HashMap<>();
    private static final List<Timed> GLOBAL = new ArrayList<>();
    private static final List<Flicker> ACTIVE = new ArrayList<>();
    private static long serverTicks = 0;

    private record Timed(long at, Runnable run) {}
    private record Task(int at, Consumer<ServerPlayerEntity> run) {}

    private static final class State {
        int ticks, scareAt, nextAmbient, nextSilhouette, nextFlicker, nextBlind, nextHeart;
        WatcherEntity watcher;
        int watcherAge;
        boolean watcherScare;
        final List<Task> tasks = new ArrayList<>();

        State() { reset(); }

        void reset() {
            ticks = 0;
            scareAt = P3_START + RNG.nextInt(MAX_END - P3_START);
            tasks.clear();
            initTimers();
        }

        void initTimers() {
            nextAmbient = ticks + 100 + RNG.nextInt(100);
            nextSilhouette = ticks + 300 + RNG.nextInt(300);
            nextFlicker = ticks + 100 + RNG.nextInt(100);
            nextBlind = ticks + 300 + RNG.nextInt(300);
            nextHeart = ticks;
        }
    }

    private HorrorManager() {}

    public static void init() {
        ServerTickEvents.END_SERVER_TICK.register(HorrorManager::tick);
        ServerPlayConnectionEvents.DISCONNECT.register((handler, server) -> {
            State s = STATES.remove(handler.getPlayer().getUuid());
            if (s != null && s.watcher != null) s.watcher.discard();
        });
        ServerLifecycleEvents.SERVER_STOPPING.register(server -> shutdown());
    }

    // ------------------------------------------------------------------ public API (commands)

    public static void reset(ServerPlayerEntity p) { state(p).reset(); }

    public static void setPhase(ServerPlayerEntity p, int phase) {
        State s = state(p);
        s.tasks.clear();
        s.ticks = phase == 1 ? 0 : phase == 2 ? P2_START : P3_START;
        s.scareAt = phase == 3 ? s.ticks + 300 : P3_START + RNG.nextInt(MAX_END - P3_START);
        s.initTimers();
    }

    public static void forceScare(ServerPlayerEntity p) { scare(p, state(p), p.getServerWorld()); }

    public static String status(ServerPlayerEntity p) {
        State s = state(p);
        int phase = s.ticks < P2_START ? 1 : s.ticks < P3_START ? 2 : 3;
        return "Phase " + phase + ", elapsed " + (s.ticks / 20) + "s, scare in ~" + Math.max(0, (s.scareAt - s.ticks) / 20) + "s";
    }

    private static State state(ServerPlayerEntity p) {
        return STATES.computeIfAbsent(p.getUuid(), k -> new State());
    }

    // ------------------------------------------------------------------ main tick

    private static void tick(MinecraftServer server) {
        serverTicks++;
        runGlobal();
        if (!enabled) return;
        for (ServerPlayerEntity p : server.getPlayerManager().getPlayerList()) {
            try {
                tickPlayer(p, state(p));
            } catch (Exception e) {
                Unseen.LOGGER.error("Horror tick failed", e);
            }
        }
    }

    private static void tickPlayer(ServerPlayerEntity p, State s) {
        if (!p.isAlive()) { s.reset(); return; }
        if (p.isSpectator()) return;
        ServerWorld world = p.getServerWorld();

        s.ticks++;
        runTasks(p, s);
        manageWatcher(p, s);

        int t = s.ticks;
        int phase = t < P2_START ? 1 : (t < P3_START ? 2 : 3);

        if (t >= s.nextAmbient) {
            ambient(p, s, world, phase);
            s.nextAmbient = t + switch (phase) {
                case 1 -> 200 + RNG.nextInt(200);
                case 2 -> 150 + RNG.nextInt(150);
                default -> 80 + RNG.nextInt(80);
            };
        }

        if (phase <= 2 && s.watcher == null && t >= s.nextSilhouette) {
            spawnSilhouette(p, s, world);
            s.nextSilhouette = t + (phase == 1 ? 500 + RNG.nextInt(400) : 400 + RNG.nextInt(300));
        }

        if (phase >= 2) {
            if (t >= s.nextFlicker) {
                flickerLights(p, world);
                s.nextFlicker = t + (phase == 2 ? 160 + RNG.nextInt(240) : 100 + RNG.nextInt(150));
            }
            if (t >= s.nextBlind) {
                p.addStatusEffect(new StatusEffectInstance(StatusEffects.BLINDNESS, 30, 0, false, false, false));
                play(p, snd("ambient.cave"), SoundCategory.AMBIENT, p.getX(), p.getY(), p.getZ(), 1.0f, 0.5f);
                s.nextBlind = t + (phase == 2 ? 500 + RNG.nextInt(500) : 400 + RNG.nextInt(400));
            }
        }

        if (phase == 3 && t >= s.nextHeart) {
            play(p, snd("entity.warden.heartbeat"), SoundCategory.HOSTILE, p.getX(), p.getY(), p.getZ(), 1.0f, 1.0f);
            s.nextHeart = t + MathHelper.clamp((s.scareAt - t) / 12, 14, 40);
        }

        if (t >= s.scareAt) {
            scare(p, s, world);
        }
    }

    private static void runTasks(ServerPlayerEntity p, State s) {
        if (s.tasks.isEmpty()) return;
        List<Task> due = new ArrayList<>();
        for (Iterator<Task> it = s.tasks.iterator(); it.hasNext(); ) {
            Task t = it.next();
            if (s.ticks >= t.at()) { due.add(t); it.remove(); }
        }
        for (Task t : due) t.run().accept(p);
    }

    private static void runGlobal() {
        if (GLOBAL.isEmpty()) return;
        List<Timed> due = new ArrayList<>();
        for (Iterator<Timed> it = GLOBAL.iterator(); it.hasNext(); ) {
            Timed t = it.next();
            if (t.at() <= serverTicks) { due.add(t); it.remove(); }
        }
        for (Timed t : due) t.run().run();
    }

    private static void shutdown() {
        for (Flicker f : new ArrayList<>(ACTIVE)) f.turnOn(true);
        ACTIVE.clear();
        GLOBAL.clear();
        for (State s : STATES.values()) if (s.watcher != null) s.watcher.discard();
        STATES.clear();
    }

    // ------------------------------------------------------------------ phase 1: ambience

    private static void ambient(ServerPlayerEntity p, State s, ServerWorld world, int phase) {
        int roll = RNG.nextInt(10);
        if (roll < 5) {
            footsteps(p, s, phase);
        } else if (roll < 8) {
            String[] ids = {"ambient.cave", "block.wooden_door.close", "block.wooden_door.open",
                    "entity.zombie.ambient", "block.chest.close", "entity.skeleton.ambient"};
            String id = ids[RNG.nextInt(ids.length)];
            Vec3d pos = p.getPos().add(rot(horizLook(p), 120 + RNG.nextInt(120)).multiply(8 + RNG.nextInt(8)));
            float pitch = id.startsWith("entity") ? 0.5f + RNG.nextFloat() * 0.2f : 0.8f + RNG.nextFloat() * 0.3f;
            play(p, snd(id), SoundCategory.AMBIENT, pos.x, p.getY(), pos.z, 0.7f, pitch);
        } else {
            play(p, snd("ambient.cave"), SoundCategory.AMBIENT, p.getX(), p.getY(), p.getZ(), 1.0f, 0.7f + RNG.nextFloat() * 0.2f);
        }
    }

    private static void footsteps(ServerPlayerEntity p, State s, int phase) {
        int steps = 4 + RNG.nextInt(4);
        Vec3d back = rot(horizLook(p), 180 + RNG.nextInt(61) - 30);
        double stepDist = phase == 3 ? 1.1 : 0.8;
        for (int i = 0; i < steps; i++) {
            final int idx = i;
            s.tasks.add(new Task(s.ticks + i * 9, pl -> {
                Vec3d pos = pl.getPos().add(back.multiply(Math.max(2.5, 7.0 - idx * stepDist)));
                play(pl, stepSound(pl.getServerWorld(), pl, pos), SoundCategory.HOSTILE,
                        pos.x, pl.getY(), pos.z, 0.6f, 0.85f + RNG.nextFloat() * 0.2f);
            }));
        }
    }

    private static RegistryEntry<SoundEvent> stepSound(ServerWorld world, ServerPlayerEntity p, Vec3d pos) {
        BlockState st = world.getBlockState(BlockPos.ofFloored(pos.x, pos.y - 0.5, pos.z));
        if (st.isAir()) st = world.getBlockState(p.getBlockPos().down());
        if (st.isAir()) return snd("block.gravel.step");
        return RegistryEntry.of(st.getSoundGroup().getStepSound());
    }

    // ------------------------------------------------------------------ silhouettes

    private static void spawnSilhouette(ServerPlayerEntity p, State s, ServerWorld world) {
        Vec3d look = horizLook(p);
        for (int attempt = 0; attempt < 10; attempt++) {
            double off = (48 + RNG.nextInt(15)) * (RNG.nextBoolean() ? 1 : -1); // just at the edge of the view
            double dist = 9 + RNG.nextDouble() * 7;
            Vec3d dir = rot(look, off);
            double x = p.getX() + dir.x * dist;
            double z = p.getZ() + dir.z * dist;
            BlockPos pos = findStandPos(world, x, p.getY(), z, attempt < 6);
            if (pos == null) continue;

            WatcherEntity w = new WatcherEntity(ModEntities.WATCHER, world);
            w.managed = true;
            w.refreshPositionAndAngles(pos.getX() + 0.5, pos.getY(), pos.getZ() + 0.5, 0f, 0f);
            faceTarget(w, p);
            world.spawnEntity(w);
            s.watcher = w;
            s.watcherAge = 0;
            s.watcherScare = false;
            return;
        }
    }

    private static BlockPos findStandPos(ServerWorld w, double x, double y, double z, boolean preferDark) {
        int bx = MathHelper.floor(x), bz = MathHelper.floor(z), by = MathHelper.floor(y);
        for (int dy = 3; dy >= -5; dy--) {
            BlockPos pos = new BlockPos(bx, by + dy, bz);
            BlockPos below = pos.down();
            if (w.getBlockState(below).isSolidBlock(w, below)
                    && free(w, pos) && free(w, pos.up())
                    && w.getFluidState(pos).isEmpty()) {
                if (!preferDark || w.getLightLevel(pos) <= 8) return pos;
            }
        }
        return null;
    }

    private static boolean free(ServerWorld w, BlockPos pos) {
        return w.getBlockState(pos).getCollisionShape(w, pos).isEmpty();
    }

    private static void manageWatcher(ServerPlayerEntity p, State s) {
        WatcherEntity w = s.watcher;
        if (w == null) return;
        if (w.isRemoved() || w.getWorld() != p.getServerWorld()) {
            if (!w.isRemoved()) w.discard();
            s.watcher = null;
            return;
        }
        s.watcherAge++;
        faceTarget(w, p);

        if (s.watcherScare) {
            if (s.watcherAge > 30) { w.discard(); s.watcher = null; }
            return;
        }
        Vec3d to = w.getEyePos().subtract(p.getEyePos());
        double dist = to.length();
        double dot = p.getRotationVec(1.0f).dotProduct(to.normalize());
        // vanishes the moment the player's view vector points at it (~40 degrees cone)
        if (dot > 0.77 || dist < 4.0 || s.watcherAge > 240) {
            w.discard();
            s.watcher = null;
        }
    }

    private static void faceTarget(WatcherEntity w, ServerPlayerEntity p) {
        double dx = p.getX() - w.getX();
        double dz = p.getZ() - w.getZ();
        float yaw = (float) (MathHelper.atan2(dz, dx) * 180.0 / Math.PI) - 90.0f;
        w.setYaw(yaw);
        w.setHeadYaw(yaw);
        w.setBodyYaw(yaw);
        w.setPitch(0f);
    }

    // ------------------------------------------------------------------ phase 2: lights

    private static final class Flicker {
        final ServerWorld world;
        final BlockPos pos;
        final BlockState original, off;

        Flicker(ServerWorld world, BlockPos pos, BlockState original, BlockState off) {
            this.world = world; this.pos = pos; this.original = original; this.off = off;
        }

        void turnOff() {
            if (world.getBlockState(pos).equals(original)) world.setBlockState(pos, off, Block.NOTIFY_LISTENERS);
        }

        void turnOn(boolean last) {
            if (world.getBlockState(pos).equals(off)) world.setBlockState(pos, original, Block.NOTIFY_LISTENERS);
            if (last) ACTIVE.remove(this);
        }
    }

    private static BlockState offState(BlockState st) {
        if (st.getLuminance() <= 0) return null;
        if (st.contains(Properties.WATERLOGGED) && st.get(Properties.WATERLOGGED)) return null;
        if (st.contains(Properties.LIT)) return st.get(Properties.LIT) ? st.with(Properties.LIT, false) : null;
        Block b = st.getBlock();
        if (b instanceof TorchBlock || b instanceof LanternBlock) return Blocks.AIR.getDefaultState();
        return null;
    }

    private static void flickerLights(ServerPlayerEntity p, ServerWorld world) {
        BlockPos c = p.getBlockPos();
        List<Flicker> found = new ArrayList<>();
        for (BlockPos pos : BlockPos.iterate(c.add(-8, -4, -8), c.add(8, 4, 8))) {
            BlockState st = world.getBlockState(pos);
            BlockState off = offState(st);
            if (off != null) found.add(new Flicker(world, pos.toImmutable(), st, off));
        }
        if (found.isEmpty()) {
            // no light sources nearby: fall back to a short darkness pulse
            p.addStatusEffect(new StatusEffectInstance(StatusEffects.DARKNESS, 60, 0, false, false, false));
            return;
        }
        Collections.shuffle(found, RNG);
        int n = Math.min(found.size(), 8);
        for (int i = 0; i < n; i++) {
            Flicker f = found.get(i);
            ACTIVE.add(f);
            GLOBAL.add(new Timed(serverTicks + 1, f::turnOff));
            GLOBAL.add(new Timed(serverTicks + 6, () -> f.turnOn(false)));
            GLOBAL.add(new Timed(serverTicks + 9, f::turnOff));
            GLOBAL.add(new Timed(serverTicks + 18 + RNG.nextInt(25), () -> f.turnOn(true)));
        }
        play(p, snd("block.fire.extinguish"), SoundCategory.AMBIENT, p.getX(), p.getY(), p.getZ(), 0.5f, 0.6f);
    }

    // ------------------------------------------------------------------ phase 3: the scare

    private static void scare(ServerPlayerEntity p, State s, ServerWorld world) {
        if (s.watcher != null) { s.watcher.discard(); s.watcher = null; }

        play(p, snd("entity.enderman.scream"), SoundCategory.HOSTILE, p.getX(), p.getY(), p.getZ(), 3.0f, 0.5f);
        play(p, snd("block.sculk_shrieker.shriek"), SoundCategory.HOSTILE, p.getX(), p.getY(), p.getZ(), 3.0f, 0.6f);

        Vec3d back = horizLook(p).multiply(-1);
        WatcherEntity spawned = null;
        for (double d : new double[]{1.5, 1.2, 0.9}) {
            double x = p.getX() + back.x * d;
            double z = p.getZ() + back.z * d;
            BlockPos pos = BlockPos.ofFloored(x, p.getY() + 0.01, z);
            if (free(world, pos) && free(world, pos.up())) {
                WatcherEntity w = new WatcherEntity(ModEntities.WATCHER, world);
                w.managed = true;
                w.refreshPositionAndAngles(x, p.getY(), z, 0f, 0f);
                faceTarget(w, p);
                world.spawnEntity(w);
                spawned = w;
                break;
            }
        }

        p.damage(world.getDamageSources().magic(), SCARE_DAMAGE);

        s.reset(); // back to phase 1, timer from zero
        if (spawned != null) {
            s.watcher = spawned;
            s.watcherAge = 0;
            s.watcherScare = true;
        }
    }

    // ------------------------------------------------------------------ helpers

    private static Vec3d horizLook(ServerPlayerEntity p) {
        Vec3d l = p.getRotationVec(1.0f);
        Vec3d h = new Vec3d(l.x, 0, l.z);
        return h.lengthSquared() < 1.0e-4 ? new Vec3d(0, 0, 1) : h.normalize();
    }

    private static Vec3d rot(Vec3d v, double deg) {
        double r = Math.toRadians(deg);
        double c = Math.cos(r), s = Math.sin(r);
        return new Vec3d(v.x * c - v.z * s, 0, v.x * s + v.z * c);
    }

    private static RegistryEntry<SoundEvent> snd(String id) {
        return Registries.SOUND_EVENT.getEntry(Identifier.ofVanilla(id))
                .<RegistryEntry<SoundEvent>>map(r -> r)
                .orElse(null);
    }

    /** Sound is sent only to this player, so other players on a server are not affected. */
    private static void play(ServerPlayerEntity p, RegistryEntry<SoundEvent> sound, SoundCategory cat,
                             double x, double y, double z, float volume, float pitch) {
        if (sound == null) return;
        p.networkHandler.sendPacket(new PlaySoundS2CPacket(sound, cat, x, y, z, volume, pitch, RNG.nextLong()));
    }
}
