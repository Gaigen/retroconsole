package com.retroconsole.library;

import com.retroconsole.network.RetroLibraryPacket;
import com.retroconsole.platform.RetroConsolePaths;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.channels.SeekableByteChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.stream.Stream;
import java.util.zip.ZipFile;

/** Библиотека: сканирует ядра и игры, скрывает мусор, определяет системы. */
public final class RomLibrary {

    public static Path coresDir() { return RetroConsolePaths.coresDir(); }
    public static Path romsDir()  { return RetroConsolePaths.romsDir(); }

    private static Path sniffCache() {
        return RetroConsolePaths.systemDir().resolve("systems.v3.cache");
    }

    private static final List<String> ROM_EXTS = List.of(".nes", ".gb", ".gbc", ".gba",
            ".sfc", ".smc", ".gen", ".md", ".sms", ".gg", ".zip", ".bin", ".cue",
            ".iso", ".chd", ".cdi", ".gdi", ".cso");
    /** В подпапках принимаем любые файлы, кроме заведомого мусора. */
    private static final List<String> IGNORE_EXTS = List.of(".txt", ".png", ".jpg", ".jpeg",
            ".pdf", ".sav", ".srm", ".state", ".cfg", ".json", ".dat", ".xml");

    public record Core(String id, GameSystem system, String displayName) {}
    public record Rom(String id, Path path, String ext, long size,
                      GameSystem system, String displayName, boolean misplaced) {}

    public final List<Core> cores = new ArrayList<>();
    public final List<Rom> roms = new ArrayList<>();
    private final Properties sniffCache = new Properties();

    public void scan() {
        GameSystem.reload();          // подхватываем systems.json и сбрасываем папочные системы
        cores.clear();
        roms.clear();
        scanCores();
        scanRoms();
    }

    private void scanCores() {
        try (var stream = Files.newDirectoryStream(ensureDir(coresDir()))) {
            for (Path p : stream) {
                String name = p.getFileName().toString();
                if (name.startsWith(".")) continue;
                String lower = name.toLowerCase(Locale.ROOT);
                if (!lower.endsWith(".so") && !lower.endsWith(".dll") && !lower.endsWith(".dylib")) continue;
                String id = name.substring(0, name.lastIndexOf('.'));
                if (id.startsWith("lib") && !id.contains("libretro")) continue;
                GameSystem sys = GameSystem.byCore(id);
                String pretty = sys == GameSystem.OTHER ? id
                        : sys.fullName + " · " + id.replace("_libretro", "");
                cores.add(new Core(id, sys, pretty));
                if (sys != GameSystem.OTHER)
                    ensureDir(romsDir().resolve(sys.folder));
            }
        } catch (IOException ignored) {}
        cores.sort(Comparator.comparing(c -> c.displayName, String.CASE_INSENSITIVE_ORDER));
    }

    private void scanRoms() {
        List<Path> files = new ArrayList<>();
        Path romsRoot = romsDir();
        try (Stream<Path> s = Files.walk(ensureDir(romsRoot), 2)) {
            s.filter(Files::isRegularFile).forEach(files::add);
        } catch (IOException ignored) {}

        Set<String> tracks = new HashSet<>();
        for (Path p : files) {
            String lower = p.getFileName().toString().toLowerCase(Locale.ROOT);
            if (lower.endsWith(".cue") || lower.endsWith(".gdi"))
                tracks.addAll(referencedFiles(p));
        }

        Set<String> knownExts = new HashSet<>(ROM_EXTS);
        for (GameSystem s : GameSystem.all()) knownExts.addAll(s.exts);

        loadProps(sniffCache, sniffCache());
        for (Path p : files) {
            String name = p.getFileName().toString();
            String lower = name.toLowerCase(Locale.ROOT);
            if (name.startsWith(".") || lower.startsWith("flycast_cue_")) continue;
            if (tracks.contains(lower)) continue;
            String ext = extOf(lower);
            boolean inSubfolder = !p.getParent().equals(romsRoot);
            // в корне — только известные расширения; в подпапке — любые, кроме мусора
            if (inSubfolder ? IGNORE_EXTS.contains(ext) : !knownExts.contains(ext)) continue;

            long size = 0;
            try { size = Files.size(p); } catch (IOException ignored) {}

            GameSystem byFolder = inSubfolder
                    ? GameSystem.forFolder(p.getParent().getFileName().toString()) : null;
            GameSystem detected = detect(p, ext, size);
            GameSystem sys = byFolder != null ? byFolder
                    : detected != null ? detected : GameSystem.OTHER;
            boolean misplaced = byFolder != null && detected != null
                    && detected != GameSystem.OTHER && detected != byFolder;

            roms.add(new Rom(romsRoot.relativize(p).toString().replace('\\', '/'),
                    p, ext, size, sys, stripExt(name), misplaced));
        }
        saveProps(sniffCache, sniffCache(), "system sniff cache");
        roms.sort((a, b) -> a.displayName().compareToIgnoreCase(b.displayName()));
    }

    private GameSystem detect(Path file, String ext, long size) {
        GameSystem byExt = GameSystem.byExt(ext);
        if (byExt != null) return byExt;

        if (ext.equals(".zip")) {
            try (ZipFile zf = new ZipFile(file.toFile())) {
                var entries = zf.entries();
                while (entries.hasMoreElements()) {
                    String n = entries.nextElement().getName().toLowerCase(Locale.ROOT);
                    int dot = n.lastIndexOf('.');
                    GameSystem s = dot >= 0 ? GameSystem.byExt(n.substring(dot)) : null;
                    if (s != null) return s;
                }
            } catch (Exception ignored) {}
            return GameSystem.OTHER;
        }

        if (!ext.equals(".iso") && !ext.equals(".cue") && !ext.equals(".bin") && !ext.equals(".chd"))
            return GameSystem.OTHER;

        String cacheKey = file.getFileName() + "|" + size;
        GameSystem cached = GameSystem.byId(sniffCache.getProperty(cacheKey));
        if (cached != null) return cached;

        GameSystem sys = null;
        if (ext.equals(".chd")) {
            sys = sniffChd(file);
        } else if (ext.equals(".iso")) {
            sys = sniffIso(file, size);
        }
        if (sys == null || sys == GameSystem.OTHER) {
            Path target = ext.equals(".cue") ? firstDataTrackOf(file) : file;
            GameSystem byScan = sniff(target, size);
            if (byScan != GameSystem.OTHER) sys = byScan;
        }
        if (sys == null) sys = GameSystem.OTHER;
        sniffCache.setProperty(cacheKey, sys.id);
        return sys;
    }

    /** Разбор файловой системы ISO9660: находим SYSTEM.CNF / PSP_GAME по адресу. */
    private static GameSystem sniffIso(Path iso, long size) {
        try (SeekableByteChannel ch = Files.newByteChannel(iso)) {
            ByteBuffer pvd = readAt(ch, 0x8000, 2048);
            if (pvd == null || !ascii(pvd, 1, 5).equals("CD001")) return null;
            pvd.order(ByteOrder.LITTLE_ENDIAN);
            long rootLba = Integer.toUnsignedLong(pvd.getInt(158));
            int rootLen  = pvd.getInt(166);
            ByteBuffer dir = readAt(ch, rootLba * 2048, Math.min(rootLen, 32 * 2048));
            if (dir == null) return null;
            dir.order(ByteOrder.LITTLE_ENDIAN);
            int pos = 0;
            while (pos < dir.limit() - 34) {
                int len = dir.get(pos) & 0xFF;
                if (len == 0) { pos = (pos / 2048 + 1) * 2048; continue; }
                int nameLen = dir.get(pos + 32) & 0xFF;
                String name = ascii(dir, pos + 33, Math.min(nameLen, 16));
                if (name.startsWith("PSP_GAME")) return GameSystem.byId("PSP");
                if (name.startsWith("SYSTEM.CNF")) {
                    long lba = Integer.toUnsignedLong(dir.getInt(pos + 2));
                    ByteBuffer cnf = readAt(ch, lba * 2048, 2048);
                    if (cnf != null) {
                        String s = ascii(cnf, 0, cnf.limit());
                        if (s.contains("BOOT2")) return GameSystem.byId("PS2");
                        if (s.contains("BOOT"))  return GameSystem.byId("PS1");
                    }
                }
                pos += len;
            }
        } catch (IOException ignored) {}
        return null;
    }

    /** .chd: данные сжаты, но метаданные читаемы — тег GD-ROM означает Dreamcast. */
    private static GameSystem sniffChd(Path file) {
        try (SeekableByteChannel ch = Files.newByteChannel(file)) {
            ByteBuffer head = readAt(ch, 0, 124);
            if (head == null || head.limit() < 48 || !ascii(head, 0, 8).equals("MComprHD"))
                return GameSystem.OTHER;
            int version = head.getInt(12);
            long metaOff = version >= 5 ? head.getLong(40) : version == 4 ? head.getLong(36) : 0;
            int guard = 0;
            while (metaOff > 0 && guard++ < 64) {
                ByteBuffer m = readAt(ch, metaOff, 16);
                if (m == null || m.limit() < 16) break;
                String tag = ascii(m, 0, 4);
                if (tag.equals("CHGT") || tag.equals("CHGD")) return GameSystem.byId("DREAMCAST");
                metaOff = m.getLong(8);
            }
        } catch (IOException ignored) {}
        return GameSystem.OTHER;
    }

    /** Магические строки в первых 16 МБ (для .bin/.cue и как fallback). */
    private static GameSystem sniff(Path file, long fullSize) {
        if (file == null) return GameSystem.OTHER;
        try (SeekableByteChannel ch = Files.newByteChannel(file)) {
            ByteBuffer buf = ByteBuffer.allocate(16 * 1024 * 1024);
            ch.read(buf);
            String s = new String(buf.array(), 0, buf.position(), StandardCharsets.ISO_8859_1);
            if (s.contains("SEGA SEGAKATANA"))  return GameSystem.byId("DREAMCAST");
            if (s.contains("SEGADISCSYSTEM"))   return GameSystem.byId("SEGACD");
            if (s.contains("SEGA SEGASATURN"))  return GameSystem.byId("SATURN");
            if (s.contains("PSP_GAME") || s.contains("UMD_DATA")) return GameSystem.byId("PSP");
            if (s.contains("BOOT2"))            return GameSystem.byId("PS2");
            if (s.contains("BOOT = cdrom") || s.contains("PLAYSTATION")) return GameSystem.byId("PS1");
            if (s.contains("Sony Computer Entertainment"))
                return GameSystem.byId(fullSize > 800L * 1024 * 1024 ? "PS2" : "PS1");
        } catch (IOException ignored) {}
        return GameSystem.OTHER;
    }

    private static List<String[]> tracksOf(Path playlist) {
        List<String[]> out = new ArrayList<>();
        boolean gdi = playlist.getFileName().toString().toLowerCase(Locale.ROOT).endsWith(".gdi");
        try {
            for (String raw : Files.readAllLines(playlist, StandardCharsets.ISO_8859_1)) {
                String line = raw.trim();
                if (gdi) {
                    String[] tok = line.split("\\s+");
                    if (tok.length >= 5)
                        out.add(new String[]{tok[4].replace("\"", ""),
                                "4".equals(tok[2]) ? "data" : "audio"});
                } else {
                    String upper = line.toUpperCase(Locale.ROOT);
                    int q1 = line.indexOf('"'), q2 = line.lastIndexOf('"');
                    if (upper.startsWith("FILE") && q2 > q1)
                        out.add(new String[]{line.substring(q1 + 1, q2), "audio"});
                    else if (upper.startsWith("TRACK") && upper.contains("MODE") && !out.isEmpty())
                        out.get(out.size() - 1)[1] = "data";
                }
            }
        } catch (IOException ignored) {}
        return out;
    }

    private static Set<String> referencedFiles(Path playlist) {
        Set<String> s = new HashSet<>();
        for (String[] t : tracksOf(playlist)) s.add(t[0].toLowerCase(Locale.ROOT));
        return s;
    }

    private static Path firstDataTrackOf(Path cue) {
        List<String[]> tracks = tracksOf(cue);
        for (String[] t : tracks)
            if ("data".equals(t[1])) {
                Path p = cue.getParent().resolve(t[0]);
                if (Files.exists(p)) return p;
            }
        return tracks.isEmpty() ? null : cue.getParent().resolve(tracks.get(0)[0]);
    }

    private static ByteBuffer readAt(SeekableByteChannel ch, long pos, int len) throws IOException {
        if (pos < 0 || pos >= ch.size()) return null;
        len = (int) Math.min(len, ch.size() - pos);
        if (len <= 0) return null;
        ByteBuffer buf = ByteBuffer.allocate(len);
        ch.position(pos);
        while (buf.hasRemaining() && ch.read(buf) > 0) {}
        buf.flip();
        return buf;
    }

    private static String ascii(ByteBuffer b, int off, int len) {
        byte[] d = new byte[len];
        for (int i = 0; i < len; i++) d[i] = b.get(off + i);
        return new String(d, StandardCharsets.US_ASCII);
    }

    /** Заполнить каталог из S2C-пакета (мультиплеер / dedicated server). */
    public void loadFromNetwork(RetroLibraryPacket pkt) {
        GameSystem.applyServerCatalog(pkt.systems());
        cores.clear();
        roms.clear();
        for (RetroLibraryPacket.CoreEntry c : pkt.cores()) {
            GameSystem sys = GameSystem.byId(c.systemId());
            if (sys == null) sys = GameSystem.OTHER;
            cores.add(new Core(c.id(), sys, c.displayName()));
        }
        for (RetroLibraryPacket.RomEntry r : pkt.roms()) {
            GameSystem sys = GameSystem.byId(r.systemId());
            if (sys == null) sys = GameSystem.OTHER;
            roms.add(new Rom(r.id(), null, r.ext(), r.size(), sys, r.displayName(), r.misplaced()));
        }
        cores.sort(Comparator.comparing(c -> c.displayName, String.CASE_INSENSITIVE_ORDER));
        roms.sort((a, b) -> a.displayName().compareToIgnoreCase(b.displayName()));
    }

    // ---- утилиты ----
    public static Path ensureDir(Path dir) {
        try { Files.createDirectories(dir); } catch (IOException ignored) {}
        return dir;
    }
    public static String stripExt(String name) {
        int dot = name.lastIndexOf('.');
        return dot > 0 ? name.substring(0, dot) : name;
    }
    private static String extOf(String lower) {
        int dot = lower.lastIndexOf('.');
        return dot >= 0 ? lower.substring(dot) : "";
    }
    public static void loadProps(Properties p, Path file) {
        if (!Files.exists(file)) return;
        try (InputStream in = Files.newInputStream(file)) { p.load(in); } catch (IOException ignored) {}
    }
    public static void saveProps(Properties p, Path file, String comment) {
        try {
            Files.createDirectories(file.getParent());
            try (OutputStream out = Files.newOutputStream(file)) { p.store(out, comment); }
        } catch (IOException ignored) {}
    }
}