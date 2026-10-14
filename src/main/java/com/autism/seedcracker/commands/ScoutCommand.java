package com.autism.seedcracker.commands;

import autismclient.commands.AutismCommandSource;
import autismclient.commands.Command;
import autismclient.util.AutismClientMessaging;
import com.autism.seedcracker.SeedcrackerAddon;
import com.autism.seedcracker.compat.ModuleLookup;
import com.autism.seedcracker.finder.ScoutJournal;
import com.autism.seedcracker.bridge.ScoutControl;
import com.autism.seedcracker.modules.BaseScoutModule;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.builder.RequiredArgumentBuilder;
import java.io.IOException;

public final class ScoutCommand extends Command {
    public ScoutCommand() { super("scout","Guarded scouting: start, stop, status, load 1..128, review session candidate label."); }
    private static BaseScoutModule scout() {
        var module=ModuleLookup.get(SeedcrackerAddon.ID+":base-scout");
        if(!(module instanceof BaseScoutModule scout)) throw new IllegalStateException("Base Scout not registered");
        return scout;
    }
    @Override public void build(LiteralArgumentBuilder<AutismCommandSource> root) {
        root.executes(context->status());
        root.then(LiteralArgumentBuilder.<AutismCommandSource>literal("status").executes(context->status()));
        root.then(LiteralArgumentBuilder.<AutismCommandSource>literal("stop").executes(context->{scout().setEnabled(false); return status();}));
        root.then(LiteralArgumentBuilder.<AutismCommandSource>literal("start").executes(context->{var module=scout(); if(module.isEnabled()) module.setEnabled(false); module.setEnabled(true); return status();}));
        root.then(LiteralArgumentBuilder.<AutismCommandSource>literal("remote")
            .then(LiteralArgumentBuilder.<AutismCommandSource>literal("on").executes(context->{
                try { ScoutControl.enable(); AutismClientMessaging.sendPrefixed("[Scout] Native loopback supervision enabled; each session requires a heartbeat."); }
                catch(IOException unavailable) { AutismClientMessaging.sendPrefixed("[Scout] Remote control unavailable: "+unavailable.getMessage()); }
                return SUCCESS;
            }))
            .then(LiteralArgumentBuilder.<AutismCommandSource>literal("off").executes(context->{ScoutControl.disable(); return status();})));
        root.then(LiteralArgumentBuilder.<AutismCommandSource>literal("load").then(RequiredArgumentBuilder.<AutismCommandSource,Integer>argument("chunks",IntegerArgumentType.integer(1,128))
            .executes(context->{scout().setValue("scan-load",Integer.toString(IntegerArgumentType.getInteger(context,"chunks"))); return status();})));
        root.then(LiteralArgumentBuilder.<AutismCommandSource>literal("review")
            .then(RequiredArgumentBuilder.<AutismCommandSource,String>argument("session",StringArgumentType.word())
            .then(RequiredArgumentBuilder.<AutismCommandSource,String>argument("candidate",StringArgumentType.word())
            .then(RequiredArgumentBuilder.<AutismCommandSource,String>argument("label",StringArgumentType.word()).executes(context->{
                try {
                    if(scout().isEnabled()) throw new IllegalArgumentException("Stop scouting before reviewing evidence");
                    ScoutJournal.review(BaseScoutModule.evidenceDirectory(),StringArgumentType.getString(context,"session"),StringArgumentType.getString(context,"candidate"),StringArgumentType.getString(context,"label"));
                    AutismClientMessaging.sendPrefixed("[Scout] Review saved. Labels are manual evidence, not detector certainty.");
                } catch(IOException|IllegalArgumentException error) { AutismClientMessaging.sendPrefixed("[Scout] Review rejected: "+error.getMessage()); }
                return SUCCESS;
            })))));
    }
    private static int status() {
        var module=scout(); AutismClientMessaging.sendPrefixed("[Scout] "+module.info()+" / session "+module.sessionId());
        return SUCCESS;
    }
}