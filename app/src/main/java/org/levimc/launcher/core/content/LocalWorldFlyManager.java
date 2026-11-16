package org.levimc.launcher.core.content;

import android.content.Context;
import android.util.AtomicFile;
import android.util.Base64;

import org.json.JSONException;
import org.json.JSONObject;
import org.levimc.launcher.core.content.nbt.BedrockNbtReader;
import org.levimc.launcher.core.content.nbt.BedrockNbtWriter;
import org.levimc.launcher.core.content.nbt.NativeNbt;
import org.levimc.launcher.core.content.nbt.NbtTag;
import org.levimc.launcher.util.LauncherStorage;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;

public final class LocalWorldFlyManager {
    private static final String[] ABILITY_FIELDS = {"mayfly", "flying", "flySpeed"};
    private static final String[] MULTIPLAYER_FIELDS = {
            "LANBroadcast", "LANBroadcastIntent", "MultiplayerGame",
            "MultiplayerGameIntent", "XBLBroadcast", "XBLBroadcastIntent"
    };

    public static final class Result {
        public final int worldCount;
        public final List<String> errors;

        private Result(int worldCount, List<String> errors) {
            this.worldCount = worldCount;
            this.errors = errors;
        }
    }

    private LocalWorldFlyManager() {}

    public static Result synchronize(
            Context context, String profileId, boolean enabled, int speedPercent) {
        File gameData = LauncherStorage.getProfileGameDataDir(context, profileId);
        File worlds = new File(gameData, "minecraftWorlds");
        File[] entries = worlds.listFiles();
        List<String> errors = new ArrayList<>();
        if (entries == null) return new Result(0, errors);

        File baselines = new File(context.getFilesDir(), "world-fly-baselines");
        if (!baselines.isDirectory() && !baselines.mkdirs()) {
            errors.add("Cannot create local-world backup directory");
            return new Result(0, errors);
        }

        int processed = 0;
        for (File world : entries) {
            File levelDat = new File(world, "level.dat");
            if (!world.isDirectory() || !levelDat.isFile()) continue;
            try {
                if (!world.getCanonicalFile().getParentFile().equals(worlds.getCanonicalFile())) continue;
                File baseline = new File(baselines, digest(world.getCanonicalPath()) + ".json");
                if (enabled) {
                    if (!baseline.isFile()) saveBaseline(levelDat, baseline);
                    applyFlight(levelDat, speedPercent);
                } else if (baseline.isFile()) {
                    restoreBaseline(levelDat, baseline);
                    if (!baseline.delete()) throw new IOException("Could not remove restored backup");
                } else {
                    continue;
                }
                processed++;
            } catch (Exception error) {
                errors.add(world.getName() + ": " + error.getMessage());
            }
        }
        return new Result(processed, errors);
    }

    private static void saveBaseline(File levelDat, File destination) throws IOException {
        BedrockNbtReader reader = new BedrockNbtReader();
        NbtTag root = reader.readFile(levelDat);
        if (root.getType() != NbtTag.TAG_COMPOUND) throw new IOException("Invalid level.dat root");
        JSONObject backup = new JSONObject();
        NbtTag abilities = root.getTag("abilities");
        if (abilities != null && abilities.getType() != NbtTag.TAG_COMPOUND)
            throw new IOException("Invalid abilities compound");
        try {
            backup.put("abilities_present", abilities != null);
            for (String field : ABILITY_FIELDS) {
                putEncoded(backup, "ability_" + field,
                        abilities == null ? null : abilities.getTag(field));
            }
            for (String field : MULTIPLAYER_FIELDS) {
                putEncoded(backup, "world_" + field, root.getTag(field));
            }
        } catch (JSONException error) {
            throw new IOException("Could not record original world settings", error);
        }
        writeAtomic(destination, backup.toString().getBytes(StandardCharsets.UTF_8));
    }

    private static void applyFlight(File levelDat, int speedPercent) throws IOException {
        BedrockNbtReader reader = new BedrockNbtReader();
        NbtTag root = reader.readFile(levelDat);
        if (root.getType() != NbtTag.TAG_COMPOUND) throw new IOException("Invalid level.dat root");

        NbtTag abilities = root.getTag("abilities");
        if (abilities == null) {
            abilities = new NbtTag(NbtTag.TAG_COMPOUND, "abilities", new LinkedHashMap<String, NbtTag>());
            root.putTag("abilities", abilities);
        } else if (abilities.getType() != NbtTag.TAG_COMPOUND) {
            throw new IOException("Invalid abilities compound");
        }

        putByte(abilities, "mayfly", 1);
        putByte(abilities, "flying", 0);
        float speed = 0.05f * Math.max(25, Math.min(500, speedPercent)) / 100f;
        abilities.putTag("flySpeed", new NbtTag(NbtTag.TAG_FLOAT, "flySpeed", speed));
        for (String field : MULTIPLAYER_FIELDS) putByte(root, field, 0);
        writeLevelDat(levelDat, root, reader.getHeaderVersion());
    }

    private static void restoreBaseline(File levelDat, File source) throws IOException {
        BedrockNbtReader reader = new BedrockNbtReader();
        NbtTag root = reader.readFile(levelDat);
        if (root.getType() != NbtTag.TAG_COMPOUND) throw new IOException("Invalid level.dat root");
        JSONObject backup;
        try {
            backup = new JSONObject(new String(
                    java.nio.file.Files.readAllBytes(source.toPath()), StandardCharsets.UTF_8));
        } catch (JSONException error) {
            throw new IOException("Invalid saved world settings", error);
        }
        NbtTag abilities = root.getTag("abilities");
        boolean hadAbilities = false;
        for (String field : ABILITY_FIELDS) {
            if (backup.has("ability_" + field)) {
                NbtTag original = decodeTag(backup, "ability_" + field);
                if (original != null) {
                    if (abilities == null) {
                        abilities = new NbtTag(
                                NbtTag.TAG_COMPOUND, "abilities", new LinkedHashMap<String, NbtTag>());
                        root.putTag("abilities", abilities);
                    }
                    if (abilities.getType() != NbtTag.TAG_COMPOUND)
                        throw new IOException("Invalid abilities compound");
                    abilities.putTag(field, original);
                    hadAbilities = true;
                } else if (abilities != null && abilities.getType() == NbtTag.TAG_COMPOUND) {
                    abilities.getCompound().remove(field);
                }
            }
        }
        if (abilities != null && abilities.getType() == NbtTag.TAG_COMPOUND
                && abilities.getCompound().isEmpty() && !hadAbilities
                && !backup.optBoolean("abilities_present", false)) {
            root.getCompound().remove("abilities");
        }
        for (String field : MULTIPLAYER_FIELDS) {
            NbtTag original = decodeTag(backup, "world_" + field);
            if (original == null) root.getCompound().remove(field);
            else root.putTag(field, original);
        }
        writeLevelDat(levelDat, root, reader.getHeaderVersion());
    }

    private static void putEncoded(JSONObject target, String key, NbtTag tag) throws JSONException, IOException {
        if (tag == null) {
            target.put(key, JSONObject.NULL);
            return;
        }
        target.put(key, Base64.encodeToString(
                NativeNbt.encode(tag, 0, NativeNbt.LITTLE_ENDIAN, false), Base64.NO_WRAP));
    }

    private static NbtTag decodeTag(JSONObject source, String key) throws IOException {
        if (!source.has(key) || source.isNull(key)) return null;
        byte[] bytes;
        try {
            bytes = Base64.decode(source.getString(key), Base64.NO_WRAP);
        } catch (JSONException | IllegalArgumentException error) {
            throw new IOException("Invalid saved world setting: " + key, error);
        }
        return NativeNbt.decode(bytes, 0, NativeNbt.LITTLE_ENDIAN).root;
    }

    private static void putByte(NbtTag compound, String key, int value) {
        compound.putTag(key, new NbtTag(NbtTag.TAG_BYTE, key, (byte) value));
    }

    private static void writeLevelDat(File file, NbtTag root, int headerVersion) throws IOException {
        BedrockNbtWriter writer = new BedrockNbtWriter();
        writer.setHeaderVersion(headerVersion);
        writer.writeFile(file, root);
    }

    private static void writeAtomic(File file, byte[] bytes) throws IOException {
        AtomicFile atomicFile = new AtomicFile(file);
        FileOutputStream output = null;
        try {
            output = atomicFile.startWrite();
            output.write(bytes);
            atomicFile.finishWrite(output);
        } catch (IOException error) {
            if (output != null) atomicFile.failWrite(output);
            throw error;
        }
    }

    private static String digest(String value) throws IOException {
        try {
            byte[] hash = MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8));
            StringBuilder result = new StringBuilder(hash.length * 2);
            for (byte item : hash) result.append(String.format("%02x", item));
            return result.toString();
        } catch (NoSuchAlgorithmException error) {
            throw new IOException("SHA-256 is unavailable", error);
        }
    }
}
