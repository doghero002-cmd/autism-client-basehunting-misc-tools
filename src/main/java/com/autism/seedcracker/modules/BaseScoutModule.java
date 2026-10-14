package com.autism.seedcracker.modules;

import autismclient.api.module.BoolSetting;
import autismclient.api.module.IntSetting;
import autismclient.modules.Module;
import com.autism.seedcracker.SeedcrackerAddon;
import com.autism.seedcracker.compat.ClientNotify;
import com.autism.seedcracker.compat.ModuleLookup;
import com.autism.seedcracker.finder.BaseTracker;
import com.autism.seedcracker.finder.ScoutJournal;
import com.autism.seedcracker.motion.Motion;
import com.autism.seedcracker.motion.MineTask;
import com.autism.seedcracker.motion.pure.ScoutSafety;
import com.autism.seedcracker.motion.pure.ScoutLease;
import com.google.gson.JsonObject;
import java.io.IOException;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.Set;
import java.util.UUID;
import java.util.function.BooleanSupplier;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.gui.screens.TitleScreen;
import net.minecraft.client.gui.screens.multiplayer.JoinMultiplayerScreen;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.game.ClientboundPlayerPositionPacket;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.monster.Enemy;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;

public final class BaseScoutModule extends Module {
    private final IntSetting radius=add(new IntSetting("radius","Travel radius (blocks)",512,32,4096,32).group("Session"));
    private final IntSetting minutes=add(new IntSetting("minutes","Session limit (minutes)",15,1,120,1).group("Session"));
    private final IntSetting minimumHealth=add(new IntSetting("health-reserve","Health reserve (half-hearts)",18,12,20,1).group("Safety"));
    private final IntSetting playerRange=add(new IntSetting("player-range","Player stop radius",64,16,128,8).group("Safety"));
    private final BoolSetting protection=add(new BoolSetting("require-protection","Require armor and held totem",true).description("Full armor, at least 12 armor points, 25% durability and a totem held in a hand. Creative testing is exempt.").group("Safety"));
    private final BoolSetting disconnect=add(new BoolSetting("emergency-disconnect","Emergency disconnect",true).description("Attempts logout after damage/fire/fall/low health. Combat logging rules and instant damage can still kill you.").group("Safety"));
    private final IntSetting score=add(new IntSetting("candidate-score","Stop at candidate score",70,20,100,5).group("Evidence"));
    private final IntSetting scanLoad=add(new IntSetting("scan-load","Finder chunks per tick",2,1,128,1).description("Bounded scan stress; individual finder caps still apply. Movement restrictions never change.").group("Experiment"));
    private final Set<Module> enabledByScout=new HashSet<>();
    private final java.util.Map<Module,String> previousBudgets=new java.util.HashMap<>();
    private ClientLevel world;
    private BooleanSupplier guard;
    private ScoutJournal journal;
    private UUID session;
    private volatile UUID remoteSession;
    private final ScoutLease lease=new ScoutLease();
    private volatile long lastPacket;
    private volatile boolean corrected;
    private long startedAt,lastProgress,lastObservation,lastGuardAt;
    private double originX,originZ,progressX,progressZ,previousHealth=-1;
    private boolean started,latched,emergencyAttempted;
    private String state="IDLE",reason="";

    public BaseScoutModule() {
        super(SeedcrackerAddon.ID+":base-scout","Base Scout","Guarded walking exploration and local unreviewed candidate evidence. Never guarantees survival.");
    }

    public static Path evidenceDirectory() { return FabricLoader.getInstance().getGameDir().resolve("run/scout-evidence"); }

    @Override public void onEnable() {
        lease.clear(); remoteSession=null;
        Minecraft mc=Minecraft.getInstance(); world=mc.level; started=false; latched=false; corrected=false; emergencyAttempted=false;
        startedAt=lastPacket=lastProgress=System.nanoTime(); lastObservation=0; previousHealth=-1;
        lastGuardAt=startedAt;
        session=UUID.randomUUID(); guard=()->safe(mc); state="ARMED"; reason="";
        if(world==null || mc.player==null) { latched=true; state="STOPPED"; reason="join a world and re-arm"; return; }
        originX=progressX=mc.player.getX(); originZ=progressZ=mc.player.getZ();
        try { journal=new ScoutJournal(evidenceDirectory(),session); record("start","unreviewed scouting session",null); }
        catch(IOException error) { latched=true; state="STOPPED"; reason="cannot create evidence log"; ClientNotify.warning(reason); }
    }

    @Override public void onDisable() {
        lease.clear();
        Minecraft mc=Minecraft.getInstance(); Motion.stopGuarded(mc,guard);
        restoreFinders();
        if(journal!=null) { record("end",reason.isBlank()?"disabled":reason,null); journal.close(); journal=null; }
        started=false; if(!latched) state="STOPPED";
    }

    private void restoreFinders() {
        for(Module module:enabledByScout) if(module.isEnabled()) module.setEnabled(false);
        for(var entry:previousBudgets.entrySet()) if(entry.getKey().value("chunks-per-tick").equals(appliedBudget(entry.getKey()))) entry.getKey().setValueTransient("chunks-per-tick",entry.getValue());
        previousBudgets.clear(); appliedBudgets.clear();
        enabledByScout.clear();
    }

    @Override public void onGameLeft() { halt(Minecraft.getInstance(),"world left",false); onDisable(); setEnabledSilently(false); world=null; com.autism.seedcracker.bridge.ScoutControl.disable(); }
    @Override protected void onOptionValueChanged(String settingId) { if(started && !latched) halt(Minecraft.getInstance(),"settings changed; re-arm",false); }
    @Override public boolean onPacketReceive(Packet<?> packet) { lastPacket=System.nanoTime(); if(packet instanceof ClientboundPlayerPositionPacket) corrected=true; return false; }

    @Override public void tick() {
        Minecraft mc=Minecraft.getInstance(); if(latched) { watchStopped(mc); return; }
        if(!safe(mc)) return;
        if(!started) {
            if(mc.gui.screen()!=null) return;
            if(Motion.isBusy() || MineTask.active() || ElytraTravelModule.active() || conflictingAutomation()) { halt(mc,"another automation is active",false); return; }
            for(String id:new String[]{":z-sus-chunk-finder",":light-finder",":z-chunk-finder",":z-stash-finder"}) {
                Module module=ModuleLookup.get(SeedcrackerAddon.ID+id);
                if(module!=null) {
                    String before=module.value("chunks-per-tick");
                    if(!before.isBlank()) { previousBudgets.put(module,before); module.setValueTransient("chunks-per-tick",Integer.toString(scanLoad.get())); appliedBudgets.put(module,module.value("chunks-per-tick")); }
                    if(!module.isEnabled()) { module.setEnabled(true); enabledByScout.add(module); }
                }
            }
            if(!Motion.exploreGuarded(mc,radius.get(),guard)) { halt(mc,"no safe exploration route",false); return; }
            started=true; state="SCOUTING"; lastProgress=System.nanoTime();
        } else if(!Motion.guardedBy(guard)) { halt(mc,"movement changed or stopped externally",false); return; }
        else if(!Motion.isBusy()) { halt(mc,"route complete",false); return; }
        Motion.tick(mc);
        if(latched || mc.player==null) return;
        var candidate=BaseTracker.nearestCandidate(mc.player.getX(),mc.player.getZ(),score.get(),128);
        if(candidate!=null) {
            halt(mc,"candidate requires review",false); record("candidate","unreviewed",candidate); return;
        }
        if(System.nanoTime()-lastObservation>=15_000_000_000L) { lastObservation=System.nanoTime(); record("observation",Motion.status(),null); }
    }

    private boolean safe(Minecraft mc) {
        if(latched) return false;
        if(mc.player==null || mc.level!=world || mc.getConnection()==null || !mc.player.isAlive()) { halt(mc,"world/player changed",false); return false; }
        if(!started && !mc.player.onGround()) { halt(mc,"start from solid ground",false); return false; }
        if(!world.dimension().equals(Level.OVERWORLD) || mc.player.isSpectator() || mc.player.getAbilities().flying || mc.player.isFallFlying() || mc.player.isPassenger()) { halt(mc,"scouting requires overworld walking",false); return false; }
        long now=System.nanoTime();
        if(lease.expired(now)) { halt(mc,"supervisor heartbeat expired",false); return false; }
        boolean stalled=started && now-lastGuardAt>2_000_000_000L;
        lastGuardAt=now;
        if(started && (MineTask.active() || ElytraTravelModule.active() || conflictingAutomation())) { halt(mc,"another automation became active",false); return false; }
        if(mc.gui.screen()!=null) lastProgress=now;
        if(now-startedAt>=minutes.get()*60_000_000_000L || Math.hypot(mc.player.getX()-originX,mc.player.getZ()-originZ)>radius.get()) { halt(mc,"session travel/time limit",false); return false; }
        if(Math.hypot(mc.player.getX()-progressX,mc.player.getZ()-progressZ)>0.5) { progressX=mc.player.getX(); progressZ=mc.player.getZ(); lastProgress=now; }
        if(started && mc.gui.screen()==null && now-lastProgress>30_000_000_000L) { halt(mc,"no progress for 30 seconds",false); return false; }
        boolean player=false,hostile=false;
        for(var entity:world.entitiesForRendering()) {
            if(entity==mc.player || !entity.isAlive()) continue;
            double distance=mc.player.distanceToSqr(entity);
            if(entity instanceof net.minecraft.world.entity.player.Player other && !other.isSpectator() && distance<=playerRange.get()*playerRange.get()) player=true;
            if(distance<=16*16 && (entity instanceof Enemy || entity instanceof Mob mob && mob.getTarget()==mc.player)) hostile=true;
        }
        var verdict=ScoutSafety.check(new ScoutSafety.Vitals(mc.player.getHealth(),previousHealth,mc.player.getFoodData().getFoodLevel(),mc.player.getAirSupply(),mc.player.fallDistance,
            mc.player.isOnFire(),mc.player.isInLava(),mc.player.hurtTime>0,player,hostile,prepared(mc),mc.gui.screen()!=null,
            stalled || corrected || now-lastPacket>3_000_000_000L,Motion.physicalInput(mc),unsafeAhead(mc)),minimumHealth.get());
        previousHealth=mc.player.getHealth();
        if(verdict.action()==ScoutSafety.Action.STOP || verdict.action()==ScoutSafety.Action.EMERGENCY) { halt(mc,verdict.reason(),verdict.action()==ScoutSafety.Action.EMERGENCY); return false; }
        if(journal==null || !journal.failure().isEmpty()) { halt(mc,"evidence logging failed",false); return false; }
        state=verdict.action()==ScoutSafety.Action.PAUSE?"PAUSED":"SCOUTING"; return true;
    }

    private boolean prepared(Minecraft mc) {
        if(!protection.get() || mc.player.getAbilities().instabuild) return true;
        if(mc.player.getArmorValue()<12 || !(mc.player.getOffhandItem().is(Items.TOTEM_OF_UNDYING) || mc.player.getMainHandItem().is(Items.TOTEM_OF_UNDYING))) return false;
        for(EquipmentSlot slot:new EquipmentSlot[]{EquipmentSlot.HEAD,EquipmentSlot.CHEST,EquipmentSlot.LEGS,EquipmentSlot.FEET}) {
            var item=mc.player.getItemBySlot(slot); if(item.isEmpty()) return false;
            if(item.isDamageableItem() && (item.getMaxDamage()-item.getDamageValue())*4<item.getMaxDamage()) return false;
        }
        return true;
    }

    private static boolean conflictingAutomation() {
        Set<String> names=Set.of("automine","gather","schematicbuilder","elytratravel","elytrafly","tunnelbasefinder","relogloader","donutrtpstashfinder","rtpstashfinder","autortp","antiafk","scaffold","flight","autowalk","speed","noclip");
        for(Module module:ModuleLookup.includingHost()) if(module.isEnabled() && names.contains(module.name().toLowerCase(java.util.Locale.ROOT).replaceAll("[^a-z0-9]",""))) return true;
        return false;
    }

    private static boolean unsafeAhead(Minecraft mc) {
        BlockPos next=Motion.nextStep(); if(next==null) return false;
        for(int offsetX=-1;offsetX<=1;offsetX++) for(int offsetZ=-1;offsetZ<=1;offsetZ++) {
            BlockPos near=next.offset(offsetX,0,offsetZ); if(!mc.level.hasChunk(near.getX()>>4,near.getZ()>>4)) return true;
            for(int offsetY=-1;offsetY<=1;offsetY++) {
                var block=mc.level.getBlockState(near.offset(0,offsetY,0));
                if(block.is(Blocks.LAVA) || block.is(Blocks.FIRE) || block.is(Blocks.SOUL_FIRE) || block.is(Blocks.MAGMA_BLOCK) || block.is(Blocks.CACTUS) || block.is(Blocks.SWEET_BERRY_BUSH) || block.is(Blocks.POWDER_SNOW)) return true;
            }
        }
        return !mc.level.getBlockState(next).getFluidState().isEmpty()
            || mc.level.getBlockState(next.below()).getCollisionShape(mc.level,next.below()).isEmpty();
    }

    private void halt(Minecraft mc,String why,boolean emergency) {
        if(latched) return; latched=true; state="STOPPED"; reason=why; Motion.stopGuarded(mc,guard);
        restoreFinders();
        record("stop",why,null);
        if(why.equals("manual takeover") || why.equals("movement changed or stopped externally")) started=false;
        ClientNotify.warning("Base Scout stopped: "+why);
        if(emergency) emergencyExit(mc,why);
    }

    private void watchStopped(Minecraft mc) {
        if(!started || emergencyAttempted || !disconnect.get() || mc.player==null || mc.level!=world || mc.getConnection()==null) return;
        if(Motion.physicalInput(mc) || Motion.isBusy() || conflictingAutomation()) { started=false; return; }
        var verdict=ScoutSafety.check(new ScoutSafety.Vitals(mc.player.getHealth(),previousHealth,mc.player.getFoodData().getFoodLevel(),
            mc.player.getAirSupply(),mc.player.fallDistance,mc.player.isOnFire(),mc.player.isInLava(),mc.player.hurtTime>0,
            false,false,true,mc.gui.screen()!=null,false,false,false),minimumHealth.get());
        previousHealth=mc.player.getHealth();
        if(verdict.action()==ScoutSafety.Action.EMERGENCY) {
            reason=verdict.reason(); record("emergency","after stop: "+reason,null); emergencyExit(mc,reason);
        }
    }

    private void emergencyExit(Minecraft mc,String why) {
        if(emergencyAttempted || !started || !disconnect.get() || mc.getConnection()==null) return;
        emergencyAttempted=true;
        var connection=mc.getConnection().getConnection();
        try { mc.disconnect(new JoinMultiplayerScreen(new TitleScreen()),false); }
        catch(RuntimeException failure) { connection.disconnect(Component.literal("Base Scout: "+why)); }
    }

    private void record(String type,String detail,BaseTracker.Fused candidate) {
        if(journal==null) return;
        Minecraft mc=Minecraft.getInstance(); JsonObject event=new JsonObject();
        event.addProperty("type",type); event.addProperty("sessionId",session.toString()); event.addProperty("at",System.currentTimeMillis());
        event.addProperty("detail",detail); event.addProperty("label","unreviewed");
        event.addProperty("scanLoad",scanLoad.get()); event.addProperty("movementPolicy","guarded-walk-no-world-edits");
        event.addProperty("localWorld",mc.getSingleplayerServer()!=null);
        event.addProperty("supervised",lease.bound());
        event.addProperty("dimension",world==null?"unknown":world.dimension().identifier().toString());
        if(mc.player!=null) { event.addProperty("x",mc.player.getX()); event.addProperty("y",mc.player.getY()); event.addProperty("z",mc.player.getZ()); event.addProperty("health",mc.player.getHealth()); event.addProperty("food",mc.player.getFoodData().getFoodLevel()); }
        if(candidate!=null) { event.addProperty("candidateId",UUID.randomUUID().toString()); event.addProperty("candidateX",candidate.blockX()); event.addProperty("candidateZ",candidate.blockZ()); event.addProperty("score",candidate.confidence()); event.addProperty("sources",candidate.sources()); }
        if(!journal.append(event) && !latched) halt(mc,"evidence queue/record limit",false);
    }

    public String startSupervised() {
        if(isEnabled() || Motion.isBusy() || MineTask.active() || ElytraTravelModule.active()) throw new IllegalStateException("Another session or movement is active");
        setEnabled(true);
        if(latched || session==null) { setEnabled(false); throw new IllegalStateException(reason); }
        remoteSession=session; lease.bind(session,System.nanoTime());
        record("supervision","supervisor lease bound",null);
        tick();
        if(latched) { setEnabled(false); throw new IllegalStateException(reason); }
        return session.toString();
    }

    public boolean heartbeat(UUID owner) { return isEnabled() && !latched && lease.heartbeat(owner,System.nanoTime()); }
    public boolean stopSupervised(UUID owner) {
        if(owner==null || !owner.equals(remoteSession) || !owner.equals(session)) return false;
        if(isEnabled()) { halt(Minecraft.getInstance(),"supervisor stopped session",false); setEnabled(false); }
        return true;
    }
    public UUID supervisedSession() { return remoteSession; }
    public void remoteDisabled(UUID owner) {
        if(owner!=null && owner.equals(remoteSession) && owner.equals(session) && isEnabled()) { halt(Minecraft.getInstance(),"remote supervisor disabled",false); setEnabled(false); }
    }

    public JsonObject snapshot() {
        Minecraft mc=Minecraft.getInstance(); JsonObject result=new JsonObject();
        result.addProperty("enabled",isEnabled()); result.addProperty("state",state); result.addProperty("reason",reason);
        result.addProperty("sessionId",sessionId()); result.addProperty("supervised",lease.bound());
        result.addProperty("protectionActive",started && !emergencyAttempted && disconnect.get());
        result.addProperty("leaseRemainingMillis",lease.remaining(System.nanoTime())/1_000_000L);
        result.addProperty("inWorld",mc.level!=null && mc.player!=null && mc.getConnection()!=null);
        if(mc.player!=null) {
            result.addProperty("health",mc.player.getHealth()); result.addProperty("food",mc.player.getFoodData().getFoodLevel());
            result.addProperty("gearReady",prepared(mc)); result.addProperty("onGround",mc.player.onGround());
            result.addProperty("x",mc.player.getX()); result.addProperty("y",mc.player.getY()); result.addProperty("z",mc.player.getZ());
        }
        return result;
    }

    public String sessionId() { return session==null?"none":session.toString(); }
    private final java.util.Map<Module,String> appliedBudgets=new java.util.HashMap<>();
    private String appliedBudget(Module module) { return appliedBudgets.getOrDefault(module,""); }
    @Override public String info() { return state+(reason.isBlank()?"":" / "+reason); }
}