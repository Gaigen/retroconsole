package com.retroconsole.bridge;

import com.sun.jna.NativeLibrary;
import com.sun.jna.Pointer;
import com.sun.jna.ptr.PointerByReference;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Path;

/**
 * Runtime NOP patch for {@code flycast_libretro.dll} on Windows.
 *
 * <p>Flycast can dereference unregistered libretro callback slots via {@code ff 15}
 * thunks, yielding {@code nullptr+0x28} (0xC0000005). Linux uses four fixed RVAs
 * ({@link LibretroCoreLinux}); on Windows we patch a known six-site option-callback
 * cluster by default.
 *
 * <p><b>Do not</b> scan all {@code ff 15} sharing one IAT target — that slot is also
 * used by {@code GET_VARIABLE} during CUE parse and breaks load ({@code 0 tracks}).
 *
 * <p>Stages (bisect via {@code -Dretroconsole.flycast.patch=...}):
 * <ul>
 *   <li>{@code cluster} (default) — ff15 only in RVA window 0x668b6..0x669c7</li>
 *   <li>{@code none} — disabled</li>
 *   <li>{@code anchor-scan} — global IAT-target scan (breaks CUE; debug only)</li>
 *   <li>{@code slots}, {@code rlg} — over-broad scans (break load)</li>
 *   <li>{@code rvas} — explicit RVAs via {@code -Dretroconsole.flycast.patch.rvas=668b6,...}</li>
 * </ul>
 */
public final class FlycastWindowsCrashPatch {
    private static final Logger LOGGER = LoggerFactory.getLogger("FlycastCrashPatch");

    static final String PATCH_PROP = "retroconsole.flycast.patch";
    static final String DEFAULT_PATCH = "cluster";

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

    private static final byte[] NOP6 = {
            (byte) 0x90, (byte) 0x90, (byte) 0x90,
            (byte) 0x90, (byte) 0x90, (byte) 0x90
    };
    private static final byte[] NOP2 = { (byte) 0x90, (byte) 0x90 };

    private static final long FLYCAST_CALLBACK_SLOT_LO = 0x7A2000L;
    private static final long FLYCAST_CALLBACK_SLOT_HI = 0x7A5000L;

    /** Six option-callback {@code ff15} sites (RVA 0x668b6 .. 0x669c7). */
    private static final long OPTION_CALLBACK_CLUSTER_LO = 0x668b6L;
    private static final long OPTION_CALLBACK_CLUSTER_HI = 0x669cdL;

    /** Apply after {@code Native.load(flycast_libretro.dll)}, before {@code retro_set_environment}. */
    public static void apply(Path corePath) {
        if (corePath == null) return;

        String prop = System.getProperty(PATCH_PROP, DEFAULT_PATCH);
        boolean doCluster = patchStageEnabled("cluster", prop);
        boolean doAnchorScan = patchStageEnabled("anchor-scan", prop);
        boolean doRvas = patchStageEnabled("rvas", prop);
        boolean doSlots = patchStageEnabled("slots", prop);
        boolean doRlg = patchStageEnabled("rlg", prop);

        LOGGER.info("disableFlycastCrash: stages cluster={} anchor-scan={} rvas={} slots={} rlg={} ({}={})",
                doCluster, doAnchorScan, doRvas, doSlots, doRlg, PATCH_PROP, prop);
        if (!doCluster && !doAnchorScan && !doRvas && !doSlots && !doRlg) {
            LOGGER.info("disableFlycastCrash: disabled");
            return;
        }

        try {
            Pointer base = findFlycastBase(corePath);
            if (base == null || Pointer.nativeValue(base) == 0) {
                LOGGER.warn("disableFlycastCrash: could not resolve flycast module base");
                return;
            }
            long baseAddr = Pointer.nativeValue(base);
            LOGGER.info("disableFlycastCrash: Flycast baseAddr = 0x{}", Long.toHexString(baseAddr));

            long[] textRange = readTextSectionRange(base);
            if (textRange == null) {
                LOGGER.warn("disableFlycastCrash: could not locate .text section");
                return;
            }
            long textVa = textRange[0];
            long textEnd = textRange[1];

            if (doCluster) {
                int n = patchOptionCallbackCluster(base, baseAddr);
                LOGGER.info("disableFlycastCrash: [cluster] patched {} ff15 site(s) in 0x{}..0x{}",
                        n, Long.toHexString(OPTION_CALLBACK_CLUSTER_LO),
                        Long.toHexString(OPTION_CALLBACK_CLUSTER_HI));
            }

            if (doAnchorScan) {
                LOGGER.warn("disableFlycastCrash: [anchor-scan] global IAT scan — breaks CUE parse");
                patchAnchorScan(base, baseAddr, textVa, textEnd);
            }

            if (doRvas) {
                patchExplicitRvas(baseAddr, parseRvaList());
            }

            if (doSlots) {
                int slotPatches = patchFf15ToCallbackSlots(base, baseAddr, textVa, textEnd);
                LOGGER.info("disableFlycastCrash: [slots] patched {} site(s)", slotPatches);
            }

            if (doRlg) {
                int rlg = patchRetroLoadGameRegion(corePath, base, baseAddr, textVa, textEnd);
                LOGGER.info("disableFlycastCrash: [rlg] patched {} site(s) inside retro_load_game", rlg);
            }
        } catch (Throwable t) {
            LOGGER.error("disableFlycastCrash threw: {}", t.getMessage(), t);
        }
    }

    private static boolean patchStageEnabled(String stage, String prop) {
        for (String token : prop.toLowerCase().split("[,;\\s]+")) {
            if (token.isEmpty() || token.equals("none")) continue;
            if (token.equals("all") || token.equals("*") || token.equals(stage)) return true;
            if (stage.equals("cluster") && token.equals("anchor")) return true;
        }
        return false;
    }

    private static long[] parseRvaList() {
        String raw = System.getProperty("retroconsole.flycast.patch.rvas", "").trim();
        if (raw.isEmpty()) return new long[0];
        var out = new java.util.ArrayList<Long>();
        for (String token : raw.split("[,;\\s]+")) {
            if (token.isEmpty()) continue;
            String hex = token.startsWith("0x") ? token.substring(2) : token;
            try {
                out.add(Long.parseUnsignedLong(hex, 16));
            } catch (NumberFormatException e) {
                LOGGER.warn("disableFlycastCrash: ignoring bad RVA '{}'", token);
            }
        }
        return out.stream().mapToLong(Long::longValue).toArray();
    }

    private static int patchOptionCallbackCluster(Pointer imageBase, long baseAddr) {
        int patched = 0;
        int found = 0;
        for (long rva = OPTION_CALLBACK_CLUSTER_LO; rva + 6 <= OPTION_CALLBACK_CLUSTER_HI; rva++) {
            Pointer p = imageBase.share(rva);
            if ((p.getByte(0) & 0xFF) != 0xFF || (p.getByte(1) & 0xFF) != 0x15) continue;
            found++;
            if (nopFlycastInsn(baseAddr, rva, NOP6)) {
                patched++;
                LOGGER.info("disableFlycastCrash: [cluster] NOP RVA 0x{}", Long.toHexString(rva));
            }
        }
        LOGGER.info("disableFlycastCrash: [cluster] found {} ff15 in window, patched {}", found, patched);
        return patched;
    }

    private static void patchAnchorScan(Pointer imageBase, long baseAddr, long textVa, long textEnd) {
        long anchorRva = 0x668b6L;
        long targetRva = readFf15TargetRva(imageBase, anchorRva);
        if (targetRva < 0) {
            LOGGER.warn("disableFlycastCrash: anchor RVA 0x{} is not ff15", Long.toHexString(anchorRva));
            return;
        }
        var sites = findFf15CallSites(imageBase, textVa, textEnd, targetRva);
        LOGGER.info("disableFlycastCrash: [anchor-scan] {} site(s) share target 0x{}",
                sites.size(), Long.toHexString(targetRva));
        int patched = 0;
        for (long rva : sites) {
            if (nopFlycastInsn(baseAddr, rva, NOP6)) {
                patched++;
                LOGGER.info("disableFlycastCrash: [anchor-scan] NOP RVA 0x{}", Long.toHexString(rva));
            }
        }
        LOGGER.info("disableFlycastCrash: [anchor-scan] patched {}/{}", patched, sites.size());
    }

    private static void patchExplicitRvas(long baseAddr, long[] rvas) {
        if (rvas.length == 0) {
            LOGGER.warn("disableFlycastCrash: [rvas] enabled but retroconsole.flycast.patch.rvas is empty");
            return;
        }
        int patched = 0;
        for (long rva : rvas) {
            if (nopFlycastInsn(baseAddr, rva, NOP6)) {
                patched++;
                LOGGER.info("disableFlycastCrash: [rvas] NOP RVA 0x{}", Long.toHexString(rva));
            } else {
                LOGGER.warn("disableFlycastCrash: [rvas] skip RVA 0x{} (not ff15?)", Long.toHexString(rva));
            }
        }
        LOGGER.info("disableFlycastCrash: [rvas] patched {}/{}", patched, rvas.length);
    }

    private static int patchFf15ToCallbackSlots(Pointer imageBase, long baseAddr,
            long textVa, long textEnd) {
        var seen = new java.util.HashSet<Long>();
        int patched = 0;
        for (long rva = textVa; rva + 6 < textEnd; rva++) {
            Pointer p = imageBase.share(rva);
            if ((p.getByte(0) & 0xFF) != 0xFF || (p.getByte(1) & 0xFF) != 0x15) continue;
            int disp = p.getInt(2);
            long target = rva + 6L + disp;
            if (target < FLYCAST_CALLBACK_SLOT_LO || target >= FLYCAST_CALLBACK_SLOT_HI) continue;
            if (!seen.add(rva)) continue;
            if (nopFlycastInsn(baseAddr, rva, NOP6)) {
                patched++;
                LOGGER.info("disableFlycastCrash: [slots] NOP RVA 0x{} -> 0x{}",
                        Long.toHexString(rva), Long.toHexString(target));
            }
        }
        return patched;
    }

    private static int patchRetroLoadGameRegion(Path corePath, Pointer imageBase, long baseAddr,
            long textVa, long textEnd) {
        try {
            NativeLibrary lib = NativeLibrary.getInstance(corePath.toAbsolutePath().toString());
            Pointer fn = lib.getFunction("retro_load_game");
            if (fn == null || Pointer.nativeValue(fn) == 0) return 0;
            long fnRva = Pointer.nativeValue(fn) - baseAddr;
            long scanEnd = Math.min(fnRva + 0x12000L, textEnd);
            if (fnRva < textVa || fnRva >= textEnd) return 0;

            var seen = new java.util.HashSet<Long>();
            int patched = 0;
            int d1Candidates = 0;
            for (long rva = fnRva; rva + 6 < scanEnd; rva++) {
                Pointer p = imageBase.share(rva);
                int b0 = p.getByte(0) & 0xFF;
                int b1 = p.getByte(1) & 0xFF;
                if (b0 == 0xFF && b1 == 0x15) {
                    int disp = p.getInt(2);
                    long target = rva + 6L + disp;
                    if (target >= FLYCAST_CALLBACK_SLOT_LO && target < FLYCAST_CALLBACK_SLOT_HI
                            && seen.add(rva)) {
                        if (nopFlycastInsn(baseAddr, rva, NOP6)) patched++;
                    }
                } else if (b0 == 0xFF && b1 == 0xD1) {
                    d1Candidates++;
                }
            }
            if (d1Candidates > 0 && d1Candidates <= 64) {
                LOGGER.warn("disableFlycastCrash: [rlg] blanket ff-d1 NOP ({} sites) — breaks load",
                        d1Candidates);
                for (long rva = fnRva; rva + 2 < scanEnd; rva++) {
                    Pointer p = imageBase.share(rva);
                    if ((p.getByte(0) & 0xFF) == 0xFF && (p.getByte(1) & 0xFF) == 0xD1
                            && seen.add(rva)) {
                        if (nopFlycastInsn(baseAddr, rva, NOP2)) patched++;
                    }
                }
            }
            return patched;
        } catch (Throwable t) {
            LOGGER.warn("disableFlycastCrash: retro_load_game scan failed: {}", t.getMessage());
            return 0;
        }
    }

    private static long[] readTextSectionRange(Pointer imageBase) {
        int eLfanew = imageBase.getInt(0x3C);
        int numSections = imageBase.getShort(eLfanew + 6) & 0xFFFF;
        int optHeaderSize = imageBase.getShort(eLfanew + 20) & 0xFFFF;
        Pointer secTable = imageBase.share(eLfanew + 24 + optHeaderSize);
        for (int i = 0; i < numSections; i++) {
            Pointer sec = secTable.share(i * 40L);
            byte[] nameBytes = sec.getByteArray(0, 8);
            String name = new String(nameBytes, java.nio.charset.StandardCharsets.US_ASCII).trim();
            if (name.startsWith(".text")) {
                long vsize = Integer.toUnsignedLong(sec.getInt(8));
                long va = Integer.toUnsignedLong(sec.getInt(12));
                return new long[] { va, va + vsize };
            }
        }
        return null;
    }

    private static long readFf15TargetRva(Pointer imageBase, long callRva) {
        Pointer p = imageBase.share(callRva);
        if ((p.getByte(0) & 0xFF) != 0xFF || (p.getByte(1) & 0xFF) != 0x15) return -1;
        int disp = p.getInt(2);
        return callRva + 6L + disp;
    }

    private static java.util.List<Long> findFf15CallSites(Pointer imageBase,
            long textVa, long textEnd, long targetRva) {
        var sites = new java.util.ArrayList<Long>();
        for (long rva = textVa; rva + 6 < textEnd; rva++) {
            Pointer p = imageBase.share(rva);
            if ((p.getByte(0) & 0xFF) != 0xFF || (p.getByte(1) & 0xFF) != 0x15) continue;
            int disp = p.getInt(2);
            if (rva + 6L + disp == targetRva) sites.add(rva);
        }
        return sites;
    }

    private static boolean nopFlycastInsn(long baseAddr, long rva, byte[] nops) {
        long targetAddr = baseAddr + rva;
        Pointer p = new Pointer(targetAddr);
        int b0 = p.getByte(0) & 0xFF;
        int b1 = p.getByte(1) & 0xFF;
        if (nops.length == NOP6.length) {
            if (b0 != 0xFF || b1 != 0x15) return false;
        } else if (nops.length == NOP2.length) {
            if (b0 != 0xFF || b1 != 0xD1) return false;
        } else {
            return false;
        }
        long pageSize = 0x1000L;
        long pageStart = targetAddr & ~(pageSize - 1);
        int[] oldProtect = new int[1];
        if (!WinKernel32.INSTANCE.VirtualProtect(new Pointer(pageStart),
                pageSize, WinKernel32.INSTANCE.PAGE_EXECUTE_READWRITE, oldProtect)) {
            LOGGER.warn("disableFlycastCrash: VirtualProtect failed for RVA 0x{}",
                    Long.toHexString(rva));
            return false;
        }
        try {
            p.write(0, nops, 0, nops.length);
            return true;
        } finally {
            int[] restored = new int[1];
            WinKernel32.INSTANCE.VirtualProtect(new Pointer(pageStart), pageSize, oldProtect[0], restored);
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
                        if (base != null && Pointer.nativeValue(base) != 0) {
                            LOGGER.info("findFlycastBase: via {} = 0x{}",
                                    sym, Long.toHexString(Pointer.nativeValue(base)));
                            return base;
                        }
                    }
                } catch (UnsatisfiedLinkError ignored) {}
            }
        } catch (Throwable t) {
            LOGGER.debug("findFlycastBase failed: {}", t.getMessage());
        }
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

