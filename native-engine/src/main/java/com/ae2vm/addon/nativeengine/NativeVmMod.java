package com.ae2vm.addon.nativeengine;

import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.event.server.ServerStoppingEvent;
import net.minecraftforge.fml.common.Mod;

@Mod("ae2vm_aco")
public final class NativeVmMod {
    public NativeVmMod() {
        MinecraftForge.EVENT_BUS.addListener(this::stopping);
    }
    private void stopping(ServerStoppingEvent event) { NativeVm.cancelServer(event.getServer()); }
}
