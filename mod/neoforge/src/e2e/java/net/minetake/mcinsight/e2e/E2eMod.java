package net.minetake.mcinsight.e2e;

import net.neoforged.fml.common.Mod;

/** The e2e harness as its own mod, so it loads even when McInsight itself is left out. */
@Mod(E2eHarness.MOD_ID)
public final class E2eMod {
    public E2eMod() {}
}
