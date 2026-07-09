package com.retroconsole.bridge;

import com.sun.jna.NativeLibrary;
import com.sun.jna.Pointer;
import com.sun.jna.ptr.PointerByReference;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Path;

/**
 * Flycast Windows crash patch — bisect one ff15 call site at a time.
 *
 * <p>Open this file and uncomment the desired {@code patchSite(...)} calls in
 * {@link #applyEnabledSites}. Rebuild with {@code gradlew compileJava}, then restart the client.
 *
 * <p>RVAs from audit (flycast v7ec978e):
 * <ul>
 *   <li>668b6, 668d6, 668f6, 66921, 6699b, 669c7 → env slot 0x7a34a8 (break CUE if all patched)</li>
 *   <li>669b0 → log slot 0x7a34a0 (candidate against crash without env)</li>
 * </ul>
 */
public final class FlycastWindowsCrashPatch {
    private static final Logger LOGGER = LoggerFactory.getLogger("FlycastCrashPatch");

    /** false = patch fully disabled (same as commented apply in LibretroCoreWindows). */
    private static final boolean ENABLED = true;

    /** true = xor eax,eax (ret false); false = 6× NOP. */
    private static final boolean USE_RET_FALSE = true;

    private FlycastWindowsCrashPatch() {}

    interface WinKernel32 extends com.sun.jna.Library {
        WinKernel32 INSTANCE = com.sun.jna.Native.load("kernel32", WinKernel32.class);
        int GET_MODULE_HANDLE_EX_FLAG_FROM_ADDRESS = 0x00000004;
        int GET_MODULE_HANDLE_EX_FLAG_UNCHANGED_REFCOUNT = 0x00000002;
        Pointer GetModuleHandleW(String lpModuleName);
        boolean GetModuleHandleExW(int dwFlags, Pointer lpModuleName, PointerByReference phModule);
        boolean VirtualProtect(Pointer lpAddress, long dwSize, int flNewProtect, int[] lpflOldProtect);
        int PAGE_EXECUTE_READWRITE = 0x40;
    }

    private static final byte[] RET_FALSE_6 = {
            (byte) 0x33, (byte) 0xC0,
            (byte) 0x90, (byte) 0x90, (byte) 0x90, (byte) 0x90
    };
    private static final byte[] NOP6 = {
            (byte) 0x90, (byte) 0x90, (byte) 0x90,
            (byte) 0x90, (byte) 0x90, (byte) 0x90
    };

    /** After {@code Native.load}, before {@code retro_set_environment}. */
    public static void apply(Path corePath) {
        if (corePath == null || !ENABLED) {
            LOGGER.info("FlycastWindowsCrashPatch: disabled");
            return;
        }
        try {
            Pointer base = findFlycastBase(corePath);
            if (base == null || Pointer.nativeValue(base) == 0) {
                LOGGER.warn("FlycastWindowsCrashPatch: could not resolve module base");
                return;
            }
            long baseAddr = Pointer.nativeValue(base);
            LOGGER.info("FlycastWindowsCrashPatch: base=0x{} retFalse={}",
                    Long.toHexString(baseAddr), USE_RET_FALSE);

            byte[] patch = USE_RET_FALSE ? RET_FALSE_6 : NOP6;
            int n = applyEnabledSites(base, baseAddr, patch);
            LOGGER.info("FlycastWindowsCrashPatch: patched {} site(s)", n);
        } catch (Throwable t) {
            LOGGER.error("FlycastWindowsCrashPatch failed: {}", t.getMessage(), t);
        }
    }

    // BISECT — uncomment patchSite for the RVA you want (multiple allowed)
    private static int applyEnabledSites(Pointer imageBase, long baseAddr, byte[] patch) {
        int n = 0;
        // if (patchSite(imageBase, baseAddr, 0x668b6L, patch)) n++; // → 0x7a34a8 env timer
        // if (patchSite(imageBase, baseAddr, 0x669b0L, patch)) n++; // → 0x7a34a0 log timer

        // if (patchSite(imageBase, baseAddr, 0x668d6L, patch)) n++; // → 0x7a34a8 env caused crash at address
        // if (patchSite(imageBase, baseAddr, 0x6699bL, patch)) n++; // → 0x7a34a8 env log very interesting + crash at address
        // if (patchSite(imageBase, baseAddr, 0x669c7L, patch)) n++; // → 0x7a34a8 env larger log + address

        if (patchSite(imageBase, baseAddr, 0x668f6L, patch)) n++; // → 0x7a34a8 env gives picture




        // if (patchSite(imageBase, baseAddr, 0x66921L, patch)) n++; // → 0x7a34a8 env
        return n;
    }

    private static boolean patchSite(Pointer imageBase, long baseAddr, long rva, byte[] patch) {
        long target = readFf15TargetRva(imageBase, rva);
        if (!patchFf15Site(baseAddr, rva, patch)) {
            LOGGER.warn("FlycastWindowsCrashPatch: skip 0x{} (not ff15?)", Long.toHexString(rva));
            return false;
        }
        LOGGER.info("FlycastWindowsCrashPatch: patched 0x{} -> target 0x{}",
                Long.toHexString(rva),
                target >= 0 ? Long.toHexString(target) : "?");
        return true;
    }

    private static long readFf15TargetRva(Pointer imageBase, long callRva) {
        Pointer p = imageBase.share(callRva);
        if ((p.getByte(0) & 0xFF) != 0xFF || (p.getByte(1) & 0xFF) != 0x15) return -1;
        return callRva + 6L + p.getInt(2);
    }

    private static boolean patchFf15Site(long baseAddr, long rva, byte[] patchBytes) {
        long addr = baseAddr + rva;
        Pointer p = new Pointer(addr);
        if ((p.getByte(0) & 0xFF) != 0xFF || (p.getByte(1) & 0xFF) != 0x15) return false;
        long pageStart = addr & ~0xFFFL;
        int[] old = new int[1];
        if (!WinKernel32.INSTANCE.VirtualProtect(new Pointer(pageStart),
                0x1000L, WinKernel32.PAGE_EXECUTE_READWRITE, old)) {
            return false;
        }
        try {
            p.write(0, patchBytes, 0, patchBytes.length);
            return true;
        } finally {
            int[] restored = new int[1];
            WinKernel32.INSTANCE.VirtualProtect(new Pointer(pageStart), 0x1000L, old[0], restored);
        }
    }

    private static Pointer findFlycastBase(Path corePath) {
        try {
            NativeLibrary lib = NativeLibrary.getInstance(corePath.toAbsolutePath().toString());
            for (String sym : new String[] { "retro_api_version", "retro_init", "retro_load_game" }) {
                try {
                    Pointer fn = lib.getFunction(sym);
                    if (fn == null || Pointer.nativeValue(fn) == 0) continue;
                    PointerByReference mod = new PointerByReference();
                    int flags = WinKernel32.GET_MODULE_HANDLE_EX_FLAG_FROM_ADDRESS
                            | WinKernel32.GET_MODULE_HANDLE_EX_FLAG_UNCHANGED_REFCOUNT;
                    if (WinKernel32.INSTANCE.GetModuleHandleExW(flags, fn, mod)) {
                        Pointer base = mod.getValue();
                        if (base != null && Pointer.nativeValue(base) != 0) return base;
                    }
                } catch (UnsatisfiedLinkError ignored) {}
            }
        } catch (Throwable ignored) {}
        for (String name : new String[] {
                "flycast_libretro.dll",
                corePath.toAbsolutePath().toString(),
                corePath.getFileName().toString()
        }) {
            Pointer p = WinKernel32.INSTANCE.GetModuleHandleW(name);
            if (p != null && Pointer.nativeValue(p) != 0) return p;
        }
        return null;
    }
}
