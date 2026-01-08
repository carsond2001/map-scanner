package com.frames;

import meteordevelopment.meteorclient.addons.MeteorAddon;
import meteordevelopment.meteorclient.systems.modules.Modules;

public class MapFrameAddon extends MeteorAddon {
    @Override
    public void onInitialize() {
        System.out.println("Initializing MapFrame Addon");
        //Modules.get().add(new MapFrameFarmer());
        Modules.get().add(new MapRgbScanner());
        Modules.get().add(new SignScanner());

    }

    @Override
    public String getPackage() {
        // IMPORTANT: this must match your real Java package
        return "com.frames";
    }


}
